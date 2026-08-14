package test.domain.ingest.lh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.notice.LhUnitSupplyValues;
import test.domain.notice.Notice;
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * LH 두 원천을 한 번에 받아 이미 적재된 {@link Notice} 를 완성한다.
 *
 * <pre>
 *   15057999 lhLeaseNoticeDtlInfo1 → 일정 · 접수처 · 첨부 · 정정사유 · 단지 상세(dsSbd)
 *   15056765 lhLeaseNoticeSplInfo1 → 주택형별 공급정보(dsList01)
 * </pre>
 *
 * <p><b>왜 한 서비스인가.</b> 공급행 한 줄을 만들려면 세 원천이 다 필요하다 — 마이홈이 임대조건과 PNU를,
 * 15056765가 주택형과 금회 공급호수를, 15057999의 {@code dsSbd}가 그 둘을 잇는 지번주소를 준다.
 * 요청 파라미터도 같아서 나눠 부를 이유가 없다.
 *
 * <p><b>공급행을 다시 쓴다.</b> 마이홈 적재가 만들어 둔 단지 단위 행을 주택형 단위로 쪼갠다.
 *
 * <ol>
 *   <li>마이홈 공급행 ↔ {@code dsSbd} 를 지번주소로 잇는다. 양쪽에서 후보가 하나씩일 때만 확정하고,
 *       세대수가 둘 다 있는데 다르면 확정하지 않는다.</li>
 *   <li>{@code dsList01} 한 행마다 LH 단지명으로 {@code dsSbd} 를 찾고, 거기서 마이홈 공급행에 닿으면
 *       그 행의 임대조건·PNU를 복사해 주택형 행을 만든다.</li>
 *   <li>닿지 못한 {@code dsList01} 행도 버리지 않는다 — 금회 공급호수는 원천 사실이다.
 *       마이홈 값이 없는 채로 남는다.</li>
 *   <li>어떤 주택형 행도 못 만든 마이홈 공급행은 단지 단위 그대로 남는다.</li>
 * </ol>
 *
 * <p>그래서 한 공고 안에 알갱이가 다른 두 종류의 행이 섞인다. 합계를 낼 때 어느 쪽 기준인지 정해야 하는
 * 이유는 {@link NoticeSupply} 클래스 설명에 있다.
 *
 * <p>공고가 불변이라 LH 응답도 불변이다. {@code lh_fetched_at} 이 차 있으면 다시 부르지 않는다.
 */
@Slf4j
@Service
public class LhNoticeIngestService {

    private static final String DETAIL_PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";
    private static final String SUPPLY_PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";

    /** 공고 링크가 이 파라미터를 갖고 있으면 LH 공고다. */
    private static final String LH_LINK_MARK = "panId";

