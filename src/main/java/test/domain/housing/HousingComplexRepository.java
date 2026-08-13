package test.domain.housing;

import org.springframework.data.jpa.repository.JpaRepository;
import test.domain.source.SourceSystem;

import java.util.List;
import java.util.Optional;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {

    Optional<HousingComplex> findBySourceSystemAndSourceComplexIdAndSupplyType(
            SourceSystem sourceSystem, String sourceComplexId, SupplyType supplyType);

    /**
     * 공고행을 단지에 붙일 때 쓰는 키. 단지명은 매입임대에서 지역명이라 못 쓴다.
     *
     * <p><b>여러 건이 나올 수 있다.</b> 매입임대는 같은 건물(필지)에서 호를 따로 사들이면 각각
     * 다른 hsmpSn 으로 등록된다. 실제로 단지 11,236개가 고유 PNU 6,851개에 몰려 있고,
     * 한 PNU 에 단지가 113개까지 달린 경우도 있다. 그래서 Optional 이 아니라 List 다.
     */
    List<HousingComplex> findAllByAddressPnuAndSupplyType(String pnu, SupplyType supplyType);
}
