package test.domain.ingest.lh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import test.domain.housing.Address;
import test.domain.housing.LhLeaseInfo;
import test.domain.housing.LhLeaseInfoBatch;
import test.domain.housing.LhLeaseInfoBatchRepository;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.IngestReport;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.match.LhLeaseInfoUnitTypeMatch;
import test.domain.match.LhLeaseInfoUnitTypeMatchRepository;
import test.domain.match.LhLeaseInfoUnitTypeMatchStatus;
import test.domain.source.SourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 15059475 전국 LH 임대단지 주택형 카탈로그를 저장하고, 엄격하게 확인된 행만 {@link UnitType}에 반영한다.
 * 원천에는 PNU가 없으므로 이름이 비슷하다는 이유만으로는 절대 갱신하지 않는다.
 */
@Slf4j
@Service
public class LhLeaseInfoIngestService {

    private static final String PATH = "lhLeaseInfo1/lhLeaseInfo1";
    private static final String LIST_KEY = "dsList";
    private static final String MATCHER_VERSION = "lh-lease-info-area-v1";
    private static final DateTimeFormatter RESPONSE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OpenApiClient lhApiClient;
    private final ObjectMapper objectMapper;
    private final LhLeaseInfoBatchRepository batchRepository;
    private final LhLeaseInfoUnitTypeMatchRepository matchRepository;
    private final HousingComplexRepository complexRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public LhLeaseInfoIngestService(@Qualifier("lhApiClient") OpenApiClient lhApiClient,
                                    ObjectMapper objectMapper,
                                    LhLeaseInfoBatchRepository batchRepository,
                                    LhLeaseInfoUnitTypeMatchRepository matchRepository,
                                    HousingComplexRepository complexRepository,
                                    UnitTypeRepository unitTypeRepository,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this.lhApiClient = lhApiClient;
        this.objectMapper = objectMapper;
        this.batchRepository = batchRepository;
        this.matchRepository = matchRepository;
        this.complexRepository = complexRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 필터 없이 전 페이지를 읽어 전국 카탈로그의 현재 스냅샷을 교체한다. */
    public IngestReport ingest(int pageSize, int maxPages) {
        if (pageSize < 1 || maxPages < 1) {
            throw new IllegalArgumentException("pageSize와 maxPages는 1 이상이어야 합니다.");
        }

        try {
            List<JsonNode> pages = new ArrayList<>();
            for (int page = 1; page <= maxPages; page++) {
                MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
                params.add("PG_SZ", String.valueOf(pageSize));
                params.add("PAGE", String.valueOf(page));
                JsonNode root = lhApiClient.getRaw(PATH, params);
                pages.add(root);
                if (OpenApiClient.findRows(root, LIST_KEY).size() < pageSize) {
                    return apply(pages);
                }
            }
            throw new IllegalStateException("15059475가 maxPages=%d 안에 끝나지 않았습니다.".formatted(maxPages));
        } catch (RuntimeException e) {
            log.warn("LH 임대주택단지 전국 조회에 실패했습니다.", e);
            return IngestReport.oneFailed();
        }
    }

    /** HTTP 없이 이미 받은 페이지 응답을 현재 스냅샷으로 적용한다. */
    IngestReport apply(List<JsonNode> pages) {
        if (!isCompleteSnapshot(pages)) {
            log.warn("LH 임대주택단지 응답에 유효한 전국 dsList 스냅샷이 없습니다.");
            return IngestReport.oneFailed();
        }
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> saveSnapshot(pages)));
        } catch (RuntimeException e) {
            log.warn("LH 임대주택단지 스냅샷 저장에 실패했습니다.", e);
            return IngestReport.oneFailed();
        }
    }

    private IngestReport saveSnapshot(List<JsonNode> pages) {
        boolean replacing = batchRepository.count() > 0;
        matchRepository.deleteAll();
        matchRepository.flush();
        batchRepository.deleteAll();
        batchRepository.flush();

        unitTypeRepository.findAll().forEach(unitType -> unitType.updateTotalUnitCount(null));

        LhLeaseInfoBatch batch = new LhLeaseInfoBatch(
                SourceSystem.LH_LEASE_INFO, sourceRespondedAt(pages), LocalDateTime.now(clock));
        addLeaseInfos(batch, pages);
        batchRepository.saveAndFlush(batch);
        match(batch);
        return replacing ? IngestReport.oneVersioned() : IngestReport.oneCreated();
    }

    private void addLeaseInfos(LhLeaseInfoBatch batch, List<JsonNode> pages) {
        int order = 0;
        for (JsonNode page : pages) {
            for (JsonNode row : OpenApiClient.findRows(page, LIST_KEY)) {
                LhLeaseInfoItem item = objectMapper.convertValue(row, LhLeaseInfoItem.class);
                batch.addLeaseInfo(order++,
                        SourceValues.trimToNull(item.areaName()),
                        SourceValues.trimToNull(item.supplyTypeName()),
                        SourceValues.trimToNull(item.complexLabel()),
                        SourceValues.toInt(item.complexTotalUnitCount()),
                        SourceValues.toDecimal(item.exclusiveArea()),
                        SourceValues.toInt(item.totalUnitCount()));
            }
        }
    }

    private void match(LhLeaseInfoBatch batch) {
        Map<CatalogKey, List<HousingComplex>> complexes = complexesByKey();
        LocalDateTime now = LocalDateTime.now(clock);
        List<MatchDecision> decisions = batch.getLeaseInfos().stream()
                .map(leaseInfo -> evaluate(leaseInfo, complexes, now))
                .toList();
        Map<Long, Integer> sourceRowsByUnitType = new HashMap<>();
        for (MatchDecision decision : decisions) {
            if (decision.status() == LhLeaseInfoUnitTypeMatchStatus.MATCHED) {
                sourceRowsByUnitType.merge(decision.unitType().getId(), 1, Integer::sum);
            }
        }

        for (MatchDecision decision : decisions) {
            if (decision.status() == LhLeaseInfoUnitTypeMatchStatus.MATCHED
                    && sourceRowsByUnitType.get(decision.unitType().getId()) > 1) {
                decision = decision.competingSourceRows(sourceRowsByUnitType.get(decision.unitType().getId()));
            }
            if (decision.status() == LhLeaseInfoUnitTypeMatchStatus.MATCHED) {
                decision.unitType().updateTotalUnitCount(decision.leaseInfo().getTotalUnitCount());
            }
            matchRepository.save(result(decision));
        }
    }

    private Map<CatalogKey, List<HousingComplex>> complexesByKey() {
        Map<CatalogKey, List<HousingComplex>> complexes = new HashMap<>();
        for (HousingComplex complex : complexRepository.findAll()) {
            CatalogKey key = CatalogKey.from(complex);
            if (key != null) {
                complexes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(complex);
            }
        }
        return complexes;
    }

    private MatchDecision evaluate(LhLeaseInfo leaseInfo,
                                   Map<CatalogKey, List<HousingComplex>> complexes,
                                   LocalDateTime now) {
        List<HousingComplex> complexCandidates = complexes.getOrDefault(CatalogKey.from(leaseInfo), List.of());
        if (complexCandidates.isEmpty()) {
            return decision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.UNMATCHED, 0, now,
                    "지역·단지명·공급유형이 모두 같은 카탈로그 단지가 없음");
        }
        if (complexCandidates.size() > 1) {
            return decision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.AMBIGUOUS,
                    complexCandidates.size(), now, "같은 지역·단지명·공급유형 카탈로그 단지가 여러 건");
        }

        HousingComplex complex = complexCandidates.getFirst();
        if (leaseInfo.getComplexTotalUnitCount() == null
                || !Objects.equals(leaseInfo.getComplexTotalUnitCount(), complex.getUnitCount())) {
            return decision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.CONFLICT_PROGRAM_UNIT_COUNT,
                    1, now, "SUM_HSH_CNT가 카탈로그 단지·공급유형 총세대수와 다름");
        }
        if (leaseInfo.getExclusiveArea() == null || leaseInfo.getTotalUnitCount() == null) {
            return decision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.UNMATCHED,
                    0, now, "DDO_AR 또는 HSH_CNT가 없음");
        }

        List<UnitType> unitTypeCandidates = unitTypeRepository.findByHousingComplex(complex).stream()
                .filter(unitType -> unitType.getExclusiveArea() != null
                        && unitType.getExclusiveArea().compareTo(leaseInfo.getExclusiveArea()) == 0)
                .toList();
        if (unitTypeCandidates.isEmpty()) {
            return decision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.UNMATCHED,
                    0, now, "DDO_AR가 정확히 같은 카탈로그 주택형이 없음");
        }
        if (unitTypeCandidates.size() > 1) {
            return decision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.AMBIGUOUS,
                    unitTypeCandidates.size(), now, "DDO_AR가 정확히 같은 카탈로그 주택형이 여러 건");
        }

        UnitType unitType = unitTypeCandidates.getFirst();
        return decision(leaseInfo, unitType, LhLeaseInfoUnitTypeMatchStatus.MATCHED,
                1, now, "지역·단지명·공급유형·SUM_HSH_CNT·DDO_AR 유일 일치");
    }

    private MatchDecision decision(LhLeaseInfo leaseInfo,
                                   UnitType unitType,
                                   LhLeaseInfoUnitTypeMatchStatus status,
                                   int candidateCount,
                                   LocalDateTime now,
                                   String reason) {
        return new MatchDecision(leaseInfo, unitType, status, candidateCount, now, reason);
    }

    private LhLeaseInfoUnitTypeMatch result(MatchDecision decision) {
        return new LhLeaseInfoUnitTypeMatch(decision.leaseInfo(), decision.unitType(), decision.status(),
                decision.candidateCount(), MATCHER_VERSION, decision.evaluatedAt(), decision.reason());
    }

    private boolean isCompleteSnapshot(List<JsonNode> pages) {
        if (pages == null || pages.isEmpty()) {
            return false;
        }

        boolean hasRows = false;
        for (JsonNode page : pages) {
            if (!containsDataset(page)) {
                return false;
            }
            hasRows |= !OpenApiClient.findRows(page, LIST_KEY).isEmpty();
        }
        return hasRows;
    }

    private boolean containsDataset(JsonNode page) {
        if (page == null || !page.isArray()) {
            return false;
        }
        for (JsonNode element : page) {
            if (element.has(LIST_KEY) && element.get(LIST_KEY).isArray()) {
                return true;
            }
        }
        return false;
    }

    private record MatchDecision(LhLeaseInfo leaseInfo,
                                 UnitType unitType,
                                 LhLeaseInfoUnitTypeMatchStatus status,
                                 int candidateCount,
                                 LocalDateTime evaluatedAt,
                                 String reason) {

        MatchDecision competingSourceRows(int sourceRowCount) {
            return new MatchDecision(leaseInfo, null, LhLeaseInfoUnitTypeMatchStatus.AMBIGUOUS, sourceRowCount,
                    evaluatedAt, "같은 카탈로그 주택형을 가리키는 15059475 원천행이 여러 건");
        }
    }

    private LocalDateTime sourceRespondedAt(List<JsonNode> pages) {
        for (int index = pages.size() - 1; index >= 0; index--) {
            List<JsonNode> headers = OpenApiClient.findRows(pages.get(index), "resHeader");
            if (headers.isEmpty()) {
                continue;
            }
            String raw = SourceValues.trimToNull(headers.getFirst().path("RS_DTTM").asString(""));
            if (raw == null) {
                continue;
            }
            try {
                return LocalDateTime.parse(raw, RESPONSE_TIMESTAMP);
            } catch (DateTimeParseException e) {
                log.warn("LH 임대주택단지 응답시각 변환 실패: raw={}", raw);
            }
        }
        return null;
    }

    /*
     * 15059475는 단지 ID를 주지 않는다. 그래서 키가 하나의 단지·공급유형, 하나의 정확한 전용면적으로
     * 좁혀진 원천행만 UnitType에 반영한다.
     */
    private record CatalogKey(String areaName, String complexLabel, String supplyTypeName) {

        static CatalogKey from(LhLeaseInfo leaseInfo) {
            return new CatalogKey(normalize(leaseInfo.getAreaName()), normalize(leaseInfo.getComplexLabel()),
                    normalize(leaseInfo.getSupplyTypeName()));
        }

        static CatalogKey from(HousingComplex complex) {
            Address address = complex.getAddress();
            String province = address == null ? null : address.getProvinceName();
            String district = address == null ? null : address.getDistrictName();
            String areaName = province == null ? null : province + (district == null ? "" : " " + district);
            CatalogKey key = new CatalogKey(normalize(areaName), normalize(complex.getName()),
                    normalize(complex.getSupplyTypeName()));
            return key.complete() ? key : null;
        }

        private boolean complete() {
            return areaName != null && complexLabel != null && supplyTypeName != null;
        }
    }

    private static String normalize(String value) {
        String trimmed = SourceValues.trimToNull(value);
        return trimmed == null ? null : trimmed.replaceAll("\\s+", "");
    }
}