    private final OpenApiClient lhApiClient;
    private final ObjectMapper objectMapper;
    private final NoticeRepository noticeRepository;
    private final NoticeSupplyRepository supplyRepository;
    private final LhSupplyInfoTypeResolver supplyInfoTypeResolver;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LhNoticeIngestService(@Qualifier("lhApiClient") OpenApiClient lhApiClient,
                                 ObjectMapper objectMapper,
                                 NoticeRepository noticeRepository,
                                 NoticeSupplyRepository supplyRepository,
                                 LhSupplyInfoTypeResolver supplyInfoTypeResolver,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.lhApiClient = lhApiClient;
        this.objectMapper = objectMapper;
        this.noticeRepository = noticeRepository;
        this.supplyRepository = supplyRepository;
        this.supplyInfoTypeResolver = supplyInfoTypeResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 공고를 먼저 적재해 둔 뒤에 돌린다. LH 공고가 아닌 공고는 건드리지 않는다. */
    public IngestReport ingest() {
        IngestReport report = IngestReport.empty();
        for (Notice notice : noticeRepository.findByDetailUrlContaining(LH_LINK_MARK)) {
            report = report.plus(applyOne(notice));
        }
        return report;
    }

    IngestReport applyOne(Notice notice) {
        if (notice.getLhFetchedAt() != null) {
            return IngestReport.oneUnchanged();
        }
        Optional<LhNoticeRequest> request = requestFor(notice);
        if (request.isEmpty()) {
            return rejectionFor(notice);
        }

        JsonNode detailRoot;
        JsonNode supplyRoot;
        try {
            detailRoot = lhApiClient.getRaw(DETAIL_PATH, request.orElseThrow().toParams());
            supplyRoot = lhApiClient.getRaw(SUPPLY_PATH, request.orElseThrow().toParams());
        } catch (RuntimeException e) {
            log.warn("LH 공고 원천 조회에 실패했습니다: sourceNoticeId={}", notice.getSourceNoticeId(), e);
            return IngestReport.oneFailed();
        }
        return apply(notice, request.orElseThrow(), detailRoot, supplyRoot);
    }

    /** HTTP 없이 이미 받은 두 응답을 적용한다. 테스트가 호출과 저장을 분리해서 검증할 때 쓴다. */
    IngestReport apply(Notice notice, JsonNode detailRoot, JsonNode supplyRoot) {
        Optional<LhNoticeRequest> request = requestFor(notice);
        if (request.isEmpty()) {
            return rejectionFor(notice);
        }
        return apply(notice, request.orElseThrow(), detailRoot, supplyRoot);
    }

    private IngestReport apply(Notice notice, LhNoticeRequest request, JsonNode detailRoot, JsonNode supplyRoot) {
        if (notice.getLhFetchedAt() != null) {
            return IngestReport.oneUnchanged();
        }
        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> save(notice.getId(), request, detailRoot, supplyRoot)));
        } catch (RuntimeException e) {
            log.warn("LH 공고 적재 저장 실패: sourceNoticeId={}", notice.getSourceNoticeId(), e);
            return IngestReport.oneFailed();
        }
    }

    private IngestReport save(Long noticeId, LhNoticeRequest request, JsonNode detailRoot, JsonNode supplyRoot) {
        Notice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고입니다: " + noticeId));

        rebuildSupplies(notice, readComplexRows(detailRoot), readSupplyRows(supplyRoot));

        notice.clearLhChildren();
        addSchedules(notice, detailRoot);
        addReceptionPlaces(notice, detailRoot);
        addAttachments(notice, detailRoot);
        notice.markLhFetched(request.panId(), request.supplyInfoTypeCode(),
                correctionReason(detailRoot), LocalDateTime.now(clock));
        noticeRepository.save(notice);
        return IngestReport.oneCreated();
    }

    /**
     * 마이홈이 만든 단지 단위 행을 주택형 단위로 다시 쓴다. 행마다 자연키가 달라(LH 행은 단지명+주택형,
     * 마이홈 행은 houseSn) 부분 갱신이 성립하지 않으므로 공고 단위로 통째 교체한다.
     */
    private void rebuildSupplies(Notice notice, List<LhComplexRow> complexRows, List<LhUnitSupplyValues> supplyRows) {
        List<NoticeSupply> myHomeRows = supplyRepository.findByNoticeIdOrderByDisplayOrder(notice.getId());
        Map<LhComplexRow, NoticeSupply> byComplexRow = matchByAddress(myHomeRows, complexRows);

        List<NoticeSupply> rebuilt = new ArrayList<>();
        Set<NoticeSupply> splitRows = new LinkedHashSet<>();
        for (LhUnitSupplyValues values : supplyRows) {
            LhComplexRow complexRow = soleByLabel(complexRows, values.complexLabel());
            NoticeSupply myHomeRow = complexRow == null ? null : byComplexRow.get(complexRow);
            NoticeSupply row = myHomeRow == null
                    ? NoticeSupply.ofLhOnly(notice, rebuilt.size(), values)
                    : myHomeRow.splitInto(rebuilt.size(), values);
            if (myHomeRow != null) {
                splitRows.add(myHomeRow);
            }
            if (complexRow != null) {
                row.applyMoveInYearMonth(complexRow.moveInYearMonth());
            }
            rebuilt.add(row);
        }
        for (NoticeSupply myHomeRow : myHomeRows) {
            if (!splitRows.contains(myHomeRow)) {
                rebuilt.add(myHomeRow.copyAt(rebuilt.size()));
            }
        }

        // (notice_id, display_order) 유니크 때문에 물리 DELETE 가 INSERT 보다 먼저 나가야 한다.
        supplyRepository.deleteByNoticeId(notice.getId());
        supplyRepository.flush();
        supplyRepository.saveAll(rebuilt);
        log.debug("공고 {} 공급행 재구성: 주택형 {}건, 단지 단위 잔여 {}건",
                notice.getSourceNoticeId(), supplyRows.size(), rebuilt.size() - supplyRows.size());
    }

    /**
     * 마이홈 공급행과 LH 단지 상세를 지번주소로 잇는다. LH 주소가 공고 주소로 <b>시작</b>하면 후보다 —
     * LH 가 공고 주소 뒤에 동·호를 더 붙이는 것만 허용하고, 그 반대나 부분 일치는 받지 않는다.
     *
     * <p>양쪽에서 후보가 하나씩일 때만 확정한다. 세대수가 둘 다 있는데 다르면 주소가 유일해도 확정하지
     * 않는다 — 주소 접두어만 같고 실제로는 다른 단지일 수 있다.
     */
    private Map<LhComplexRow, NoticeSupply> matchByAddress(List<NoticeSupply> myHomeRows,
                                                           List<LhComplexRow> complexRows) {
        Map<NoticeSupply, List<LhComplexRow>> candidates = new LinkedHashMap<>();
        Map<LhComplexRow, List<NoticeSupply>> reverse = new LinkedHashMap<>();
        for (LhComplexRow complexRow : complexRows) {
            reverse.put(complexRow, new ArrayList<>());
        }
        for (NoticeSupply myHomeRow : myHomeRows) {
            List<LhComplexRow> hits = new ArrayList<>();
            for (LhComplexRow complexRow : complexRows) {
                if (isAddressCandidate(myHomeRow, complexRow)) {
                    hits.add(complexRow);
                    reverse.get(complexRow).add(myHomeRow);
                }
            }
            candidates.put(myHomeRow, hits);
        }

        Map<LhComplexRow, NoticeSupply> confirmed = new LinkedHashMap<>();
        for (NoticeSupply myHomeRow : myHomeRows) {
            List<LhComplexRow> hits = candidates.get(myHomeRow);
            if (hits.size() != 1) {
                continue;
            }
            LhComplexRow sole = hits.get(0);
            if (reverse.get(sole).size() != 1 || conflictingUnitCount(myHomeRow, sole)) {
                continue;
            }
            confirmed.put(sole, myHomeRow);
        }
        return confirmed;
    }

    private boolean isAddressCandidate(NoticeSupply myHomeRow, LhComplexRow complexRow) {
        String noticeAddress = normalize(myHomeRow.getSuppliedAddress());
        String lhAddress = normalize(complexRow.fullLotAddress());
        return noticeAddress != null && lhAddress != null && lhAddress.startsWith(noticeAddress);
    }

    private boolean conflictingUnitCount(NoticeSupply myHomeRow, LhComplexRow complexRow) {
        Integer notice = myHomeRow.getComplexTotalUnitCount();
        Integer lh = complexRow.totalUnitCount();
        return notice != null && lh != null && !notice.equals(lh);
    }

    /** LH 단지명이 같은 공고 안에서 정확히 한 번 겹칠 때만 잇는다. LH 이름끼리라 실측 290/290 이 맞았다. */
    private LhComplexRow soleByLabel(List<LhComplexRow> complexRows, String label) {
        String normalized = normalize(label);
        if (normalized == null) {
            return null;
        }
        List<LhComplexRow> hits = complexRows.stream()
                .filter(row -> normalized.equals(normalize(row.complexLabel())))
                .toList();
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** 공백만 지운다 — 하이픈 등 다른 구두점은 그대로 남겨 지번·도로명 표기 차이를 지우지 않는다. */
    static String normalize(String value) {
        return value == null ? null : value.strip().replace(" ", "");
    }

    private Optional<LhNoticeRequest> requestFor(Notice notice) {
        Optional<String> supplyInfoTypeCode = supplyInfoTypeResolver.resolve(notice.getSupplyTypeName());
        if (supplyInfoTypeCode.isEmpty() || notice.getDetailUrl() == null) {
            return Optional.empty();
        }
        try {
            return LhNoticeRequest.from(URI.create(notice.getDetailUrl()), supplyInfoTypeCode.orElseThrow());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 호출을 못 한 이유를 구분해 남긴다. 공급유형 문제와 링크 문제는 다른 후속 조치를 부른다. */
    private IngestReport rejectionFor(Notice notice) {
        if (supplyInfoTypeResolver.resolve(notice.getSupplyTypeName()).isEmpty()) {
            log.info("LH 공급정보코드를 아직 모르는 공급유형이라 호출을 건너뜁니다: sourceNoticeId={}, supplyType={}",
                    notice.getSourceNoticeId(), notice.getSupplyTypeName());
            return IngestReport.oneRejected(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE);
        }
        log.warn("LH 링크에서 호출 파라미터를 못 뽑아 건너뜁니다: {}", notice.getDetailUrl());
        return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
    }

    private String correctionReason(JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, "dsEtcInfo")) {
            String reason = SourceValues.trimToNull(
                    objectMapper.convertValue(row, LhNoticeDetail.EtcInfo.class).correctionReason());
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private void addSchedules(Notice notice, JsonNode root) {
        int sourceOrder = 0;
        for (JsonNode row : OpenApiClient.findRows(root, "dsSplScdl")) {
            int rowOrder = sourceOrder++;
            LhNoticeDetail.Schedule schedule = objectMapper.convertValue(row, LhNoticeDetail.Schedule.class);
            String complexName = SourceValues.trimToNull(schedule.complexName());
            String applicationPeriod = SourceValues.trimToNull(schedule.applicationPeriod());
            LocalDate documentTargetAnnouncementDate = date(
                    "dsSplScdl[%d].PPR_SBM_OPE_ANC_DT".formatted(rowOrder),
                    schedule.documentTargetAnnouncementDate());
            LocalDate documentSubmissionBeginDate = date(
                    "dsSplScdl[%d].PPR_ACP_ST_DT".formatted(rowOrder),
                    schedule.documentSubmissionBeginDate());
            LocalDate documentSubmissionEndDate = date(
                    "dsSplScdl[%d].PPR_ACP_CLSG_DT".formatted(rowOrder),
                    schedule.documentSubmissionEndDate());
            LocalDate contractBeginDate = date(
                    "dsSplScdl[%d].CTRT_ST_DT".formatted(rowOrder), schedule.contractBeginDate());
            LocalDate contractEndDate = date(
                    "dsSplScdl[%d].CTRT_ED_DT".formatted(rowOrder), schedule.contractEndDate());
            if (complexName == null && applicationPeriod == null
                    && documentTargetAnnouncementDate == null
                    && documentSubmissionBeginDate == null
                    && documentSubmissionEndDate == null
                    && contractBeginDate == null
                    && contractEndDate == null) {
                continue;
            }
            notice.addSchedule(complexName, applicationPeriod, documentTargetAnnouncementDate,
                    documentSubmissionBeginDate, documentSubmissionEndDate,
                    contractBeginDate, contractEndDate);
        }
    }

    private void addReceptionPlaces(Notice notice, JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, "dsCtrtPlc")) {
            LhNoticeDetail.Reception reception = objectMapper.convertValue(row, LhNoticeDetail.Reception.class);
            String address = SourceValues.trimToNull(reception.address());
            String detailAddress = SourceValues.trimToNull(reception.detailAddress());
            String operationBegin = SourceValues.trimToNull(reception.operationBegin());
            String operationEnd = SourceValues.trimToNull(reception.operationEnd());
            String phone = SourceValues.trimToNull(reception.phone());
            String guidance = SourceValues.trimToNull(reception.guidance());
            if (address == null && detailAddress == null && operationBegin == null
                    && operationEnd == null && phone == null && guidance == null) {
                continue;
            }
            notice.addReceptionPlace(address, detailAddress, operationBegin, operationEnd, phone, guidance);
        }
    }

    /** 공고문 파일과 단지 이미지를 응답 순서대로 한 줄로 잇는다. 둘 다 "공고에 딸린 파일"이라 같은 테이블이다. */
    private void addAttachments(Notice notice, JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, "dsAhflInfo")) {
            LhNoticeDetail.NoticeFile file = objectMapper.convertValue(row, LhNoticeDetail.NoticeFile.class);
            addAttachment(notice, file.kind(), file.name(), file.url(), null);
        }
        for (JsonNode row : OpenApiClient.findRows(root, "dsSbdAhfl")) {
            LhNoticeDetail.ComplexImage image = objectMapper.convertValue(row, LhNoticeDetail.ComplexImage.class);
            addAttachment(notice, image.kind(), image.name(), image.url(), image.complexName());
        }
    }

    /**
     * 원천이 값 대신 <b>컬럼 이름</b>을 담은 행을 같이 준다("첨부파일명", "다운로드" 같은 것).
     * URL 이 http 로 시작하는지로 그런 행을 걸러낸다.
     */
    private void addAttachment(Notice notice, String kind, String name, String url, String complexLabel) {
        String trimmedUrl = SourceValues.trimToNull(url);
        String trimmedKind = SourceValues.trimToNull(kind);
        String trimmedName = SourceValues.trimToNull(name);
        if (!isHttpUrl(trimmedUrl) || trimmedKind == null || trimmedName == null) {
            return;
        }
        notice.addAttachment(trimmedKind, trimmedName, trimmedUrl, SourceValues.trimToNull(complexLabel));
    }

    private boolean isHttpUrl(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            URI uri = URI.create(raw);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code dsSbd} 는 공급행을 잇는 다리로만 쓰고 저장하지 않는다. 입주예정월만 공급행으로 옮겨 간다. */
    private List<LhComplexRow> readComplexRows(JsonNode root) {
        List<LhComplexRow> rows = new ArrayList<>();
        int sourceOrder = 0;
        for (JsonNode row : OpenApiClient.findRows(root, "dsSbd")) {
            int rowOrder = sourceOrder++;
            LhNoticeDetail.ComplexDetail detail =
                    objectMapper.convertValue(row, LhNoticeDetail.ComplexDetail.class);
            LhComplexRow parsed = new LhComplexRow(
                    rowOrder,
                    SourceValues.trimToNull(detail.complexName()),
                    SourceValues.trimToNull(detail.lotAddress()),
                    SourceValues.trimToNull(detail.lotDetailAddress()),
                    SourceValues.toInt(detail.totalUnitCount()),
                    yearMonth("dsSbd[%d].MVIN_XPC_YM".formatted(rowOrder), detail.expectedMoveInYearMonth()));
            if (!parsed.isEmpty()) {
                rows.add(parsed);
            }
        }
        return rows;
    }

    private List<LhUnitSupplyValues> readSupplyRows(JsonNode root) {
        List<LhUnitSupplyValues> rows = new ArrayList<>();
        for (JsonNode row : OpenApiClient.findRows(root, "dsList01")) {
            LhUnitSupplyItem item = objectMapper.convertValue(row, LhUnitSupplyItem.class);
            LhUnitSupplyValues values = new LhUnitSupplyValues(
                    SourceValues.trimToNull(item.complexLabel()),
                    SourceValues.trimToNull(item.typeName()),
                    SourceValues.toDecimal(item.exclusiveArea()),
                    SourceValues.toDecimal(item.supplyArea()),
                    SourceValues.toInt(item.totalUnitCount()),
                    SourceValues.toInt(item.suppliedUnitCount()),
                    SourceValues.trimToNull(item.deposit()),
                    SourceValues.trimToNull(item.monthlyRent()));
            if (!values.isEmpty()) {
                rows.add(values);
            }
        }
        return rows;
    }

    private LocalDate date(String sourcePath, String raw) {
        String value = SourceValues.trimToNull(raw);
        if (value == null) {
            return null;
        }
        LocalDate parsed = SourceValues.toDate(value);
        if (parsed == null) {
            log.warn("LH 상세 날짜 변환 실패: field={}, raw={}", sourcePath, value);
        }
        return parsed;
    }

    private YearMonth yearMonth(String sourcePath, String raw) {
        String value = SourceValues.trimToNull(raw);
        if (value == null) {
            return null;
        }
        YearMonth parsed = SourceValues.toYearMonth(value);
        if (parsed == null) {
            log.warn("LH 상세 입주예정월 변환 실패: field={}, raw={}", sourcePath, value);
        }
        return parsed;
    }

    /**
     * {@code dsSbd} 한 행. 저장되지 않고 이 적재 안에서만 산다 — 예전 {@code lh_complex_detail} 테이블이
     * 하던 일 중 남길 값은 입주예정월뿐이고, 나머지는 공급행을 잇는 중간 계산이다.
     *
     * <p>{@code sourceOrder} 를 담는 이유는 값이 똑같은 두 행이 와도 서로 다른 키로 남게 하기 위해서다.
     */
    private record LhComplexRow(int sourceOrder,
                                String complexLabel,
                                String lotAddress,
                                String lotDetailAddress,
                                Integer totalUnitCount,
                                YearMonth moveInYearMonth) {

        /** 주소 매칭에 쓸 원문. 두 주소 칸을 버리지 않고 조립한다. */
        String fullLotAddress() {
            if (lotAddress == null) {
                return lotDetailAddress;
            }
            return lotDetailAddress == null ? lotAddress : lotAddress + " " + lotDetailAddress;
        }

        boolean isEmpty() {
            return complexLabel == null && lotAddress == null && lotDetailAddress == null
                    && totalUnitCount == null && moveInYearMonth == null;
        }
    }
}
