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
import test.domain.housing.BaseRentTerms;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.IngestReport;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 15059475 전국 LH 임대단지 주택형 카탈로그를 읽어 확인된 값만 {@link UnitType} 에 바로 반영한다.
 *
 * <p><b>원천행을 저장하지 않는다.</b> 예전에는 {@code lh_lease_info_batch} + {@code lh_lease_info}
 * (6,710행) + 매칭 결과 테이블에 스냅샷을 남겼는데, 이 원천은 공고와 무관한 전국 카탈로그고
 * {@code PG_SZ=9999} 한 번이면 전부 받는다. 재적재 비용이 거의 없어서 원천행을 남길 값이
 * 재호출 비용보다 크지 않았다.
 *
 * <p><b>대신 엄격하게 확인된 행만 쓴다.</b> 원천에는 단지 ID 도 PNU 도 주소도 없다. 그래서
 * 지역·단지명·공급유형·{@code SUM_HSH_CNT} 가 카탈로그 단지 하나로 좁혀지고 {@code DDO_AR} 가
 * {@link UnitType#getExclusiveArea()} 와 <b>정확히</b> 하나 일치할 때만 반영한다.
 * 36.97과 36.9700 처럼 소수 자릿수만 다른 값은 같게 보지만 ±0.05㎡ 근사는 쓰지 않는다.
 * 같은 주택형을 여러 원천행이 가리키면 마지막 값이 앞 값을 덮어쓰지 못하도록 아무것도 반영하지 않는다.
 */
@Slf4j
@Service
public class LhLeaseInfoIngestService {

    private static final String PATH = "lhLeaseInfo1/lhLeaseInfo1";
    private static final String LIST_KEY = "dsList";

    private final OpenApiClient lhApiClient;
    private final ObjectMapper objectMapper;
    private final HousingComplexRepository complexRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final TransactionTemplate transactionTemplate;

    public LhLeaseInfoIngestService(@Qualifier("lhApiClient") OpenApiClient lhApiClient,
                                    ObjectMapper objectMapper,
                                    HousingComplexRepository complexRepository,
                                    UnitTypeRepository unitTypeRepository,
                                    PlatformTransactionManager transactionManager) {
        this.lhApiClient = lhApiClient;
        this.objectMapper = objectMapper;
        this.complexRepository = complexRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 필터 없이 전 페이지를 읽어 카탈로그를 갱신한다. */
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

    /**
     * HTTP 없이 이미 받은 페이지 응답을 반영한다. 부분 응답으로 카탈로그를 훼손하지 않도록,
     * 전 페이지에 {@code dsList} 키가 있고 행이 하나라도 있을 때만 반영한다.
     */
    IngestReport apply(List<JsonNode> pages) {
        if (!isCompleteSnapshot(pages)) {
            log.warn("LH 임대주택단지 응답에 유효한 전국 dsList 스냅샷이 없습니다.");
            return IngestReport.oneFailed();
        }
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> applyRows(readRows(pages))));
        } catch (RuntimeException e) {
            log.warn("LH 임대주택단지 카탈로그 반영에 실패했습니다.", e);
            return IngestReport.oneFailed();
        }
    }

    private IngestReport applyRows(List<LeaseInfoRow> rows) {
        // 이번 스냅샷이 말하지 않는 주택형은 값을 비운다. 지난 응답의 세대수가 남아 있으면 안 된다.
        unitTypeRepository.findAll().forEach(unitType -> unitType.updateTotalUnitCount(null));

        Map<CatalogKey, List<HousingComplex>> complexes = complexesByKey();
        Map<Long, List<LeaseInfoRow>> rowsByUnitType = new LinkedHashMap<>();
        for (LeaseInfoRow row : rows) {
            UnitType unitType = resolve(row, complexes);
            if (unitType != null) {
                rowsByUnitType.computeIfAbsent(unitType.getId(), key -> new ArrayList<>()).add(row);
            }
        }

        int applied = 0;
        int ambiguous = 0;
        for (Map.Entry<Long, List<LeaseInfoRow>> entry : rowsByUnitType.entrySet()) {
            if (entry.getValue().size() > 1) {
                ambiguous++;
                continue;
            }
            UnitType unitType = unitTypeRepository.findById(entry.getKey()).orElseThrow();
            LeaseInfoRow row = entry.getValue().get(0);
            unitType.updateTotalUnitCount(row.totalUnitCount());
            updateBaseRentTerms(unitType, row);
            applied++;
        }
        log.info("15059475 반영: 원천 {}행 중 주택형 {}건 갱신, 원천행 중복으로 보류 {}건",
                rows.size(), applied, ambiguous);
        return IngestReport.oneVersioned();
    }

    /** 보증금·월세만 덮어쓴다. 전환보증금 한도는 15059475가 주지 않아 마이홈 값을 유지한다. */
    private void updateBaseRentTerms(UnitType unitType, LeaseInfoRow row) {
        if (row.deposit() == null && row.monthlyRent() == null) {
            return;
        }
        BaseRentTerms current = unitType.getBaseRentTerms();
        Long deposit = row.deposit() != null
                ? row.deposit() : current == null ? null : current.getDeposit();
        Long monthlyRent = row.monthlyRent() != null
                ? row.monthlyRent() : current == null ? null : current.getMonthlyRent();
        Long convertibleDepositLimit = current == null ? null : current.getConvertibleDepositLimit();
        unitType.updateBaseRentTerms(new BaseRentTerms(deposit, monthlyRent, convertibleDepositLimit));
    }

    /**
     * 한 원천행이 가리키는 카탈로그 주택형. 지역·단지명·공급유형이 단지 하나로 좁혀지고,
     * {@code SUM_HSH_CNT} 가 그 단지 세대수와 같고, {@code DDO_AR} 가 주택형 하나와 정확히 같아야 한다.
     */
    private UnitType resolve(LeaseInfoRow row, Map<CatalogKey, List<HousingComplex>> complexes) {
        List<HousingComplex> candidates = complexes.getOrDefault(CatalogKey.from(row), List.of());
        if (candidates.size() != 1) {
            return null;
        }
        HousingComplex complex = candidates.getFirst();
        if (row.complexTotalUnitCount() == null
                || !Objects.equals(row.complexTotalUnitCount(), complex.getUnitCount())) {
            return null;
        }
        if (row.exclusiveArea() == null || row.totalUnitCount() == null) {
            return null;
        }
        List<UnitType> unitTypes = unitTypeRepository.findByHousingComplex(complex).stream()
                .filter(unitType -> unitType.getExclusiveArea() != null
                        && unitType.getExclusiveArea().compareTo(row.exclusiveArea()) == 0)
                .toList();
        return unitTypes.size() == 1 ? unitTypes.getFirst() : null;
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

    private List<LeaseInfoRow> readRows(List<JsonNode> pages) {
        List<LeaseInfoRow> rows = new ArrayList<>();
        for (JsonNode page : pages) {
            for (JsonNode row : OpenApiClient.findRows(page, LIST_KEY)) {
                LhLeaseInfoItem item = objectMapper.convertValue(row, LhLeaseInfoItem.class);
                rows.add(new LeaseInfoRow(
                        SourceValues.trimToNull(item.areaName()),
                        SourceValues.trimToNull(item.complexLabel()),
                        SourceValues.trimToNull(item.supplyTypeName()),
                        SourceValues.toInt(item.complexTotalUnitCount()),
                        SourceValues.toDecimal(item.exclusiveArea()),
                        SourceValues.toInt(item.totalUnitCount()),
                        SourceValues.toLong(item.deposit()),
                        SourceValues.toLong(item.monthlyRent())));
            }
        }
        return rows;
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

    /** 15059475 원천행. 저장하지 않고 이 적재 안에서만 산다. */
    private record LeaseInfoRow(String areaName,
                                String complexLabel,
                                String supplyTypeName,
                                Integer complexTotalUnitCount,
                                BigDecimal exclusiveArea,
                                Integer totalUnitCount,
                                Long deposit,
                                Long monthlyRent) {
    }

    /** 15059475는 단지 ID를 주지 않는다. 이름·지역·공급유형 셋이 다 있어야 후보로 삼는다. */
    private record CatalogKey(String areaName, String complexLabel, String supplyTypeName) {

        static CatalogKey from(LeaseInfoRow row) {
            return new CatalogKey(normalize(row.areaName()), normalize(row.complexLabel()),
                    normalize(row.supplyTypeName()));
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
