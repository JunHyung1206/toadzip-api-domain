package test.domain.housing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ComplexRentalProgramRepository extends JpaRepository<ComplexRentalProgram, Long> {

    List<ComplexRentalProgram> findByHousingComplexOrderBySupplyTypeName(HousingComplex housingComplex);

    Optional<ComplexRentalProgram> findByHousingComplexAndSupplyTypeName(
            HousingComplex housingComplex, String supplyTypeName);
}
