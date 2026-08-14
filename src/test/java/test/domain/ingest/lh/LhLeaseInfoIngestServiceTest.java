package test.domain.ingest.lh;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.housing.Address;
import test.domain.housing.BaseRentTerms;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-13에 15059475에서 직접 읽은 응답 구조를 카탈로그에 반영한다. HTTP 호출은 제외한다.
 *
 * <p>원천행을 저장하지 않으므로, 검증 대상은 "무엇이 {@link UnitType} 에 반영됐고 무엇이 안 됐나"다.
 */
@DataJpaTest
class LhLeaseInfoIngestServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired private HousingComplexRepository complexRepository;
    @Autowired private UnitTypeRepository unitTypeRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    private UnitType matched36;
    private UnitType matched26;
    private UnitType ambiguousA;
    private UnitType ambiguousB;
    private UnitType nearArea;
    private LhLeaseInfoIngestService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            unitTypeRepository.deleteAll();
            complexRepository.deleteAll();
        });

        committed.executeWithoutResult(status -> {
            HousingComplex complex = complexRepository.save(new HousingComplex(
                    "강릉교동 행복주택", address("강원특별자치도", "강릉시"), "10001", "행복주택", 180, "LH"));
            matched36 = unitTypeRepository.save(new UnitType(complex, "36", area("36.9700"), area("20.1000")));
            matched36.updateBaseRentTerms(new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L));
            matched26 = unitTypeRepository.save(new UnitType(complex, "26", area("26.9500"), area("15.0000")));
            ambiguousA = unitTypeRepository.save(new UnitType(complex, "21A", area("21.8400"), area("12.0000")));
            ambiguousB = unitTypeRepository.save(new UnitType(complex, "21B", area("21.8600"), area("12.1000")));

            HousingComplex nearAreaComplex = complexRepository.save(new HousingComplex(
                    "강릉교동 근접 행복주택", address("강원특별자치도", "강릉시"), "10002", "행복주택", 70, "LH"));
            nearArea = unitTypeRepository.save(
                    new UnitType(nearAreaComplex, "36", area("36.9200"), area("20.1000")));
        });

        service = new LhLeaseInfoIngestService(
                null, MAPPER, complexRepository, unitTypeRepository, transactionManager);
    }

    private UnitType reload(UnitType unitType) {
        return unitTypeRepository.findById(unitType.getId()).orElseThrow();
    }

    @Test
    @DisplayName("유일 매칭된 주택형에만 HSH_CNT·LS_GMY·RFE를 반영한다")
    void updatesOnlyUniquelyMatchedUnitTypes() {
        service.apply(List.of(MAPPER.readTree(RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(matched36).getTotalUnitCount()).isEqualTo(72);
        assertThat(reload(matched26).getTotalUnitCount()).isEqualTo(36);
        assertThat(reload(matched36).getBaseRentTerms().getDeposit()).isEqualTo(19_546_000L);
        assertThat(reload(matched36).getBaseRentTerms().getMonthlyRent()).isEqualTo(195_460L);
        // 전환보증금 한도는 15059475가 주지 않으므로 마이홈 값을 유지한다.
        assertThat(reload(matched36).getBaseRentTerms().getConvertibleDepositLimit()).isEqualTo(3_000_000L);
        // 21.85㎡는 21A(21.84)·21B(21.86) 어느 쪽과도 정확히 같지 않아 아무것도 안 붙는다.
        assertThat(reload(ambiguousA).getTotalUnitCount()).isNull();
        assertThat(reload(ambiguousB).getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("SUM_HSH_CNT가 카탈로그 단지 세대수와 다르면 반영하지 않는다")
    void ignoresRowsWhoseProgramUnitCountConflicts() {
        service.apply(List.of(MAPPER.readTree(CONFLICTING_PROGRAM_COUNT_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(matched36).getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("새 전국 응답은 더 이상 확인되지 않는 주택형의 총세대수를 비운다")
    void clearsStaleUnitTypeCountsOnTheNextSnapshot() {
        service.apply(List.of(MAPPER.readTree(RESPONSE)));
        service.apply(List.of(MAPPER.readTree(UPDATED_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(matched36).getTotalUnitCount()).isEqualTo(70);
        assertThat(reload(matched26).getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("전용면적은 스케일을 제외하고 정확히 같을 때만 매칭한다")
    void matchesOnlyExactlyEqualExclusiveAreas() {
        service.apply(List.of(MAPPER.readTree(NEAR_AREA_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(nearArea).getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("복수 원천행이 같은 주택형을 가리키면 마지막 값이 앞 값을 덮어쓰지 못하게 아무것도 반영하지 않는다")
    void doesNotUpdateAUnitTypeWhenMultipleSourceRowsTargetIt() {
        service.apply(List.of(MAPPER.readTree(DUPLICATE_TARGET_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(matched36).getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("빈 dsList 또는 누락된 dsList는 이미 반영된 값을 지우지 않는다")
    void preservesAppliedValuesWhenTheDatasetIsMissingOrEmpty() {
        service.apply(List.of(MAPPER.readTree(RESPONSE)));

        assertThat(service.apply(List.of(MAPPER.readTree(MISSING_LIST_RESPONSE))).failed()).isEqualTo(1);
        assertThat(service.apply(List.of(MAPPER.readTree(EMPTY_LIST_RESPONSE))).failed()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(reload(matched36).getTotalUnitCount()).isEqualTo(72);
    }

    private Address address(String province, String district) {
        return new Address(province + " " + district + " 가상로 1", "4215010100100010001",
                "42", province, "150", district);
    }

    private BigDecimal area(String value) {
        return new BigDecimal(value);
    }

    private static final String RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"dsList":[
               {"SUM_HSH_CNT":"180","HSH_CNT":"72","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"36.97",
                "LS_GMY":"19546000","RFE":"195460"},
               {"SUM_HSH_CNT":"180","HSH_CNT":"36","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"26.95"},
               {"SUM_HSH_CNT":"180","HSH_CNT":"72","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"21.85"}],
              "resHeader":[{"RS_DTTM":"20260813042736","SS_CODE":"Y"}]}]
            """;

    private static final String CONFLICTING_PROGRAM_COUNT_RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"dsList":[
               {"SUM_HSH_CNT":"181","HSH_CNT":"70","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"36.97"}],
              "resHeader":[{"RS_DTTM":"20260813042736","SS_CODE":"Y"}]}]
            """;

    private static final String UPDATED_RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"dsList":[
               {"SUM_HSH_CNT":"180","HSH_CNT":"70","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"36.97"}],
              "resHeader":[{"RS_DTTM":"20260814042736","SS_CODE":"Y"}]}]
            """;

    private static final String NEAR_AREA_RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"dsList":[
               {"SUM_HSH_CNT":"70","HSH_CNT":"70","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 근접 행복주택","DDO_AR":"36.97"}],
              "resHeader":[{"RS_DTTM":"20260813042736","SS_CODE":"Y"}]}]
            """;

    private static final String DUPLICATE_TARGET_RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"dsList":[
               {"SUM_HSH_CNT":"180","HSH_CNT":"72","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"36.97"},
               {"SUM_HSH_CNT":"180","HSH_CNT":"70","ARA_NM":"강원특별자치도 강릉시",
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"36.97"}],
              "resHeader":[{"RS_DTTM":"20260813042736","SS_CODE":"Y"}]}]
            """;

    private static final String MISSING_LIST_RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"resHeader":[{"RS_DTTM":"20260813042736","SS_CODE":"Y"}]}]
            """;

    private static final String EMPTY_LIST_RESPONSE = """
            [{"dsSch":[{"PG_SZ":"5","PAGE":"1"}]},
             {"dsList":[],"resHeader":[{"RS_DTTM":"20260813042736","SS_CODE":"Y"}]}]
            """;
}
