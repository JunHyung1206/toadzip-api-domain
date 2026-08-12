package test.domain.ingest.myhome;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import test.domain.housing.Address;
import test.domain.housing.BaseRentTerms;
import test.domain.housing.CatalogDetails;
import test.domain.housing.HeatingType;
import test.domain.housing.HouseType;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.HousingProviderAgency;
import test.domain.housing.HousingProviderAgencyRepository;
import test.domain.housing.SupplyType;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.IngestReport;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;

import java.util.Collection;
import java.util.List;

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
    private final UnitTypeRepository unitTypeRepository;
    private final HousingProviderAgencyRepository agencyRepository;

    public MyHomeComplexIngestService(@Qualifier("myhomeComplexApiClient") OpenApiClient myhomeApiClient,
                                      HousingComplexRepository complexRepository,
                                      UnitTypeRepository unitTypeRepository,
                                      HousingProviderAgencyRepository agencyRepository) {
        this.myhomeApiClient = myhomeApiClient;
        this.complexRepository = complexRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.agencyRepository = agencyRepository;
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
            return new IngestReport(0, 0, 0, 1);
        }
        // 건설임대만 담는다. 매입임대·전세임대는 지어진 단지가 아니라서 이 카탈로그의 전제와 안 맞는다.
        if (SupplyType.isPurchasedOrJeonse(item.suplyTyNm()) || looksPurchased(item)) {
            return new IngestReport(0, 0, 0, 1);
        }

        String sourceComplexId = String.valueOf(item.hsmpSn());
        HousingComplex existing = complexRepository.findBySourceComplexId(sourceComplexId).orElse(null);
        boolean isNew = existing == null;

        HousingComplex complex = isNew ? newComplex(item, sourceComplexId) : existing;

        boolean complexChanged = complex.updateCatalogDetails(new CatalogDetails(
                item.hshldCo(),
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
            return new IngestReport(1, 0, 0, 0);
        }
        return complexChanged || unitTypeChanged
                ? new IngestReport(0, 1, 0, 0)
                : new IngestReport(0, 0, 1, 0);
    }

    /**
     * 공급유형은 건설임대인데 실제로는 매입임대인 행을 걸러낸다.
     *
     * <p>{@code suplyTyNm} 한 칸에 서로 다른 두 가지가 들어온다 — '매입임대'는 <b>어떻게 확보했나</b>이고
     * '10년임대'·'장기전세'는 <b>어떤 조건으로 빌려주나</b>다. 사들인 집을 10년임대로 공급하면 둘 다 참이라
     * 어느 쪽을 적을지가 기관마다 갈린다. 대구 노블힐즈4(hsmpSn 1058)는 같은 응답에 '장기전세' 5행과
     * '매입임대' 1행이 같이 오고, 성남시 40단지는 hsmpNm 이 "매입임대주택"인데 suplyTyNm 은 '10년임대'다.
     *
     * <p>그래서 라벨 대신 지어진 흔적으로 가른다. <b>아파트가 아니면서 준공일도 없으면</b> 지어진 단지로 볼
     * 근거가 없다. 매입임대 라벨 28,773행 중 준공일이 있는 건 2행(0.007%)뿐이라 이 반대 방향은 거의 비어 있고,
     * 준공일이 있는 비아파트 건설임대(만부마을 행복주택, 평택이충 통합공공임대주택 등)는 그대로 남는다.
     */
    private boolean looksPurchased(MyHomeComplexItem item) {
        return HouseType.from(item.houseTyNm()) != HouseType.APARTMENT
                && SourceValues.trimToNull(item.competDe()) == null;
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

    /** @return 주택형을 새로 만들었거나 값이 바뀌었으면 true */
    private boolean upsertUnitType(HousingComplex complex, MyHomeComplexItem item) {
        String typeName = SourceValues.trimToNull(item.styleNm());
        String supplyTypeName = SourceValues.trimToNull(item.suplyTyNm());
        if (typeName == null || supplyTypeName == null) {
            return false;
        }

        UnitType existing = unitTypeRepository
                .findByComplexAndSupplyTypeNameAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
                        complex, supplyTypeName, typeName, item.suplyPrvuseAr(), item.suplyCmnuseAr())
                .orElse(null);
        boolean isNew = existing == null;
        UnitType unitType = isNew
                ? new UnitType(complex, supplyTypeName, typeName, item.suplyPrvuseAr(), item.suplyCmnuseAr())
                : existing;

        boolean changed = unitType.updateSupplyDetails(
                item.hshldCo(),
                new BaseRentTerms(item.bassRentGtn(), item.bassMtRntchrg(), item.bassCnvrsGtnLmt()));

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
