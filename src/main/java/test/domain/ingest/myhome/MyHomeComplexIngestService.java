package test.domain.ingest.myhome;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
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

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 15110581 → HousingComplex + UnitType 적재.
 *
 * <p>원천 한 행이 단지 하나가 아니라 "단지 × 공급유형 × 주택형" 이다.
 * 그래서 행마다 단지를 upsert 한 뒤 주택형을 붙인다. 보고서의 숫자는 단지 기준이라
 * 같은 단지의 두 번째 주택형부터는 versioned(갱신)로 잡힌다.
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

    public MyHomeComplexIngestService(@Qualifier("myhomeComplexApiClient") OpenApiClient myhomeApiClient,
                                      HousingComplexRepository complexRepository,
                                      ComplexRentalProgramRepository programRepository,
                                      UnitTypeRepository unitTypeRepository,
                                      HousingProviderAgencyRepository agencyRepository,
                                      ConstructionRentalPolicy rentalPolicy) {
        this.myhomeApiClient = myhomeApiClient;
        this.complexRepository = complexRepository;
        this.programRepository = programRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.agencyRepository = agencyRepository;
        this.rentalPolicy = rentalPolicy;
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
     * 행 단위로 커밋된다. 같은 단지를 다시 읽어도 결과가 같으므로 중간에 실패하면 다시 돌리면 되고,
     * 그래서 배치 전체를 한 트랜잭션으로 묶지 않는다(묶으면 HTTP 를 도는 내내 커넥션을 잡고 있어야 한다).
     */
    public IngestReport apply(List<MyHomeComplexItem> items) {
        IngestReport report = IngestReport.empty();
        for (MyHomeComplexItem item : items) {
            report = report.plus(applyOne(item));
        }
        return report;
    }

    private IngestReport applyOne(MyHomeComplexItem item) {
        if (item.hsmpSn() == null || SourceValues.trimToNull(item.rnAdres()) == null) {
            log.warn("단지 식별자나 주소가 없어 건너뜁니다: hsmpSn={}, {}", item.hsmpSn(), item.hsmpNm());
            return IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY);
        }
        Optional<IngestRejectionReason> rejection = rentalPolicy.rejectComplex(
                item.suplyTyNm(), item.houseTyNm(), item.competDe());
        if (rejection.isPresent()) {
            log.debug("단지 원천 행 제외: hsmpSn={}, supplyType={}, reason={}",
                    item.hsmpSn(), item.suplyTyNm(), rejection.get());
            return IngestReport.oneRejected(rejection.get());
        }

        String sourceComplexId = String.valueOf(item.hsmpSn());
        HousingComplex existing = complexRepository
                .findBySourceSystemAndSourceComplexId(SourceSystem.MYHOME_PORTAL, sourceComplexId)
                .orElse(null);
        boolean isNew = existing == null;

        HousingComplex complex = isNew ? newComplex(item, sourceComplexId) : existing;

        boolean complexChanged = complex.updateCatalogDetails(new CatalogDetails(
                SourceValues.toDate(item.competDe()),
                HeatingType.from(item.heatMthdDetailNm()),
                SourceValues.trimToNull(item.heatMthdDetailNm()),
                item.parkngCo(),
                SourceValues.trimToNull(item.buldStleNm()),
                SourceValues.trimToNull(item.elvtrInstlAtNm()),
                HouseType.from(item.houseTyNm()),
                SourceValues.trimToNull(item.houseTyNm())));

        // 트랜잭션 경계가 save() 안에 있어서 더티체킹이 안 돈다. 그래서 바뀐 걸 직접 판단해 저장한다.
        if (isNew || complexChanged) {
            complexRepository.save(complex);
        }
        boolean unitTypeChanged = upsertUnitType(complex, item);

        if (isNew) {
            return IngestReport.oneCreated();
        }
        return complexChanged || unitTypeChanged
                ? IngestReport.oneVersioned()
                : IngestReport.oneUnchanged();
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

    /** @return 프로그램 또는 주택형을 새로 만들었거나 값이 바뀌었으면 true */
    private boolean upsertUnitType(HousingComplex complex, MyHomeComplexItem item) {
        String typeName = SourceValues.trimToNull(item.styleNm());
        String supplyTypeName = SourceValues.trimToNull(item.suplyTyNm());
        if (typeName == null || supplyTypeName == null) {
            return false;
        }

        boolean programChanged = false;
        ComplexRentalProgram existingProgram = programRepository
                .findByHousingComplexAndSupplyTypeName(complex, supplyTypeName)
                .orElse(null);
        boolean programIsNew = existingProgram == null;
        ComplexRentalProgram program = programIsNew
                ? new ComplexRentalProgram(complex, supplyTypeName, SupplyType.from(supplyTypeName), item.hshldCo())
                : existingProgram;
        if (!programIsNew) {
            programChanged = program.updateUnitCount(item.hshldCo());
        }
        if (programIsNew || programChanged) {
            programRepository.save(program);
        }

        UnitType existing = unitTypeRepository
                .findByComplexRentalProgramAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
                        program, typeName, item.suplyPrvuseAr(), item.suplyCmnuseAr())
                .orElse(null);
        boolean isNew = existing == null;
        UnitType unitType = isNew
                ? new UnitType(program, typeName, item.suplyPrvuseAr(), item.suplyCmnuseAr())
                : existing;

        boolean changed = unitType.updateBaseRentTerms(
                new BaseRentTerms(item.bassRentGtn(), item.bassMtRntchrg(), item.bassCnvrsGtnLmt()));

        if (isNew || changed) {
            unitTypeRepository.save(unitType);
        }
        return programIsNew || programChanged || isNew || changed;
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
