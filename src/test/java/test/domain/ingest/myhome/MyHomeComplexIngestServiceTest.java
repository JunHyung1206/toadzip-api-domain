package test.domain.ingest.myhome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
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

/**
 * 15110581 실제 응답 모양 그대로 태운다. HTTP만 빠져 있다.
 *
 * <p>단지 저장이 REQUIRES_NEW 로 커밋되어 {@code @DataJpaTest} 기본 롤백을 우회하므로,
 * 테스트 사이에 남는 데이터를 매번 별도 트랜잭션으로 직접 비운다.
 */
@DataJpaTest
class MyHomeComplexIngestServiceTest {

    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private MyHomeComplexIngestService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.executeWithoutResult(status -> {
            unitTypeRepository.deleteAll();
            complexRepository.deleteAll();
        });
        service = new MyHomeComplexIngestService(
                null, complexRepository, unitTypeRepository,
                new ConstructionRentalPolicy(), transactionManager, new MyHomeRegionCatalog());
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

        // 10년임대 두 행은 라벨이 허용 대상이라 건설 흔적이 없는 두 번째 경계에서 걸리고,
        // 장기전세 행은 라벨 단계에서 먼저 걸린다.
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.NOT_CONSTRUCTION_HOUSING, 2)
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 1);
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
        assertThat(complex.getHouseTypeName()).isEqualTo("다세대주택");
        assertThat(complex.getCompletionDate()).isEqualTo(LocalDate.of(2020, 12, 30));
        assertThat(unitTypeRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 행이 단지가 아니라 단지×공급유형×주택형이라 5행이 단지 1개 + 주택형 3개가 되고 장기전세 2행은 걸러진다")
    void splitsRowsIntoComplexAndUnitTypes() {
        IngestReport report = service.apply(MyHomeFixtures.constructedComplexItems());

        assertThat(report.created()).isEqualTo(1);
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 2);
        assertThat(complexRepository.count()).isEqualTo(1);
        assertThat(unitTypeRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("건설임대 주택형은 49A·49B 형태라 호실 번호가 섞이지 않는다")
    void keepsApartmentStyleNames() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        assertThat(unitTypeRepository.findAll()).extracting(UnitType::getTypeName)
                .containsExactlyInAnyOrder("49A", "49A-S", "49B");
    }

    @Test
    @DisplayName("주택형명도 면적도 같은데 공급유형만 다르면 별개 주택형이다")
    void keepsSameUnitApartBySupplyType() {
        service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        List<UnitType> sameName = unitTypeRepository.findAll().stream()
                .filter(unitType -> unitType.getTypeName().equals("51A"))
                .toList();

        assertThat(sameName).hasSize(2);
        assertThat(sameName).extracting(UnitType::getExclusiveArea)
                .allSatisfy(area -> assertThat(area).isEqualByComparingTo(new BigDecimal("51.7377")));
        assertThat(sameName).extracting(unitType -> unitType.getHousingComplex().getSupplyTypeName())
                .containsExactlyInAnyOrder("국민임대", "행복주택");
        assertThat(sameName).extracting(unitType -> unitType.getBaseRentTerms().getDeposit())
                .containsExactlyInAnyOrder(125_070_000L, 196_800_000L);
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
        assertThat(complex.getHeatingTypeName()).isEqualTo("지역난방");
        assertThat(complex.getCorridorType()).isEqualTo("혼합식");
        assertThat(complex.getElevatorInstallation()).isEqualTo("전체동 설치");
        assertThat(complex.getParkingSpaces()).isEqualTo(472);
        assertThat(complex.getHouseTypeName()).isEqualTo("아파트");
        assertThat(complex.getSupplyInstitutionName()).isEqualTo("SH공사");
    }

    @Test
    @DisplayName("공급유형별 세대수는 각 단지 행에 따로 남는다")
    void keepsUnitCountPerSupplyTypeComplex() {
        service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        // 국민임대 10 / 행복주택 8. 물리 단지 전체 세대수(18)로 합쳐지지 않는다.
        assertThat(complexRepository.findAll())
                .extracting(HousingComplex::getUnitCount)
                .containsExactlyInAnyOrder(10, 8);
    }

    @Test
    @DisplayName("같은 hsmpSn이라도 공급유형이 다르면 별도 단지 행이라, 자연키가 한글 공급유형명이다")
    void keepsSupplyTypeNameAsPartOfTheNaturalKey() {
        service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        assertThat(complexRepository.findAll()).extracting(HousingComplex::getSupplyTypeName)
                .containsExactlyInAnyOrder("국민임대", "행복주택");
        assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName("31696890", "국민임대"))
                .isPresent();
        assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName("31696890", "매입임대"))
                .isEmpty();
    }

    @Test
    @DisplayName("같은 응답을 다시 읽으면 아무것도 쓰지 않고 단지 하나를 unchanged 로 보고한다")
    void isIdempotent() {
        service.apply(MyHomeFixtures.constructedComplexItems());

        IngestReport second = service.apply(MyHomeFixtures.constructedComplexItems());

        assertThat(second).isEqualTo(new IngestReport(0, 0, 1, 0,
                Map.of(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 2)));
        assertThat(complexRepository.count()).isEqualTo(1);
        assertThat(unitTypeRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("공급유형별로 세대수가 갈리는 단지도 다시 읽으면 단지 하나를 unchanged 로 보고한다")
    void doesNotChurnOnSplitUnitCounts() {
        service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        IngestReport second = service.apply(MyHomeFixtures.complexItemsWithTwoSupplyTypes());

        assertThat(second).isEqualTo(new IngestReport(0, 0, 2, 0, Map.of()));
    }

    @Test
    @DisplayName("공급유형이 갈리는 행은 그룹핑 전에 걸러져 나머지 단지만 된다")
    void filtersUnsupportedRowsBeforeCreatingComplexes() {
        IngestReport report = service.apply(MyHomeFixtures.itemsForOneComplex("국민임대", "매입임대"));

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 1);
        assertThat(complexRepository.findAll()).extracting(HousingComplex::getSupplyTypeName)
                .containsExactly("국민임대");
    }

    @Test
    @DisplayName("허용 행 중 하나라도 건설 흔적이 있으면 단지 전체를 담는다")
    void acceptsWholeComplexWhenAnyAllowedRowHasConstructionEvidence() {
        IngestReport report = service.apply(MyHomeFixtures.rowsWithApartmentEvidenceOnlyOnSecondRow());

        assertThat(report.created()).isOne();
        assertThat(complexRepository.count()).isOne();
        assertThat(unitTypeRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 단지 안에서 도로명주소가 갈리면 단지 전체를 제외한다")
    void rejectsWholeComplexWhenNonBlankAddressesConflict() {
        IngestReport report = service.apply(MyHomeFixtures.rowsWithConflictingRoadAddresses());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(complexRepository.count()).isZero();
    }

    @Test
    @DisplayName("세대수가 갈리는 공급유형만 제외하고 나머지 단지는 담는다")
    void rejectsOnlySupplyTypeWhoseNonNullUnitCountsConflict() {
        IngestReport report = service.apply(MyHomeFixtures.rowsWithOneConflictingAndOneValidProgram());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(complexRepository.findAll()).extracting(HousingComplex::getSupplyTypeName)
                .containsExactly("행복주택");
    }
}
