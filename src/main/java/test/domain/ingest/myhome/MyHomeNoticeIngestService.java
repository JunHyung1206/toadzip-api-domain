package test.domain.ingest.myhome;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.SupplyType;
import test.domain.ingest.IngestReport;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RentTerms;
import test.domain.notice.SourceSystem;
import test.domain.notice.SuppliedHousing;
import test.domain.notice.SupplyLine;
import test.domain.notice.SupplyLineRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 15108420 → NoticeVersion + SupplyLine 적재.
 *
 * <p>원천 한 행은 공고가 아니라 공급행이다. 같은 pblancId 가 지역별·단지별로 여러 행에 걸쳐 나오고
 * 행마다 sumSuplyCo(공급 수)가 다르다. 그래서 pblancId 로 묶어 공고버전 하나를 만들고 행들을 공급행으로 붙인다.
 *
 * <p>정정공고는 새 pblancId 로 발급되고 beforePblancId 가 이전 공고를 가리킨다. 실데이터에서
 * beforePblancId 는 항상 자기 pblancId 보다 작았고 19건 모두 같은 응답 안에 이전 공고가 들어 있었다.
 * 그래서 pblancId 숫자 오름차순으로 처리하면 이전 버전이 항상 먼저 저장된다.
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
    private final NoticeVersionRepository noticeVersionRepository;
    private final SupplyLineRepository supplyLineRepository;
    private final HousingComplexRepository complexRepository;

    public MyHomeNoticeIngestService(@Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeApiClient,
                                     NoticeVersionRepository noticeVersionRepository,
                                     SupplyLineRepository supplyLineRepository,
                                     HousingComplexRepository complexRepository) {
        this.myhomeApiClient = myhomeApiClient;
        this.noticeVersionRepository = noticeVersionRepository;
        this.supplyLineRepository = supplyLineRepository;
        this.complexRepository = complexRepository;
    }

    public IngestReport ingest(String path, int pageSize, int maxPages) {
        IngestReport report = IngestReport.empty();
        for (int page = 1; page <= maxPages; page++) {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("numOfRows", String.valueOf(pageSize));
            params.add("pageNo", String.valueOf(page));

            List<MyHomeNoticeItem> items =
                    myhomeApiClient.getList(path, params, LIST_POINTER, MyHomeNoticeItem.class);
            log.info("마이홈 공고 {} {}페이지 {}행", path, page, items.size());
            if (items.isEmpty()) {
                break;
            }
            report = report.plus(apply(items));
            if (items.size() < pageSize) {
                break;
            }
        }
        return report;
    }

    /**
     * 적재된 공고가 가리키는 지역코드 목록. 단지를 어느 시군구까지 받아야 공고가 다 붙는지 알려 준다.
     * 단지 API 가 시군구 단위로만 열려 있어서 필요한 값이다.
     */
    public List<String> regionCodesFromNotices() {
        return supplyLineRepository.findDistinctRegionCodes();
    }

    /**
     * 단지를 나중에 적재한 뒤 못 붙였던 공급행을 다시 붙인다.
     *
     * <p>공고 적재 시점에는 그 단지가 아직 DB 에 없을 수 있다. 단지 API 가 시군구 단위로만 열려 있어
     * 어느 지역을 받을지 공고에서 알아내야 하기 때문에, 순서가 공고 → 단지가 된다.
     *
     * @return 새로 붙인 공급행 수
     */
    public int rematchComplexes() {
        List<SupplyLine> unmatched = supplyLineRepository.findByComplexIsNullAndSuppliedHousingPnuIsNotNull();
        int matched = 0;
        for (SupplyLine line : unmatched) {
            HousingComplex complex = matchByPnu(line.getSuppliedHousing().getPnu());
            if (complex != null) {
                line.attachComplex(complex);
                supplyLineRepository.save(line);
                matched++;
            }
        }
        log.info("재매칭 대상 {}건 중 {}건 연결", unmatched.size(), matched);
        return matched;
    }

    /** 공고 단위로 커밋된다. 이미 저장된 pblancId 는 건드리지 않으므로 몇 번을 돌려도 결과가 같다. */
    public IngestReport apply(List<MyHomeNoticeItem> items) {
        IngestReport report = IngestReport.empty();
        for (Map.Entry<String, List<MyHomeNoticeItem>> notice : groupByNoticeInChainOrder(items).entrySet()) {
            report = report.plus(applyNotice(notice.getKey(), notice.getValue()));
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

        // 건설임대만 담는다. 매입임대·전세임대 공고는 대상 주택이 아예 없어(houseSn=0) 단지에 붙지 않는다.
        if (SupplyType.isPurchasedOrJeonse(head.suplyTyNm())) {
            return new IngestReport(0, 0, 0, 1);
        }

        Optional<NoticeVersion> alreadyStored = noticeVersionRepository.findBySourceNoticeId(pblancId);
        if (alreadyStored.isPresent()) {
            // 마이홈은 정정 때 새 pblancId 를 발급한다. 그런데도 내용이 달라졌다면 원천이 제자리에서 고친 것이라
            // 우리 스냅샷이 조용히 낡는다. 버전을 함부로 만들지는 않되 눈에 보이게 남긴다.
            if (!alreadyStored.get().hasSameContentAs(snapshotOf(head))) {
                log.warn("공고 {}의 내용이 같은 pblancId 로 바뀌었습니다. 원천이 제자리에서 수정한 것으로 보입니다.",
                        pblancId);
            }
            return new IngestReport(0, 0, 1, 0);
        }

        Optional<NoticeVersion> previous = findPrevious(head);
        NoticeVersion version = previous
                .map(prior -> prior.nextVersion(pblancId, snapshotOf(head)))
                .orElseGet(() -> NoticeVersion.firstVersion(pblancId, SourceSystem.MYHOME_PORTAL, snapshotOf(head)));

        noticeVersionRepository.save(version);
        saveSupplyLines(version, rows);

        return previous.isPresent() ? new IngestReport(0, 1, 0, 0) : new IngestReport(1, 0, 0, 0);
    }

    private Optional<NoticeVersion> findPrevious(MyHomeNoticeItem head) {
        String beforeId = SourceValues.trimToNull(head.beforePblancId());
        if (beforeId == null) {
            return Optional.empty();
        }
        Optional<NoticeVersion> previous = noticeVersionRepository.findBySourceNoticeId(beforeId);
        if (previous.isEmpty()) {
            log.warn("정정공고 {}의 이전 공고 {}를 못 찾아 새 체인으로 시작합니다.", head.pblancId(), beforeId);
        }
        return previous;
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

    private void saveSupplyLines(NoticeVersion version, List<MyHomeNoticeItem> rows) {
        int matched = 0;
        for (int order = 0; order < rows.size(); order++) {
            MyHomeNoticeItem row = rows.get(order);
            HousingComplex complex = matchComplex(row);
            if (complex != null) {
                matched++;
            }
            supplyLineRepository.save(new SupplyLine(
                    version,
                    complex,
                    order,
                    row.houseSn(),
                    row.sumSuplyCo(),
                    suppliedHousingOf(row),
                    rentTermsOf(row),
                    SourceValues.trimToNull(row.pcUrl()),
                    SourceValues.trimToNull(row.mobileUrl())));
        }
        log.debug("공고 {} 공급행 {}건 중 단지 매칭 {}건", version.getSourceNoticeId(), rows.size(), matched);
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

    /** 단지명은 매입임대에서 지역명("경기도 수원시")이 오기 때문에 이름 매칭은 위험하다. PNU 만 쓴다. */
    private HousingComplex matchComplex(MyHomeNoticeItem row) {
        return matchByPnu(SourceValues.trimToNull(row.pnu()));
    }

    /**
     * PNU 는 단지를 <b>유일하게 지목하지 않는다.</b> 같은 필지에 여러 hsmpSn 이 등록되기 때문이다.
     * 후보가 둘 이상이면 어느 쪽인지 알 방법이 없어서 <b>붙이지 않는다.</b>
     * 잘못 붙은 단지를 화면에 보여 주는 것보다 안 붙은 채로 두는 편이 낫다.
     */
    private HousingComplex matchByPnu(String pnu) {
        if (pnu == null) {
            return null;
        }
        List<HousingComplex> candidates = complexRepository.findAllByAddressPnu(pnu);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            log.debug("PNU {} 에 단지가 {}개라 어느 쪽인지 정할 수 없어 붙이지 않습니다.", pnu, candidates.size());
        }
        return null;
    }
}
