package test.domain.housing;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.source.SourceSystem;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class HousingComplexSupplyTypePersistenceTest {

    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void storesSameSourceComplexAsIndependentRowsPerSupplyType() {
        HousingComplex fifty = complex("123", SupplyType.FIFTY_YEAR_RENTAL, "50년임대", 100, "LH");
        HousingComplex happy = complex("123", SupplyType.HAPPY_HOUSE, "행복주택", 80, "LH");

        complexRepository.saveAll(java.util.List.of(fifty, happy));
        entityManager.flush();
        entityManager.clear();

        assertThat(complexRepository.findAll()).extracting(HousingComplex::getSupplyType)
                .containsExactlyInAnyOrder(SupplyType.FIFTY_YEAR_RENTAL, SupplyType.HAPPY_HOUSE);
        assertThat(complexRepository.findAll()).extracting(HousingComplex::getUnitCount)
                .containsExactlyInAnyOrder(100, 80);
        assertThat(complexRepository.findAll()).extracting(HousingComplex::getSupplyInstitutionName)
                .containsOnly("LH");
    }

    @Test
    void storesSameUnitTypeShapeSeparatelyUnderEachSupplyTypeComplex() {
        HousingComplex fifty = complex("123", SupplyType.FIFTY_YEAR_RENTAL, "50년임대", 100, "LH");
        HousingComplex happy = complex("123", SupplyType.HAPPY_HOUSE, "행복주택", 80, "LH");
        complexRepository.saveAll(java.util.List.of(fifty, happy));

        unitTypeRepository.save(new UnitType(fifty, "49A", area("49.9000"), area("20.1000")));
        unitTypeRepository.save(new UnitType(happy, "49A", area("49.9000"), area("20.1000")));
        entityManager.flush();
        entityManager.clear();

        assertThat(unitTypeRepository.findByHousingComplex(
                complexRepository.findBySourceSystemAndSourceComplexIdAndSupplyType(
                                SourceSystem.MYHOME_PORTAL, "123", SupplyType.FIFTY_YEAR_RENTAL)
                        .orElseThrow()))
                .hasSize(1)
                .allSatisfy(unitType -> assertThat(unitType.getHousingComplex().getSupplyType())
                        .isEqualTo(SupplyType.FIFTY_YEAR_RENTAL));
        assertThat(unitTypeRepository.findByHousingComplex(
                complexRepository.findBySourceSystemAndSourceComplexIdAndSupplyType(
                                SourceSystem.MYHOME_PORTAL, "123", SupplyType.HAPPY_HOUSE)
                        .orElseThrow()))
                .hasSize(1)
                .allSatisfy(unitType -> assertThat(unitType.getHousingComplex().getSupplyType())
                        .isEqualTo(SupplyType.HAPPY_HOUSE));
    }

    @Test
    void storesTheSourceTotalForOneUnitTypeSeparatelyFromComplexTotal() {
        HousingComplex complex = complex("124", SupplyType.HAPPY_HOUSE, "행복주택", 180, "LH");
        complexRepository.save(complex);
        UnitType unitType = unitTypeRepository.save(
                new UnitType(complex, "36", area("36.9700"), area("20.1000")));

        assertThat(unitType.updateTotalUnitCount(72)).isTrue();
        assertThat(unitType.updateTotalUnitCount(72)).isFalse();
        entityManager.flush();
        entityManager.clear();

        UnitType stored = unitTypeRepository.findById(unitType.getId()).orElseThrow();
        assertThat(stored.getTotalUnitCount()).isEqualTo(72);
        assertThat(stored.getHousingComplex().getUnitCount()).isEqualTo(180);
    }

    private HousingComplex complex(String sourceId,
                                   SupplyType supplyType,
                                   String supplyTypeName,
                                   int unitCount,
                                   String institutionName) {
        return new HousingComplex("중계센트럴파크", address(), SourceSystem.MYHOME_PORTAL, sourceId,
                supplyType, supplyTypeName, unitCount, institutionName);
    }

    private Address address() {
        return new Address("서울특별시 노원구 덕릉로70가길 21", "1135010500113220000",
                "11", "서울특별시", "350", "노원구");
    }

    private BigDecimal area(String value) {
        return new BigDecimal(value);
    }
}
