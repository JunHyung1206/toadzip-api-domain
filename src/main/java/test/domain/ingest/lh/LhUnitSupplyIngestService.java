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
import test.domain.notice.LhUnitSupplyBatch;
import test.domain.notice.LhUnitSupplyBatchRepository;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.source.SourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 15056765 → 이미 적재된 {@link NoticeVersion} 에 주택형별 공급정보를 덧입힌다.
 *
 * <p><b>왜 필요한가.</b> 마이홈({@link test.domain.notice.NoticeHousing})과 LH 상세({@link
 * test.domain.notice.LhComplexDetail})는 둘 다 "이 단지에 몇 호"까지만 말하고 <b>어느 주택형에 몇
 * 호</b>인지는 안 준다. 15056765만 그 배분({@code HTY_NNA}, {@code DDO_AR}, {@code NOW_HSH_CNT})을 준다.
 *
 * <p><b>어떻게 잇나.</b> {@link LhNoticeDetailIngestService}와 요청 파라미터가 완전히 같다 —
 * 마이홈 링크의 {@code panId}·{@code ccrCnntSysDsCd}·{@code uppAisTpCd}·{@code aisTpCd}에
 * {@link LhSupplyInfoTypeResolver}가 정하는 {@code SPL_INF_TP_CD}를 더한 것뿐이라 {@link LhNoticeRequest}를
 * 그대로 재사용한다. 다만 <b>엔드포인트와 응답이 다른 별도 호출</b>이라 {@link
 * test.domain.notice.LhNoticeSupplement}에 얹지 않고 별도 aggregate({@link LhUnitSupplyBatch})로 뒀다
 * — 그 이유는 {@link LhUnitSupplyBatch}의 클래스 설명에 있다.
 */
@Slf4j
@Service
public class LhUnitSupplyIngestService {

    private static final String PATH = "lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1";
    private static final String LIST_KEY = "dsList01";

    /** LH 링크가 이 파라미터를 갖고 있으면 LH 공고다. {@link LhNoticeDetailIngestService}와 같은 기준이다. */
    private static final String LH_LINK_MARK = "panId";

