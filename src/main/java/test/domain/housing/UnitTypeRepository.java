package test.domain.housing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.Optional;

public interface UnitTypeRepository extends JpaRepository<UnitType, Long> {

    /** 자연키 조회. 프로그램과 면적이 빠지면 서로 다른 행이 하나로 뭉개진다. */
    Optional<UnitType> findByComplexRentalProgramAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
            ComplexRentalProgram complexRentalProgram,
            String typeName,
            BigDecimal exclusiveArea,
            BigDecimal residentialCommonArea);
}
