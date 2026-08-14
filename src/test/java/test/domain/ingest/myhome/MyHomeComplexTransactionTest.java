package test.domain.ingest.myhome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.IngestReport;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MyHomeComplexTransactionTest {

    private static final String FAILING_HSMP_SN = "40001";
    private static final String SAVED_HSMP_SN = "40002";

    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("한 단지의 저장 실패가 다른 단지의 저장을 막지도, 되돌리지도 않는다")
    void oneComplexFailureDoesNotRollBackOrBlockAnother() {
        UnitTypeRepository failingOnFirstComplex = mock(UnitTypeRepository.class);
        when(failingOnFirstComplex
                .findByHousingComplexAndTypeNameAndExclusiveAreaAndResidentialCommonArea(
                        any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(failingOnFirstComplex.save(argThat(unitType -> unitType != null
                && FAILING_HSMP_SN.equals(unitType.getHousingComplex().getSourceComplexId()))))
                .thenThrow(new IllegalStateException("주택형 저장 실패"));
        when(failingOnFirstComplex.save(argThat(unitType -> unitType != null
                && SAVED_HSMP_SN.equals(unitType.getHousingComplex().getSourceComplexId()))))
                .thenAnswer(invocation -> unitTypeRepository.save(invocation.getArgument(0)));

        MyHomeComplexIngestService service = new MyHomeComplexIngestService(
                null, complexRepository, failingOnFirstComplex,
                new ConstructionRentalPolicy(), transactionManager, new MyHomeRegionCatalog());

        IngestReport report = service.apply(MyHomeFixtures.itemsForTwoComplexesOneFailing());

        assertThat(report.failed()).isOne();
        assertThat(report.created()).isOne();
        assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName(FAILING_HSMP_SN, "국민임대"))
                .isEmpty();
        assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName(SAVED_HSMP_SN, "국민임대"))
                .isPresent();
    }
}