    private static final DateTimeFormatter RESPONSE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OpenApiClient lhApiClient;
    private final ObjectMapper objectMapper;
    private final NoticeVersionRepository noticeVersionRepository;
    private final LhUnitSupplyBatchRepository batchRepository;
    private final LhSupplyInfoTypeResolver supplyInfoTypeResolver;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LhUnitSupplyIngestService(@Qualifier("lhApiClient") OpenApiClient lhApiClient,
                                     ObjectMapper objectMapper,
                                     NoticeVersionRepository noticeVersionRepository,
                                     LhUnitSupplyBatchRepository batchRepository,
                                     LhSupplyInfoTypeResolver supplyInfoTypeResolver,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this.lhApiClient = lhApiClient;
        this.objectMapper = objectMapper;
        this.noticeVersionRepository = noticeVersionRepository;
        this.batchRepository = batchRepository;
        this.supplyInfoTypeResolver = supplyInfoTypeResolver;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 공고를 먼저 적재해 둔 뒤에 돌린다. LH 공고가 아닌 버전은 건드리지 않는다. */
    public IngestReport ingest() {
        IngestReport report = IngestReport.empty();
        for (NoticeVersion version : noticeVersionRepository.findByDetailUrlContaining(LH_LINK_MARK)) {
            report = report.plus(applyOne(version));
        }
        return report;
    }

    IngestReport applyOne(NoticeVersion version) {
        // 공고버전이 불변이라 공급정보 배치도 불변이다. 이미 받았으면 다시 부르지 않는다.
        if (batchRepository.existsByNoticeVersionId(version.getId())) {
            return IngestReport.oneUnchanged();
        }

        Optional<String> supplyInfoTypeCode = supplyInfoTypeResolver.resolve(version.getSupplyType());
        if (supplyInfoTypeCode.isEmpty()) {
            log.info("LH 공급정보코드를 아직 모르는 공급유형이라 호출을 건너뜁니다: sourceNoticeId={}, supplyType={}",
                    version.getSourceNoticeId(), version.getSupplyType());
            return IngestReport.oneRejected(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE);
        }

        Optional<LhNoticeRequest> request = requestFrom(version, supplyInfoTypeCode.orElseThrow());
        if (request.isEmpty()) {
            log.warn("LH 링크에서 호출 파라미터를 못 뽑아 건너뜁니다: {}", version.getDetailUrl());
            return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
        }

        JsonNode root;
        try {
            root = lhApiClient.getRaw(PATH, request.orElseThrow().toParams());
        } catch (RuntimeException e) {
            log.warn("LH 공급정보 조회에 실패했습니다: sourceNoticeId={}", version.getSourceNoticeId(), e);
            return IngestReport.oneFailed();
        }

        return apply(version, request.orElseThrow(), root);
    }

    /** HTTP 없이 이미 받은 응답을 저장한다. 테스트가 호출과 저장을 분리해서 검증할 때 쓴다. */
    IngestReport apply(NoticeVersion version, JsonNode root) {
        Optional<String> supplyInfoTypeCode = supplyInfoTypeResolver.resolve(version.getSupplyType());
        if (supplyInfoTypeCode.isEmpty()) {
            return IngestReport.oneRejected(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE);
        }
        Optional<LhNoticeRequest> request = requestFrom(version, supplyInfoTypeCode.orElseThrow());
        if (request.isEmpty()) {
            return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
        }
        return apply(version, request.orElseThrow(), root);
    }

    private IngestReport apply(NoticeVersion version, LhNoticeRequest request, JsonNode root) {
        if (batchRepository.existsByNoticeVersionId(version.getId())) {
            return IngestReport.oneUnchanged();
        }
        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> saveBatch(version, request, root)));
        } catch (RuntimeException e) {
            log.warn("LH 공급정보 배치 저장 실패: sourceNoticeId={}", version.getSourceNoticeId(), e);
            return IngestReport.oneFailed();
        }
    }

    private IngestReport saveBatch(NoticeVersion version, LhNoticeRequest request, JsonNode root) {
        LhUnitSupplyBatch batch = new LhUnitSupplyBatch(
                version,
                SourceSystem.LH_CHEONGYAK_PLUS,
                request.panId(),
                request.connectionSystemDivisionCode(),
                request.upperAnnouncementTypeCode(),
                request.announcementTypeCode(),
                request.supplyInfoTypeCode(),
                sourceRespondedAt(root),
                LocalDateTime.now(clock),
                datasetPresent(root));
        addUnitSupplies(batch, root);
        batchRepository.save(batch);
        return IngestReport.oneCreated();
    }

    private Optional<LhNoticeRequest> requestFrom(NoticeVersion version, String supplyInfoTypeCode) {
        try {
            return LhNoticeRequest.from(URI.create(version.getDetailUrl()), supplyInfoTypeCode);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private LocalDateTime sourceRespondedAt(JsonNode root) {
        List<JsonNode> header = OpenApiClient.findRows(root, "resHeader");
        if (header.isEmpty()) {
            return null;
        }
        String raw = SourceValues.trimToNull(header.get(0).path("RS_DTTM").asString(""));
        if (raw == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, RESPONSE_TIMESTAMP);
        } catch (DateTimeParseException e) {
            log.warn("LH 공급정보 응답시각 변환 실패: raw={}", raw);
            return null;
        }
    }

    private boolean datasetPresent(JsonNode root) {
        if (!root.isArray()) {
            return false;
        }
        for (JsonNode element : root) {
            if (!element.path(LIST_KEY).isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    private void addUnitSupplies(LhUnitSupplyBatch batch, JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, LIST_KEY)) {
            LhUnitSupplyItem item = objectMapper.convertValue(row, LhUnitSupplyItem.class);
            String complexLabel = SourceValues.trimToNull(item.complexLabel());
            String typeName = SourceValues.trimToNull(item.typeName());
            BigDecimal exclusiveArea = SourceValues.toDecimal(item.exclusiveArea());
            BigDecimal supplyArea = SourceValues.toDecimal(item.supplyArea());
            Integer totalUnitCount = SourceValues.toInt(item.totalUnitCount());
            Integer suppliedUnitCount = SourceValues.toInt(item.suppliedUnitCount());
            if (complexLabel == null && typeName == null && exclusiveArea == null
                    && supplyArea == null && totalUnitCount == null && suppliedUnitCount == null) {
                continue;
            }
            batch.addUnitSupply(batch.getUnitSupplies().size(), complexLabel, typeName,
                    exclusiveArea, supplyArea, totalUnitCount, suppliedUnitCount);
        }
    }
}
