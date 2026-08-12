package test.domain.ingest.myhome;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNotice;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.notice.RentTerms;
import test.domain.notice.SuppliedHousing;
import test.domain.source.SourceSystem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 15108420 → RecruitmentNotice + NoticeVersion + NoticeHousing 적재.
 *
 * <p>원천 한 행은 공고가 아니라 공급행이다. 같은 pblancId 가 지역별·단지별로 여러 행에 걸쳐 나오고
 * 행마다 sumSuplyCo(공급 수)가 다르다. 그래서 pblancId 로 묶어 공고버전 하나를 만들고 행들을 공급행으로 붙인다.
 *
 * <p>정정공고는 새 pblancId 로 발급되고 beforePblancId 가 이전 공고를 가리킨다. 실데이터에서
 * beforePblancId 는 항상 자기 pblancId 보다 작았고 19건 모두 같은 응답 안에 이전 공고가 들어 있었다.
 * 그래서 pblancId 숫자 오름차순으로 처리하면 이전 버전이 항상 먼저 저장된다.
 *
 * <p>단지 카탈로그({@code housing_complex})와의 연결은 이 서비스의 책임이 아니다. 그 매칭은 별도
 * 파생 matcher 가 한다.
 */
@Slf4j
@Service
public class MyHomeNoticeIngestService {

    /**
     * 공공임대주택 모집공고. <b>이 오퍼레이션만 받는다.</b>
     *
     * <p>같은 API 에 분양공고(`ltRsdtRcritNtcList`)도 있지만 담지 않는다. 우리 카탈로그는 임대주택이고,
     * 단지 원천(HWSPR04)이 공공<b>임대</b>주택만 담아서 분양공고의 공급행은 붙을 단지가 아예 없다
     * (실측 63행 중 11행만 붙었다). 게다가 분양은 보증금·계약금·잔금이 <b>분양대금 분할</b>이라
     * 같은 칸에 담기면 뜻이 달라진다.
     */
    public static final String RENTAL_PATH = "rsdtRcritNtcList";

    private static final String LIST_POINTER = "/response/body/item";

    private final OpenApiClient myhomeApiClient;
    private final RecruitmentNoticeRepository recruitmentNoticeRepository;
    private final NoticeVersionRepository noticeVersionRepository;
    private final NoticeHousingRepository noticeHousingRepository;
    private final ConstructionRentalPolicy rentalPolicy;
    private final TransactionTemplate transactionTemplate;

    public MyHomeNoticeIngestService(@Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeApiClient,
                                     RecruitmentNoticeRepository recruitmentNoticeRepository,
                                     NoticeVersionRepository noticeVersionRepository,
                                     NoticeHousingRepository noticeHousingRepository,
                                     ConstructionRentalPolicy rentalPolicy,
                                     PlatformTransactionManager transactionManager) {
        this.myhomeApiClient = myhomeApiClient;
        this.recruitmentNoticeRepository = recruitmentNoticeRepository;
        this.noticeVersionRepository = noticeVersionRepository;
        this.noticeHousingRepository = noticeHousingRepository;
        this.rentalPolicy = rentalPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public IngestReport ingest(String path, int pageSize, int maxPages) {
        List<MyHomeNoticeItem> allItems = new ArrayList<>();
        boolean reachedEnd = false;
        for (int page = 1; page <= maxPages; page++) {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("numOfRows", String.valueOf(pageSize));
            params.add("pageNo", String.valueOf(page));

            List<MyHomeNoticeItem> items =
                    myhomeApiClient.getList(path, params, LIST_POINTER, MyHomeNoticeItem.class);
            log.info("마이홈 공고 {} {}페이지 {}행", path, page, items.size());
            if (items.isEmpty()) {
                reachedEnd = true;
                break;
            }
            allItems.addAll(items);
            if (items.size() < pageSize) {
                reachedEnd = true;
                break;
            }
        }
        if (!reachedEnd) {
            log.warn("마이홈 공고가 maxPages={} 안에 끝나지 않아 부분 적재하지 않습니다: {}행", maxPages, allItems.size());
            return IngestReport.oneFailed();
        }
        return apply(allItems);
    }

    /** 공고 단위로 커밋된다. 이미 저장된 pblancId 는 건드리지 않으므로 몇 번을 돌려도 결과가 같다. */
    public IngestReport apply(List<MyHomeNoticeItem> items) {
        IngestReport report = IngestReport.empty();
        for (MyHomeNoticeItem item : items) {
            if (SourceValues.trimToNull(item.pblancId()) == null) {
                log.warn("공고 ID가 없는 원천 행을 제외합니다: {}", item.pblancNm());
                report = report.plus(IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY));
            }
        }
        for (Map.Entry<String, List<MyHomeNoticeItem>> notice : groupByNoticeInChainOrder(items).entrySet()) {
            IngestReport noticeReport = transactionTemplate.execute(
                    status -> applyNotice(notice.getKey(), notice.getValue()));
            report = report.plus(Objects.requireNonNull(noticeReport));
        }
        return report;
    }

