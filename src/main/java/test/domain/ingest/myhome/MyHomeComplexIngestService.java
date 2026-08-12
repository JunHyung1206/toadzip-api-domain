package test.domain.ingest.myhome;

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
import test.domain.housing.CatalogDetails;
import test.domain.housing.ComplexRentalProgram;
import test.domain.housing.ComplexRentalProgramRepository;
import test.domain.housing.HeatingType;
import test.domain.housing.HouseType;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.HousingProviderAgency;
import test.domain.housing.HousingProviderAgencyRepository;
import test.domain.housing.SupplyType;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.source.SourceSystem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 15110581 → HousingComplex + UnitType 적재.
 *
 * <p>원천 한 행이 단지 하나가 아니라 "단지 × 공급유형 × 주택형" 이다.
 * 그래서 hsmpSn 으로 행을 모아 단지 하나를 aggregate 로 검증·저장한다. 보고서의 숫자도 단지 기준이라
 * 같은 단지의 행이 여러 개 들어와도 created/versioned/unchanged 는 단지당 한 번만 잡힌다.
 */
@Slf4j
@Service
public class MyHomeComplexIngestService {

    private static final String PATH = "rentalHouseGwList";
    private static final String LIST_POINTER = "/response/body/item";
    private static final int REGION_CODE_LENGTH = 5;
    private static final int PROVINCE_CODE_LENGTH = 2;

    private final OpenApiClient myhomeApiClient;
    private final HousingComplexRepository complexRepository;
    private final ComplexRentalProgramRepository programRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final HousingProviderAgencyRepository agencyRepository;
    private final ConstructionRentalPolicy rentalPolicy;
    private final TransactionTemplate transactionTemplate;
    private final MyHomeRegionCatalog regionCatalog;

    public MyHomeComplexIngestService(@Qualifier("myhomeComplexApiClient") OpenApiClient myhomeApiClient,
                                      HousingComplexRepository complexRepository,
                                      ComplexRentalProgramRepository programRepository,
                                      UnitTypeRepository unitTypeRepository,
                                      HousingProviderAgencyRepository agencyRepository,
                                      ConstructionRentalPolicy rentalPolicy,
                                      PlatformTransactionManager transactionManager,
                                      MyHomeRegionCatalog regionCatalog) {
        this.myhomeApiClient = myhomeApiClient;
        this.complexRepository = complexRepository;
        this.programRepository = programRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.agencyRepository = agencyRepository;
        this.rentalPolicy = rentalPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.regionCatalog = regionCatalog;
    }

