package test.domain.housing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface UnitTypeRepository extends JpaRepository<UnitType, Long> {

    /** 자연키 조회. 소속 단지·공급유형과 면적이 빠지면 서로 다른 행이 하나로 뭉개진다. */
    Optional<UnitType> findByHousingComplexAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
            HousingComplex housingComplex,
            String typeName,
            BigDecimal exclusiveArea,
            BigDecimal residentialCommonArea);

    /** 한 단지·공급유형의 주택형 후보 전체. 면적 대조는 호출 쪽에서 한다(허용 오차가 쿼리로 표현하기 애매해서). */
    List<UnitType> findByHousingComplex(HousingComplex housingComplex);
}