    /** pblancId 숫자 오름차순 = 원공고 → 정정공고 순. 이 순서라야 beforePblancId 를 이미 저장된 버전에서 찾을 수 있다. */
    private Map<String, List<MyHomeNoticeItem>> groupByNoticeInChainOrder(List<MyHomeNoticeItem> items) {
        Map<String, List<MyHomeNoticeItem>> grouped = new LinkedHashMap<>();
        items.stream()
                .filter(item -> SourceValues.trimToNull(item.pblancId()) != null)
                .sorted(Comparator.comparingLong(MyHomeNoticeIngestService::sortableNoticeId))
                .forEach(item -> grouped.computeIfAbsent(item.pblancId().strip(), key -> new ArrayList<>()).add(item));
        return grouped;
    }

    /** pblancId 는 숫자 문자열이지만, 숫자가 아닌 값이 오더라도 정렬이 터지지 않게 뒤로 보낸다. */
    private static long sortableNoticeId(MyHomeNoticeItem item) {
        Integer numeric = SourceValues.toInt(item.pblancId());
        return numeric == null ? Long.MAX_VALUE : numeric;
    }

    private IngestReport applyNotice(String pblancId, List<MyHomeNoticeItem> rows) {
        MyHomeNoticeItem head = rows.get(0);
        String supplyTypeLabel = SourceValues.trimToNull(head.suplyTyNm());
        boolean inconsistentSupplyType = rows.stream()
                .map(MyHomeNoticeItem::suplyTyNm)
                .map(SourceValues::trimToNull)
                .anyMatch(label -> !Objects.equals(label, supplyTypeLabel));
        if (inconsistentSupplyType) {
            log.warn("한 공고 안에서 공급유형이 갈려 제외합니다: pblancId={}, firstSupplyType={}",
                    pblancId, supplyTypeLabel);
            return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
        }

        Optional<IngestRejectionReason> supplyTypeRejection = rentalPolicy.rejectSupplyType(supplyTypeLabel);
        if (supplyTypeRejection.isPresent()) {
            log.debug("공고 제외: pblancId={}, supplyType={}, reason={}",
                    pblancId, head.suplyTyNm(), supplyTypeRejection.get());
            return IngestReport.oneRejected(supplyTypeRejection.get());
        }

        List<MyHomeNoticeItem> validRows = new ArrayList<>();
        IngestReport rejectedRows = IngestReport.empty();
        for (MyHomeNoticeItem row : rows) {
            if (validSupplyLine(row)) {
                validRows.add(row);
                continue;
            }
            log.warn("공급행 제외: source=MYHOME_NOTICE, pblancId={}, houseSn={}, supplyType={}, reason={}",
                    pblancId, row.houseSn(), row.suplyTyNm(), IngestRejectionReason.INVALID_SOURCE_ROW);
            rejectedRows = rejectedRows.plus(
                    IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
        }
        if (validRows.isEmpty()) {
            log.warn("유효한 공급행이 없어 공고를 제외합니다: pblancId={}", pblancId);
            return rejectedRows;
        }
        MyHomeNoticeItem validHead = validRows.get(0);

        Optional<NoticeVersion> alreadyStored = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, pblancId);
        if (alreadyStored.isPresent()) {
            // 마이홈은 정정 때 새 pblancId 를 발급한다. 그런데도 내용이 달라졌다면 원천이 제자리에서 고친 것이라
            // 우리 스냅샷이 조용히 낡는다. 버전을 함부로 만들지는 않되 눈에 보이게 남긴다.
            if (!alreadyStored.get().hasSameContentAs(snapshotOf(validHead))) {
                log.warn("공고 {}의 내용이 같은 pblancId 로 바뀌었습니다. 원천이 제자리에서 수정한 것으로 보입니다.",
                        pblancId);
            }
            return IngestReport.oneUnchanged().plus(rejectedRows);
        }

        String beforeId = SourceValues.trimToNull(validHead.beforePblancId());
        Optional<NoticeVersion> previous = findPrevious(validHead, beforeId);
        NoticeVersion version = previous
                .map(prior -> prior.nextVersion(pblancId, beforeId, snapshotOf(validHead)))
                .orElseGet(() -> NoticeVersion.firstVersion(
                        resolveRoot(pblancId), pblancId, beforeId, snapshotOf(validHead)));

        noticeVersionRepository.save(version);
        saveNoticeHousing(version, validRows);

        IngestReport stored = previous.isPresent()
                ? IngestReport.oneVersioned()
                : IngestReport.oneCreated();
        return stored.plus(rejectedRows);
    }