    /**
     * 원천이 광역시도·시군구 단위로만 조회를 열어 둬서 전국을 받으려면 시군구를 돌아야 한다.
     *
     * @param brtcCode   광역시도 코드 (예: 11 서울, 41 경기)
     * @param signguCode 시군구 코드 (예: 110 종로구)
     */
    public IngestReport ingest(String brtcCode, String signguCode, int pageSize, int maxPages) {
        IngestReport report = IngestReport.empty();
        for (int page = 1; page <= maxPages; page++) {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("brtcCode", brtcCode);
            params.add("signguCode", signguCode);
            params.add("numOfRows", String.valueOf(pageSize));
            params.add("pageNo", String.valueOf(page));

            List<MyHomeComplexItem> items =
                    myhomeApiClient.getList(PATH, params, LIST_POINTER, MyHomeComplexItem.class);
            log.info("마이홈 단지 {}-{} {}페이지 {}행", brtcCode, signguCode, page, items.size());
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
     * 여러 시군구를 한 번에 돈다.
     *
     * @param regionCodes 5자리 지역코드 목록. 앞 2자리가 brtcCode, 뒤 3자리가 signguCode 다
     */
    public IngestReport ingestRegions(Collection<String> regionCodes, int pageSize, int maxPages) {
        IngestReport report = IngestReport.empty();
        int done = 0;
        for (String regionCode : regionCodes) {
            if (regionCode == null || regionCode.length() != REGION_CODE_LENGTH) {
                log.warn("지역코드 형식이 아니라 건너뜁니다: {}", regionCode);
                continue;
            }
            report = report.plus(ingest(
                    regionCode.substring(0, PROVINCE_CODE_LENGTH),
                    regionCode.substring(PROVINCE_CODE_LENGTH),
                    pageSize, maxPages));
            log.info("지역 {}/{} 완료 ({}) — 누적 {}", ++done, regionCodes.size(), regionCode, report);
        }
        return report;
    }

    /**
     * {@link MyHomeRegionCatalog} 의 공식 256개 시군구를 전부 돈다.
     *
     * <p>지역 하나는 {@link #ingestRegion} 이 페이지를 전부 모은 뒤에야 저장하므로,
     * 한 지역의 부분 실패가 다른 지역에 영향을 주지 않는다.
     */
    public IngestReport ingestNationwide(int pageSize, int maxPages) {
        IngestReport report = IngestReport.empty();
        for (MyHomeRegion region : regionCatalog.all()) {
            report = report.plus(ingestRegion(region, pageSize, maxPages));
        }
        return report;
    }

    /**
     * 한 지역의 모든 페이지를 메모리에 모은 뒤에만 {@link #apply} 로 저장한다.
     *
     * <p>중간 페이지가 비거나(더 받을 게 없음) numOfRows 보다 짧으면(마지막 페이지) 그 시점까지 모은
     * 행을 저장한다. maxPages 안에 끝나지 않거나 HTTP 호출이 실패하면 그 지역은 아무것도 저장하지 않고
     * {@link IngestReport#oneFailed()} 로 남기며, 전국 순회는 다음 지역으로 계속된다.
     */
    IngestReport ingestRegion(MyHomeRegion region, int pageSize, int maxPages) {
        List<MyHomeComplexItem> completeRows = new ArrayList<>();
        try {
            for (int page = 1; page <= maxPages; page++) {
                List<MyHomeComplexItem> rows = fetchPage(region, page, pageSize);
                if (rows.isEmpty()) {
                    return apply(completeRows);
                }
                completeRows.addAll(rows);
                if (rows.size() < pageSize) {
                    return apply(completeRows);
                }
            }
            log.warn("단지 지역 조회가 maxPages 안에 끝나지 않음: region={}", region.fullCode());
            return IngestReport.oneFailed();
        } catch (RuntimeException exception) {
            log.warn("단지 지역 조회 실패: region={}", region.fullCode(), exception);
            return IngestReport.oneFailed();
        }
    }

    private List<MyHomeComplexItem> fetchPage(MyHomeRegion region, int page, int pageSize) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("brtcCode", region.brtcCode());
        params.add("signguCode", region.signguCode());
        params.add("numOfRows", String.valueOf(pageSize));
        params.add("pageNo", String.valueOf(page));
        return myhomeApiClient.getList(PATH, params, LIST_POINTER, MyHomeComplexItem.class);
    }

    /**
     * hsmpSn 단위로 검증하고 커밋한다. 같은 단지 안의 행들이 서로 모순되면(주소가 갈리거나, 한 공급유형
     * 안에서 세대수가 갈리면) 그 단지(또는 그 프로그램)만 통째로 제외한다. hsmpSn 하나가 다른 hsmpSn 의
     * 저장 실패에 영향받지 않도록 각각 REQUIRES_NEW 트랜잭션으로 커밋한다.
     */
    public IngestReport apply(List<MyHomeComplexItem> sourceRows) {
        IngestReport report = IngestReport.empty();
        Map<Long, List<MyHomeComplexItem>> accepted = new LinkedHashMap<>();
        for (MyHomeComplexItem row : sourceRows) {
            Optional<IngestRejectionReason> rejection = rentalPolicy.rejectSupplyType(row.suplyTyNm());
            if (rejection.isPresent()) {
                report = report.plus(IngestReport.oneRejected(rejection.orElseThrow()));
            } else if (row.hsmpSn() == null) {
                report = report.plus(IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY));
            } else {
                accepted.computeIfAbsent(row.hsmpSn(), key -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<Long, List<MyHomeComplexItem>> entry : accepted.entrySet()) {
            try {
                IngestReport applied = transactionTemplate.execute(
                        status -> applyComplex(entry.getKey(), entry.getValue()));
                report = report.plus(Objects.requireNonNull(applied));
            } catch (RuntimeException exception) {
                log.warn("마이홈 단지 aggregate 저장 실패: hsmpSn={}", entry.getKey(), exception);
                report = report.plus(IngestReport.oneFailed());
            }
        }
        return report;
    }

    /**
     * 한 hsmpSn 을 검증하고 저장한다.
     * <ol>
     *   <li>파싱 가능한 준공일 또는 아파트 행이 하나도 없으면 단지 전체를 NOT_CONSTRUCTION_HOUSING 으로 제외한다.</li>
     *   <li>비어 있지 않은 rnAdres distinct 값이 1개가 아니면 단지 전체를 MISSING_IDENTITY 또는 INVALID_SOURCE_ROW 로 제외한다.</li>
     *   <li>suplyTyNm 으로 프로그램을 묶고, 비어 있지 않은 hshldCo distinct 값이 둘 이상인 프로그램만 제외한다.</li>
     *   <li>유효 프로그램이 없으면 엔티티를 만들지 않는다.</li>
     * </ol>
     */
    private IngestReport applyComplex(Long hsmpSn, List<MyHomeComplexItem> rows) {
        boolean anyConstructionEvidence = rows.stream().anyMatch(row ->
                rentalPolicy.hasConstructionEvidence(row.houseTyNm(), row.competDe()));
        if (!anyConstructionEvidence) {
            log.debug("단지 전체 제외: hsmpSn={}, reason={}", hsmpSn, IngestRejectionReason.NOT_CONSTRUCTION_HOUSING);
            return IngestReport.oneRejected(IngestRejectionReason.NOT_CONSTRUCTION_HOUSING);
        }

        Set<String> addresses = rows.stream()
                .map(MyHomeComplexItem::rnAdres)
                .map(SourceValues::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (addresses.isEmpty()) {
            log.warn("단지 전체 제외: hsmpSn={}, reason={}", hsmpSn, IngestRejectionReason.MISSING_IDENTITY);
            return IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY);
        }
        if (addresses.size() > 1) {
            log.warn("단지 전체 제외: hsmpSn={}, 도로명주소가 {}개로 갈립니다.", hsmpSn, addresses.size());
            return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
        }
        String roadAddress = addresses.iterator().next();

        Map<String, List<MyHomeComplexItem>> bySupplyType = new LinkedHashMap<>();
        for (MyHomeComplexItem row : rows) {
            bySupplyType.computeIfAbsent(
                    SourceValues.trimToNull(row.suplyTyNm()), key -> new ArrayList<>()).add(row);
        }

        IngestReport rejectedPrograms = IngestReport.empty();
        Map<String, List<MyHomeComplexItem>> validPrograms = new LinkedHashMap<>();
        for (Map.Entry<String, List<MyHomeComplexItem>> entry : bySupplyType.entrySet()) {
            Set<Integer> unitCounts = entry.getValue().stream()
                    .map(MyHomeComplexItem::hshldCo)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (unitCounts.size() > 1) {
                log.warn("프로그램 제외: hsmpSn={}, supplyType={}, 세대수가 {}개로 갈립니다.",
                        hsmpSn, entry.getKey(), unitCounts.size());
                rejectedPrograms = rejectedPrograms.plus(
                        IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW));
                continue;
            }
            validPrograms.put(entry.getKey(), entry.getValue());
        }
        if (validPrograms.isEmpty()) {
            return rejectedPrograms;
        }

        MyHomeComplexItem head = rows.stream()
                .filter(row -> roadAddress.equals(SourceValues.trimToNull(row.rnAdres())))
                .findFirst()
                .orElseThrow();
        String sourceComplexId = String.valueOf(hsmpSn);
        HousingComplex existing = complexRepository
                .findBySourceSystemAndSourceComplexId(SourceSystem.MYHOME_PORTAL, sourceComplexId)
                .orElse(null);
        boolean isNew = existing == null;
        HousingComplex complex = isNew ? newComplex(head, sourceComplexId) : existing;

        boolean complexChanged = complex.updateCatalogDetails(new CatalogDetails(
                SourceValues.toDate(head.competDe()),
                HeatingType.from(head.heatMthdDetailNm()),
                SourceValues.trimToNull(head.heatMthdDetailNm()),
                head.parkngCo(),
                SourceValues.trimToNull(head.buldStleNm()),
                SourceValues.trimToNull(head.elvtrInstlAtNm()),
                HouseType.from(head.houseTyNm()),
                SourceValues.trimToNull(head.houseTyNm())));

        // 트랜잭션 경계가 save() 안에 있어서 더티체킹이 안 돈다. 그래서 바뀐 걸 직접 판단해 저장한다.
        if (isNew || complexChanged) {
            complexRepository.save(complex);
        }

        boolean anyProgramOrUnitTypeChanged = false;
        for (Map.Entry<String, List<MyHomeComplexItem>> entry : validPrograms.entrySet()) {
            anyProgramOrUnitTypeChanged |= upsertProgram(complex, entry.getKey(), entry.getValue());
        }

        IngestReport stored = isNew
                ? IngestReport.oneCreated()
                : (complexChanged || anyProgramOrUnitTypeChanged
                        ? IngestReport.oneVersioned()
                        : IngestReport.oneUnchanged());
        return stored.plus(rejectedPrograms);
    }

    private HousingComplex newComplex(MyHomeComplexItem item, String sourceComplexId) {
        Address address = new Address(
                item.rnAdres(),
                item.pnu(),
                SourceValues.trimToNull(item.brtcCode()),
                SourceValues.trimToNull(item.brtcNm()),
                SourceValues.trimToNull(item.signguCode()),
                SourceValues.trimToNull(item.signguNm()));
        return new HousingComplex(
                complexName(item),
                address,
                findOrCreateAgency(item.insttNm()),
                SourceSystem.MYHOME_PORTAL,
                sourceComplexId);
    }

    /**
     * 단지명이 공백으로만 오는 단지가 있다(강남구 hsmpSn 30582441, 6행). 이름은 필수라 채워야 하는데,
     * 매입임대는 원래 원천이 "경기도 수원시" 처럼 지역명을 단지명으로 쓴다. 그 관례를 그대로 따른다.
     */
    private String complexName(MyHomeComplexItem item) {
        String name = SourceValues.trimToNull(item.hsmpNm());
        if (name != null) {
            return name;
        }
        String region = joinRegion(item);
        log.warn("단지명이 비어 지역명으로 대체합니다: hsmpSn={}, {}", item.hsmpSn(), region);
        return region != null ? region : item.rnAdres().strip();
    }

    private String joinRegion(MyHomeComplexItem item) {
        String province = SourceValues.trimToNull(item.brtcNm());
        String district = SourceValues.trimToNull(item.signguNm());
        if (province == null) {
            return null;
        }
        return district == null ? province : province + " " + district;
    }

    /** @return 프로그램 또는 소속 주택형 중 하나라도 새로 만들었거나 값이 바뀌었으면 true */
    private boolean upsertProgram(HousingComplex complex, String supplyTypeName, List<MyHomeComplexItem> rows) {
        Integer unitCount = rows.stream()
                .map(MyHomeComplexItem::hshldCo)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        ComplexRentalProgram existingProgram = programRepository
                .findByHousingComplexAndSupplyTypeName(complex, supplyTypeName)
                .orElse(null);
        boolean programIsNew = existingProgram == null;
        ComplexRentalProgram program = programIsNew
                ? new ComplexRentalProgram(complex, supplyTypeName, SupplyType.from(supplyTypeName), unitCount)
                : existingProgram;
        boolean programChanged = !programIsNew && program.updateUnitCount(unitCount);
        if (programIsNew || programChanged) {
            programRepository.save(program);
        }

        boolean anyUnitTypeChanged = false;
        for (MyHomeComplexItem row : dedupeByNaturalKey(rows)) {
            anyUnitTypeChanged |= upsertUnitType(program, row);
        }
        return programIsNew || programChanged || anyUnitTypeChanged;
    }

    /**
     * 전용·공용면적이 nullable 이라 DB unique 제약이 같은 자연키의 중복 행을 걸러 주지 못한다
     * (NULL 은 서로 달라 unique 로 안 묶인다). 저장 전에 미리 한 번 더 중복을 제거한다.
     */
    private List<MyHomeComplexItem> dedupeByNaturalKey(List<MyHomeComplexItem> rows) {
        Map<List<Object>, MyHomeComplexItem> byNaturalKey = new LinkedHashMap<>();
        for (MyHomeComplexItem row : rows) {
            String typeName = SourceValues.trimToNull(row.styleNm());
            if (typeName == null) {
                continue;
            }
            byNaturalKey.put(List.of(typeName,
                    Optional.ofNullable((Object) row.suplyPrvuseAr()).orElse(""),
                    Optional.ofNullable((Object) row.suplyCmnuseAr()).orElse("")), row);
        }
        return new ArrayList<>(byNaturalKey.values());
    }

    private boolean upsertUnitType(ComplexRentalProgram program, MyHomeComplexItem row) {
        String typeName = SourceValues.trimToNull(row.styleNm());
        UnitType existing = unitTypeRepository
                .findByComplexRentalProgramAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
                        program, typeName, row.suplyPrvuseAr(), row.suplyCmnuseAr())
                .orElse(null);
        boolean isNew = existing == null;
        UnitType unitType = isNew
                ? new UnitType(program, typeName, row.suplyPrvuseAr(), row.suplyCmnuseAr())
                : existing;

        boolean changed = unitType.updateBaseRentTerms(
                new BaseRentTerms(row.bassRentGtn(), row.bassMtRntchrg(), row.bassCnvrsGtnLmt()));

        if (isNew || changed) {
            unitTypeRepository.save(unitType);
        }
        return isNew || changed;
    }

    /**
     * 원천은 기관 코드를 주지 않고 이름만 준다. 실데이터에 나온 값은
     * LH서울·LH경기남부·LH경기북부·LH대전충남·LH세종·LH인천·SH공사·대전도시공사·세종특별자치시·인천도시공사 로,
     * LH가 지역본부 단위로 쪼개져 있다. 코드체계가 확인되면 여기만 바꾸면 된다.
     */
    private HousingProviderAgency findOrCreateAgency(String name) {
        String agencyName = SourceValues.trimToNull(name);
        if (agencyName == null) {
            throw new IllegalStateException("공급기관명(insttNm)이 없습니다.");
        }
        return agencyRepository.findByCode(agencyName)
                .orElseGet(() -> agencyRepository.save(new HousingProviderAgency(agencyName, agencyName)));
    }
}
