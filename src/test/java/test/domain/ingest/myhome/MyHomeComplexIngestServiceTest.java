package test.domain.ingest.myhome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.housing.HeatingType;
import test.domain.housing.HouseType;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.HousingProviderAgencyRepository;
import test.domain.housing.SupplyType;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.ConstructionRentalPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 15110581 실제 응답 모양 그대로 태운다. HTTP만 빠져 있다. */
@DataJpaTest
class MyHomeComplexIngestServiceTest {

    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private HousingProviderAgencyRepository agencyRepository;

    private MyHomeComplexIngestService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeComplexIngestService(
                null, complexRepository, unitTypeRepository, agencyRepository, new ConstructionRentalPolicy());
    }

    @Test
    @DisplayName("매입임대·전세임대는 적재하지 않는다")
    void skipsPurchasedAndJeonseRental() {
        IngestReport report = service.apply(MyHomeFixtures.purchasedComplexItems());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 3);
        assertThat(complexRepository.count()).isZero();
        assertThat(unitTypeRepository.count()).isZero();
    }

    @Test
    @DisplayName("공급유형이 건설임대여도 아파트가 아니고 준공일이 없으면 매입임대로 보고 걸러낸다")
    void skipsPurchasedRentalWearingConstructedLabel() {
        IngestReport report = service.apply(MyHomeFixtures.purchasedItemsUnderConstructedLabel());

        // 라벨은 허용 대상이지만 건설 흔적이 없어 두 번째 경계에서 걸린다.
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.NOT_CONSTRUCTION_HOUSING, 3);
        assertThat(complexRepository.count()).isZero();
        assertThat(unitTypeRepository.count()).isZero();
    }

    @Test
    @DisplayName("처음 보는 공급유형은 아파트여도 자동 적재하지 않는다")
    void rejectsUnknownSupplyType() {
        IngestReport report = service.apply(MyHomeFixtures.unknownSupplyTypeComplexItems());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE, 1);
        assertThat(complexRepository.count()).isZero();
        assertThat(unitTypeRepository.count()).isZero();
    }

    @Test
    @DisplayName("아파트가 아니어도 준공일이 있으면 지어진 단지라 담는다")
    void keepsNonApartmentWithCompletionDate() {
        IngestReport report = service.apply(MyHomeFixtures.constructedMultiplexItems());

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.rejected()).isZero();
        HousingComplex complex = complexRepository.findAll().get(0);
        assertThat(complex.getName()).isEqualTo("만부마을 행복주택");
        assertThat(complex.getHouseType()).isEqualTo(HouseType.MULTIPLEX_HOUSE);
        assertThat(complex.getCompletionDate()).isEqualTo(LocalDate.of(2020, 12, 30));
        assertThat(unitTypeRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 행이 단지가 아니라 단지×공급유형×주택형이라 5행이 단지 1개 + 주택형 5개가 된다")
    void splitsRowsIntoComplexAndUnitTypes() {
        IngestReport report = service.apply(MyHomeFixtures.constructedComplexItems());

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.rejected()).isZero();
        assertThat(complexRepository.count()).isEqualTo(1);
        assertThat(unitTypeRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("건설임대 주택형은 49A·49B 형태라 호실 번호가 섞이지 않는다")
    void keepsApartmentStyleNames() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        assertThat(unitTypeRepository.findAll()).extracting(UnitType::getTypeName)
                .containsExactlyInAnyOrder("49A", "49A", "49A-2", "49A-S", "49B");
    }

    @Test
    @DisplayName("주택형명도 면적도 같은데 공급유형만 다르면 별개 주택형이다")
    void keepsSameUnitApartBySupplyType() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        List<UnitType> sameName = unitTypeRepository.findAll().stream()
                .filter(unitType -> unitType.getTypeName().equals("49A"))
                .toList();

        assertThat(sameName).hasSize(2);
        assertThat(sameName).extracting(UnitType::getExclusiveArea)
                .allSatisfy(area -> assertThat(area).isEqualByComparingTo(new BigDecimal("49.90")));
        assertThat(sameName).extracting(UnitType::getSupplyType)
                .containsExactlyInAnyOrder(SupplyType.NATIONAL_RENTAL, SupplyType.LONG_TERM_JEONSE);
        // 장기전세는 보증금만 있고 월세가 0이다.
        assertThat(sameName).extracting(unitType -> unitType.getBaseRentTerms().getDeposit())
                .containsExactlyInAnyOrder(63_850_000L, 311_220_000L);
    }

    @Test
    @DisplayName("법정동코드는 PNU 앞 10자리에서 나오고 지역코드도 함께 남는다")
    void derivesLegalDongCodeFromPnu() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        HousingComplex complex = complexRepository.findAll().get(0);
        assertThat(complex.getName()).isEqualTo("중계센트럴파크");
        assertThat(complex.getAddress().getPnu()).isEqualTo("1135010500113220000");
        assertThat(complex.getAddress().getLegalDongCode()).isEqualTo("1135010500");
        assertThat(complex.getAddress().getRoadAddress()).isEqualTo("서울특별시 노원구 덕릉로70가길 21");
        assertThat(complex.getAddress().getProvinceCode()).isEqualTo("11");
        assertThat(complex.getAddress().getDistrictName()).isEqualTo("노원구");
    }

    @Test
    @DisplayName("건설임대는 준공일·난방·복도·승강기·주차가 다 채워진다")
    void fillsBuildingDetails() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        HousingComplex complex = complexRepository.findAll().get(0);
        assertThat(complex.getCompletionDate()).isEqualTo(LocalDate.of(2016, 1, 17));
        assertThat(complex.getCompletionYear()).isEqualTo(2016);
        assertThat(complex.getHeatingType()).isEqualTo(HeatingType.DISTRICT);
        assertThat(complex.getHeatingTypeName()).isEqualTo("지역난방");
        assertThat(complex.getCorridorType()).isEqualTo("혼합식");
        assertThat(complex.getElevatorInstallation()).isEqualTo("전체동 설치");
        assertThat(complex.getParkingSpaces()).isEqualTo(472);
        assertThat(complex.getHouseType()).isEqualTo(HouseType.APARTMENT);
        assertThat(complex.getHousingProviderAgency().getName()).isEqualTo("SH공사");
    }

    @Test
    @DisplayName("단지 세대수 칸에는 공급유형별 값 중 큰 쪽만 남는다")
    void keepsLargestSupplyTypeUnitCount() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        // 국민임대 115 / 장기전세 114 → 큰 쪽. 단지 전체 세대수(229)가 아니라는 걸 이름이 드러낸다.
        assertThat(complexRepository.findAll().get(0).getMaxSupplyTypeUnitCount()).isEqualTo(115);
        assertThat(unitTypeRepository.findAll()).extracting(UnitType::getSupplyTypeUnitCount)
                .containsOnly(115, 114);
    }

    @Test
    @DisplayName("난방유형은 열원까지 붙은 값도 읽는다")
    void mapsHeatingLabelsWithFuelSuffix() {
        assertThat(HeatingType.from("지역폐열난방")).isEqualTo(HeatingType.DISTRICT);
        assertThat(HeatingType.from("개별가스난방")).isEqualTo(HeatingType.INDIVIDUAL);
        assertThat(HeatingType.from("중앙난방")).isEqualTo(HeatingType.CENTRAL);
        assertThat(HeatingType.from("")).isNull();
    }

    @Test
    @DisplayName("모르는 공급유형·주택유형이 와도 적재는 죽지 않고 원문이 남는다")
    void keepsRawLabelWhenEnumDoesNotMatch() {
        assertThat(SupplyType.from("청년안심주택")).isNull();
        assertThat(HouseType.from("생활숙박시설")).isNull();
        assertThat(SupplyType.from("행복주택")).isEqualTo(SupplyType.HAPPY_HOUSE);
        assertThat(HouseType.from("다세대주택")).isEqualTo(HouseType.MULTIPLEX_HOUSE);
        assertThat(SupplyType.NATIONAL_RENTAL.isConstructionRental()).isTrue();
        assertThat(SupplyType.PURCHASED_RENTAL.isConstructionRental()).isFalse();
        assertThat(SupplyType.JEONSE_RENTAL.isConstructionRental()).isFalse();
    }

    @Test
    @DisplayName("같은 응답을 다시 읽으면 아무것도 쓰지 않고 unchanged 로 보고한다")
    void isIdempotent() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        IngestReport second = service.apply(MyHomeFixtures.constructedComplexItems());

        assertThat(second).isEqualTo(new IngestReport(0, 0, 5, 0, Map.of()));
        assertThat(complexRepository.count()).isEqualTo(1);
        assertThat(unitTypeRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("공급유형별로 세대수가 갈리는 단지도 다시 읽으면 unchanged 다")
    void doesNotChurnOnSplitUnitCounts() {
        service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        IngestReport second = service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        assertThat(second).isEqualTo(new IngestReport(0, 0, 2, 0, Map.of()));
    }
}