    private boolean validSupplyLine(MyHomeNoticeItem row) {
        return row.houseSn() != null
                && row.houseSn() > 0
                && SourceValues.trimToNull(row.hsmpNm()) != null
                && SourceValues.trimToNull(row.fullAdres()) != null
                && rentalPolicy.hasValidPnu(row.pnu());
    }

    private Optional<NoticeVersion> findPrevious(MyHomeNoticeItem head, String beforeId) {
        if (beforeId == null) {
            return Optional.empty();
        }
        Optional<NoticeVersion> previous = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, beforeId);
        if (previous.isEmpty()) {
            log.warn("정정공고 {}의 이전 공고 {}를 못 찾아 새 체인으로 시작합니다.", head.pblancId(), beforeId);
        }
        return previous;
    }

    /** 체인이 처음이거나 끊겼을 때 이 pblancId 를 루트로 하는 공고를 찾거나 새로 만든다. */
    private RecruitmentNotice resolveRoot(String pblancId) {
        return recruitmentNoticeRepository
                .findBySourceSystemAndSourceRootNoticeId(SourceSystem.MYHOME_PORTAL, pblancId)
                .orElseGet(() -> recruitmentNoticeRepository.save(
                        new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, pblancId)));
    }

    /** 공고 단위 값만 담는다. pcUrl 은 행마다 달라서(houseSn 이 붙는다) 여기 쓰면 안 되고 url 을 쓴다. */
    private NoticeSnapshot snapshotOf(MyHomeNoticeItem item) {
        LocalDate publishedOn = SourceValues.toDate(item.rcritPblancDe());
        return new NoticeSnapshot(
                NoticeChangeStatus.fromStatusName(item.sttusNm()),
                publishedOn == null ? null : publishedOn.atStartOfDay(),
                SourceValues.trimToNull(item.pblancNm()),
                SourceValues.trimToNull(item.url()),
                SourceValues.toDate(item.beginDe()),
                SourceValues.toDate(item.endDe()),
                SourceValues.toDate(item.przwnerPresnatnDe()),
                SourceValues.trimToNull(item.suplyInsttNm()),
                SourceValues.trimToNull(item.houseTyNm()),
                SourceValues.trimToNull(item.suplyTyNm()),
                SourceValues.trimToNull(item.refrnc()));
    }

    private void saveNoticeHousing(NoticeVersion version, List<MyHomeNoticeItem> rows) {
        for (int order = 0; order < rows.size(); order++) {
            MyHomeNoticeItem row = rows.get(order);
            noticeHousingRepository.save(new NoticeHousing(
                    version,
                    order,
                    row.houseSn(),
                    row.sumSuplyCo(),
                    suppliedHousingOf(row),
                    rentTermsOf(row),
                    SourceValues.trimToNull(row.pcUrl()),
                    SourceValues.trimToNull(row.mobileUrl())));
        }
        log.debug("공고 {} 공급행 {}건 저장", version.getSourceNoticeId(), rows.size());
    }

    /** 단지에 붙든 안 붙든 공고가 그때 뭐라고 했는지는 그대로 남긴다. */
    private SuppliedHousing suppliedHousingOf(MyHomeNoticeItem row) {
        return new SuppliedHousing(
                SourceValues.trimToNull(row.hsmpNm()),
                SourceValues.trimToNull(row.fullAdres()),
                SourceValues.trimToNull(row.pnu()),
                SourceValues.trimToNull(row.rnCodeNm()),
                SourceValues.trimToNull(row.refrnLegaldongNm()),
                SourceValues.trimToNull(row.brtcNm()),
                SourceValues.trimToNull(row.signguNm()),
                SourceValues.trimToNull(row.heatMthdNm()),
                SourceValues.toInt(row.totHshldCo()));
    }

    private RentTerms rentTermsOf(MyHomeNoticeItem row) {
        return new RentTerms(row.rentGtn(), row.enty(), row.surlus(), row.mtRntchrg());
    }
}
