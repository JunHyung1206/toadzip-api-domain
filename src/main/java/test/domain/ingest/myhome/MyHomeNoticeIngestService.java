package test.domain.ingest.myhome;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.housing.Address;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.notice.Notice;
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;
import test.domain.notice.RentTerms;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 15108420 → {@link Notice} + {@link NoticeSupply} 적재.
 *
 * <p>원천 한 행은 공고가 아니라 공급행이다. 같은 pblancId 가 지역별·단지별로 여러 행에 걸쳐 나오고
 * 행마다 sumSuplyCo(공급 수)가 다르다. 그래서 pblancId 로 묶어 공고 하나를 만들고 행들을 공급행으로 붙인다.
 *
 * <p>여기서 만드는 공급행은 <b>단지 단위</b>다. 주택형까지 쪼개는 건 LH 15056765 를 받는
 * {@link test.domain.ingest.lh.LhNoticeIngestService} 가 하고, LH 원천이 없는 공고(SH·GH)는 이 모습이 최종이다.
 *
 * <p>정정공고는 새 pblancId 로 발급되고 beforePblancId 가 이전 공고를 가리킨다. 실데이터에서
 * beforePblancId 가 자기 pblancId 보다 늘 작다는 보장은 없어서, 숫자 정렬이 아니라 batch 안의
 * 의존관계(누가 누구를 이전 버전으로 가리키는가)를 그래프로 만들어 위상 정렬한다({@link #resolveChainOrder}).
 * 그래도 batch 를 넘어 원공고가 늦게 오는 경우가 남아서, 저장 뒤에 {@link #rebaseFollowers} 로
 * 이미 저장된 정정공고들의 뿌리를 옮긴다.
 *
 * <p>단지 카탈로그({@code housing_complex})와의 연결은 이 서비스의 책임이 아니다. 공고와 카탈로그는
 * 서로 다른 시점에 적재되므로 {@link test.domain.ingest.NoticeSupplyCatalogLinker} 가 따로 채운다.
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
    private final NoticeRepository noticeRepository;
    private final NoticeSupplyRepository noticeSupplyRepository;
    private final ConstructionRentalPolicy rentalPolicy;
    private final TransactionTemplate transactionTemplate;

    public MyHomeNoticeIngestService(@Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeApiClient,
                                     NoticeRepository noticeRepository,
                                     NoticeSupplyRepository noticeSupplyRepository,
                                     ConstructionRentalPolicy rentalPolicy,
                                     PlatformTransactionManager transactionManager) {
        this.myhomeApiClient = myhomeApiClient;
        this.noticeRepository = noticeRepository;
        this.noticeSupplyRepository = noticeSupplyRepository;
        this.rentalPolicy = rentalPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 원천이 공급유형(suplyTy)별로만 필터를 열어 둬서, 허용된 8개 코드를 모두 돈다.
     * 한 공급유형의 페이지를 전부 모은 뒤에만({@link #fetchComplete}) 그 결과를 신뢰한다. 그래서 한
     * 공급유형이 maxPages 안에 끝나지 않아도 다른 공급유형은 그대로 진행되고, 그 유형만 실패 1건으로 남는다.
     */
    public IngestReport ingest(int pageSize, int maxPages) {
        List<MyHomeNoticeItem> successfulRows = new ArrayList<>();
        IngestReport report = IngestReport.empty();
        for (MyHomeRentalType type : MyHomeRentalType.values()) {
            try {
                Optional<List<MyHomeNoticeItem>> rows = fetchComplete(type, pageSize, maxPages);
                if (rows.isPresent()) {
                    successfulRows.addAll(rows.orElseThrow());
                } else {
                    report = report.plus(IngestReport.oneFailed());
                }
            } catch (RuntimeException exception) {
                log.warn("마이홈 공고 공급유형 조회 실패: suplyTy={}", type.requestCode(), exception);
                report = report.plus(IngestReport.oneFailed());
            }
        }
        return report.plus(apply(successfulRows));
    }

    /**
     * 한 공급유형의 모든 페이지를 모은다. 빈 페이지 또는 pageSize 보다 짧은 페이지를 받으면 그 시점까지
     * 모은 행을 반환한다. maxPages 까지 매번 꽉 찬 페이지만 왔다면 어디서 끝나는지 알 수 없으므로
     * {@link Optional#empty()} 를 반환해 이 공급유형은 아무 것도 적용하지 않는다.
     */
    Optional<List<MyHomeNoticeItem>> fetchComplete(MyHomeRentalType type, int pageSize, int maxPages) {
        List<MyHomeNoticeItem> rows = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("suplyTy", type.requestCode());
            params.add("numOfRows", String.valueOf(pageSize));
            params.add("pageNo", String.valueOf(page));

            List<MyHomeNoticeItem> items =
                    myhomeApiClient.getList(RENTAL_PATH, params, LIST_POINTER, MyHomeNoticeItem.class);
            log.info("마이홈 공고 suplyTy={} {}페이지 {}행", type.requestCode(), page, items.size());
            if (items.isEmpty()) {
                return Optional.of(rows);
            }
            rows.addAll(items);
            if (items.size() < pageSize) {
                return Optional.of(rows);
            }
        }
        log.warn("마이홈 공고 suplyTy={} 가 maxPages={} 안에 끝나지 않아 부분 적재하지 않습니다: {}행",
                type.requestCode(), maxPages, rows.size());
        return Optional.empty();
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
        List<MyHomeNoticeItem> withIdentity = items.stream()
                .filter(item -> SourceValues.trimToNull(item.pblancId()) != null)
                .toList();

        DeduplicatedRows deduplicated = deduplicate(withIdentity);
        for (String conflictedId : deduplicated.conflictedNoticeIds()) {
            log.warn("공급행 키(pblancId, houseSn) 충돌로 공고 전체를 제외합니다: pblancId={}", conflictedId);
            report = report.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
        }

        Map<String, List<MyHomeNoticeItem>> groups = groupByNotice(deduplicated.accepted());
        ChainResolution chain = resolveChainOrder(groups);
        for (String excludedId : chain.excludedIds()) {
            log.warn("정정 체인 분기 또는 순환으로 공고를 제외합니다: pblancId={}", excludedId);
            report = report.plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
        }

        for (String pblancId : chain.order()) {
            try {
                IngestReport noticeReport = transactionTemplate.execute(
                        status -> applyNotice(pblancId, groups.get(pblancId)));
                report = report.plus(Objects.requireNonNull(noticeReport));
            } catch (RuntimeException exception) {
                log.warn("마이홈 공고 저장 실패: pblancId={}", pblancId, exception);
                report = report.plus(IngestReport.oneFailed());
            }
        }
        return report;
    }

    private record NoticeSupplyKey(String pblancId, Integer houseSn) {
    }

    private record DeduplicatedRows(
            List<MyHomeNoticeItem> accepted,
            Set<String> conflictedNoticeIds) {
    }

    /**
     * (pblancId, houseSn) 이 같은 행이 둘 이상 오면 첫 행만 받는다. 그 둘의 내용이 다르면(원천이
     * 모순된 응답을 준 것) 신뢰할 수 없으므로 그 공고 전체를 제외 대상으로 표시한다.
     */
    private DeduplicatedRows deduplicate(List<MyHomeNoticeItem> rows) {
        Map<NoticeSupplyKey, MyHomeNoticeItem> unique = new LinkedHashMap<>();
        Set<String> conflictedNoticeIds = new HashSet<>();
        for (MyHomeNoticeItem row : rows) {
            NoticeSupplyKey key = new NoticeSupplyKey(
                    SourceValues.trimToNull(row.pblancId()), row.houseSn());
            MyHomeNoticeItem previous = unique.putIfAbsent(key, row);
            if (previous != null && !previous.equals(row) && key.pblancId() != null) {
                conflictedNoticeIds.add(key.pblancId());
            }
        }
        List<MyHomeNoticeItem> accepted = unique.values().stream()
                .filter(row -> !conflictedNoticeIds.contains(SourceValues.trimToNull(row.pblancId())))
                .toList();
        return new DeduplicatedRows(accepted, Set.copyOf(conflictedNoticeIds));
    }

    private Map<String, List<MyHomeNoticeItem>> groupByNotice(List<MyHomeNoticeItem> items) {
        Map<String, List<MyHomeNoticeItem>> grouped = new LinkedHashMap<>();
        for (MyHomeNoticeItem item : items) {
            grouped.computeIfAbsent(item.pblancId().strip(), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private record ChainResolution(List<String> order, Set<String> excludedIds) {
    }

    /**
     * 정정 체인 저장 순서를 숫자 정렬이나 입력 순서에 기대지 않고 정한다.
     *
     * <ol>
     *   <li>beforePblancId 가 없거나 이번 batch 밖(=DB에 있거나, 어디에도 없어 새 체인이 되는 경우)이면
     *       의존할 게 없으니 바로 저장 가능하다.</li>
     *   <li>beforePblancId 가 이번 batch 안의 다른 그룹이면, 그 그룹을 먼저 저장해야 한다. 이 의존관계를
     *       그래프로 만들어 위상 정렬한다.</li>
     *   <li>같은 이전 버전(beforePblancId 값)을 둘 이상의 그룹이 가리키면(분기) 그 그룹들을 모두 제외한다.</li>
     *   <li>batch 안에서만 닫히는 순환은 위상 정렬로 처리되지 않고 남으므로, 그 남은 그룹들도 제외한다.</li>
     * </ol>
     */
    private ChainResolution resolveChainOrder(Map<String, List<MyHomeNoticeItem>> groups) {
        Map<String, String> beforeIds = new LinkedHashMap<>();
        for (Map.Entry<String, List<MyHomeNoticeItem>> entry : groups.entrySet()) {
            beforeIds.put(entry.getKey(), SourceValues.trimToNull(entry.getValue().get(0).beforePblancId()));
        }

        Map<String, List<String>> claimants = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : beforeIds.entrySet()) {
            if (entry.getValue() != null) {
                claimants.computeIfAbsent(entry.getValue(), key -> new ArrayList<>()).add(entry.getKey());
            }
        }
        Set<String> branchExcluded = new LinkedHashSet<>();
        for (List<String> children : claimants.values()) {
            if (children.size() > 1) {
                branchExcluded.addAll(children);
            }
        }

        Set<String> remaining = new LinkedHashSet<>(groups.keySet());
        remaining.removeAll(branchExcluded);

        Map<String, List<String>> childrenInBatch = new LinkedHashMap<>();
        Map<String, Integer> dependencyCount = new HashMap<>();
        for (String pblancId : remaining) {
            String before = beforeIds.get(pblancId);
            if (before != null && remaining.contains(before)) {
                childrenInBatch.computeIfAbsent(before, key -> new ArrayList<>()).add(pblancId);
                dependencyCount.put(pblancId, 1);
            } else {
                dependencyCount.put(pblancId, 0);
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        for (String pblancId : remaining) {
            if (dependencyCount.get(pblancId) == 0) {
                ready.add(pblancId);
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.poll();
            order.add(current);
            for (String child : childrenInBatch.getOrDefault(current, List.of())) {
                if (dependencyCount.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }

        Set<String> cycleExcluded = new LinkedHashSet<>(remaining);
        cycleExcluded.removeAll(order);

        Set<String> excludedIds = new LinkedHashSet<>(branchExcluded);
        excludedIds.addAll(cycleExcluded);
        return new ChainResolution(order, excludedIds);
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
            log.warn("공급행 제외: source=MYHOME_NOTICE, pblancId={}, houseSn={}, reason={}",
                    pblancId, row.houseSn(), IngestRejectionReason.INVALID_SOURCE_ROW);
            rejectedRows = rejectedRows.plus(
                    IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
        }
        if (validRows.isEmpty()) {
            log.warn("유효한 공급행이 없어 공고를 제외합니다: pblancId={}", pblancId);
            return rejectedRows;
        }
        MyHomeNoticeItem validHead = validRows.get(0);

        Optional<Notice> alreadyStored = noticeRepository.findBySourceNoticeId(pblancId);
        if (alreadyStored.isPresent()) {
            Notice existing = alreadyStored.get();
            boolean sameContent = existing.hasSameContentAs(snapshotOf(validHead))
                    && hasSameSupplyContent(existing, validRows);
            if (!sameContent) {
                // 마이홈은 정정 때 새 pblancId 를 발급한다. 그런데도 같은 pblancId 의 내용이 달라졌다면
                // 원천이 제자리에서 고친 것이라 신뢰할 수 없다. 기존 공고는 그대로 두고 이 요청만 제외한다.
                log.warn("공고 {}의 내용이 같은 pblancId 로 바뀌었습니다. 기존 내용을 유지하고 이 요청은 제외합니다.",
                        pblancId);
                return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW).plus(rejectedRows);
            }
            return IngestReport.oneUnchanged().plus(rejectedRows);
        }

        String beforeId = SourceValues.trimToNull(validHead.beforePblancId());
        Optional<Notice> previous = findPrevious(validHead, beforeId);
        Notice notice = previous
                .map(prior -> prior.nextVersion(pblancId, beforeId, snapshotOf(validHead)))
                .orElseGet(() -> Notice.firstVersion(pblancId, beforeId, snapshotOf(validHead)));

        noticeRepository.save(notice);
        saveSupplies(notice, validRows);
        rebaseFollowers(notice);

        IngestReport stored = previous.isPresent()
                ? IngestReport.oneVersioned()
                : IngestReport.oneCreated();
        return stored.plus(rejectedRows);
    }

    /** 공급행의 필수 조건은 이 둘뿐이다. PNU·주소·단지명 누락은 행을 버릴 이유가 아니다. */
    private boolean validSupplyLine(MyHomeNoticeItem row) {
        return row.houseSn() != null && row.houseSn() > 0;
    }

    /**
     * 같은 pblancId 를 다시 읽었을 때 저장된 공급행 집합·내용까지 완전히 같은지 본다.
     *
     * <p>저장된 행은 LH 적재가 주택형 단위로 쪼갠 뒤일 수 있어 마이홈 행과 1:1 이 아니다. 그래서
     * houseSn 으로 묶어 비교한다 — 같은 houseSn 행들은 마이홈 쪽 값이 전부 같은 복사본이라
     * 그중 아무 행이나 하나만 보면 된다. houseSn 이 없는 행은 마이홈에 짝이 없던 LH 행이라 비교 대상이 아니다.
     */
    private boolean hasSameSupplyContent(Notice existing, List<MyHomeNoticeItem> validRows) {
        Map<Integer, NoticeSupply> storedByHouseSn = noticeSupplyRepository
                .findByNoticeOrderByDisplayOrder(existing).stream()
                .filter(supply -> supply.getHouseSn() != null)
                .collect(Collectors.toMap(NoticeSupply::getHouseSn, supply -> supply,
                        (first, second) -> first, LinkedHashMap::new));
        Set<Integer> incomingHouseSns = validRows.stream()
                .map(MyHomeNoticeItem::houseSn)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!storedByHouseSn.keySet().equals(incomingHouseSns)) {
            return false;
        }
        for (MyHomeNoticeItem row : validRows) {
            if (!sameComplexValues(storedByHouseSn.get(row.houseSn()), row)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameComplexValues(NoticeSupply stored, MyHomeNoticeItem row) {
        return stored != null
                && Objects.equals(stored.getComplexSupplyCount(), row.sumSuplyCo())
                && Objects.equals(stored.getComplexName(), SourceValues.trimToNull(row.hsmpNm()))
                && Objects.equals(stored.getSuppliedPnu(), Address.normalizePnu(row.pnu()))
                && Objects.equals(stored.getSuppliedAddress(), SourceValues.trimToNull(row.fullAdres()))
                && Objects.equals(stored.getComplexTotalUnitCount(), SourceValues.toInt(row.totHshldCo()))
                && RentTerms.sameValues(stored.getRentTerms(), rentTermsOf(row))
                && Objects.equals(stored.getDetailUrl(), SourceValues.trimToNull(row.pcUrl()))
                && Objects.equals(stored.getMobileDetailUrl(), SourceValues.trimToNull(row.mobileUrl()));
    }

    private Optional<Notice> findPrevious(MyHomeNoticeItem head, String beforeId) {
        if (beforeId == null) {
            return Optional.empty();
        }
        Optional<Notice> previous = noticeRepository.findBySourceNoticeId(beforeId);
        if (previous.isEmpty()) {
            log.warn("정정공고 {}의 이전 공고 {}를 못 찾아 새 체인으로 시작합니다.", head.pblancId(), beforeId);
        }
        return previous;
    }

    /**
     * 이 공고를 이전 버전으로 가리키면서 이미 저장돼 있던 정정공고들을 이 아래로 옮긴다.
     * 정정공고가 원공고보다 먼저 들어온 경우인데, 옮기면 뿌리와 순번이 바뀌므로 그 뒷 버전들도 따라간다.
     */
    private void rebaseFollowers(Notice notice) {
        for (Notice follower : noticeRepository.findByBeforeSourceNoticeId(notice.getSourceNoticeId())) {
            if (Objects.equals(follower.getId(), notice.getId())) {
                continue;
            }
            log.info("뒤늦게 들어온 원공고 {} 아래로 정정공고 {}를 옮깁니다.",
                    notice.getSourceNoticeId(), follower.getSourceNoticeId());
            follower.rebaseOnto(notice);
            noticeRepository.save(follower);
            rebaseFollowers(follower);
        }
    }

    /** 공고 단위 값만 담는다. pcUrl 은 행마다 달라서(houseSn 이 붙는다) 여기 쓰면 안 되고 url 을 쓴다. */
    private NoticeSnapshot snapshotOf(MyHomeNoticeItem item) {
        LocalDate publishedOn = SourceValues.toDate(item.rcritPblancDe());
        return new NoticeSnapshot(
                SourceValues.trimToNull(item.sttusNm()),
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

    private void saveSupplies(Notice notice, List<MyHomeNoticeItem> rows) {
        for (int order = 0; order < rows.size(); order++) {
            MyHomeNoticeItem row = rows.get(order);
            noticeSupplyRepository.save(NoticeSupply.ofComplex(
                    notice,
                    order,
                    row.houseSn(),
                    SourceValues.trimToNull(row.hsmpNm()),
                    Address.normalizePnu(row.pnu()),
                    SourceValues.trimToNull(row.fullAdres()),
                    row.sumSuplyCo(),
                    SourceValues.toInt(row.totHshldCo()),
                    rentTermsOf(row),
                    SourceValues.trimToNull(row.pcUrl()),
                    SourceValues.trimToNull(row.mobileUrl())));
        }
        log.debug("공고 {} 공급행 {}건 저장", notice.getSourceNoticeId(), rows.size());
    }

    private RentTerms rentTermsOf(MyHomeNoticeItem row) {
        return new RentTerms(row.rentGtn(), row.enty(), row.surlus(), row.mtRntchrg());
    }
}
