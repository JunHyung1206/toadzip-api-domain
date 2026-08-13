package test.domain.housing;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.source.SourceSystem;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ComplexRentalProgramPersistenceTest {

    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private ComplexRentalProgramRepository programRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private HousingProviderAgencyRepository agencyRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void storesUnitCountOnceOnProgramAndKeepsSameShapeSeparate() {
        HousingProviderAgency agency = agencyRepository.save(new HousingProviderAgency("SH", "SH공사"));
        HousingComplex complex = complexRepository.save(new HousingComplex(
                "중계센트럴파크", address(), agency, SourceSystem.MYHOME_PORTAL, "30582290"));
        ComplexRentalProgram national = programRepository.save(
                new ComplexRentalProgram(complex, "국민임대", SupplyType.NATIONAL_RENTAL, 115));
        ComplexRentalProgram jeonse = programRepository.save(
                new ComplexRentalProgram(complex, "장기전세", SupplyType.LONG_TERM_JEONSE, 114));

        unitTypeRepository.save(new UnitType(national, "49A", area("49.9000"), area("20.1000")));
        unitTypeRepository.save(new UnitType(jeonse, "49A", area("49.9000"), area("20.1000")));
        entityManager.flush();
        entityManager.clear();

        assertThat(programRepository.findByHousingComplexOrderBySupplyTypeName(complex))
                .extracting(ComplexRentalProgram::getUnitCount)
                .containsExactlyInAnyOrder(115, 114);
        assertThat(unitTypeRepository.findAll()).extracting(UnitType::getComplexRentalProgram)
                .extracting(ComplexRentalProgram::getSupplyType)
                .containsExactlyInAnyOrder(SupplyType.NATIONAL_RENTAL, SupplyType.LONG_TERM_JEONSE);
    }

    @Test
    void storesTheSourceTotalForOneUnitTypeSeparatelyFromTheProgramTotal() {
        HousingProviderAgency agency = agencyRepository.save(new HousingProviderAgency("LH", "한국토지주택공사"));
        HousingComplex complex = complexRepository.save(new HousingComplex(
                "강릉교동 행복주택", address(), agency, SourceSystem.MYHOME_PORTAL, "30582291"));
        ComplexRentalProgram program = programRepository.save(
                new ComplexRentalProgram(complex, "행복주택", SupplyType.HAPPY_HOUSE, 180));
        UnitType unitType = unitTypeRepository.save(
                new UnitType(program, "36", area("36.9700"), area("20.1000")));

        assertThat(unitType.updateTotalUnitCount(72)).isTrue();
        assertThat(unitType.updateTotalUnitCount(72)).isFalse();

        entityManager.flush();
        entityManager.clear();

        UnitType stored = unitTypeRepository.findById(unitType.getId()).orElseThrow();
        assertThat(stored.getTotalUnitCount()).isEqualTo(72);
        assertThat(stored.getComplexRentalProgram().getUnitCount()).isEqualTo(180);
    }

    private Address address() {
        return new Address("서울특별시 노원구 덕릉로70가길 21", "1135010500113220000",
                "11", "서울특별시", "350", "노원구");
    }

    private BigDecimal area(String value) {
        return new BigDecimal(value);
    }
}
