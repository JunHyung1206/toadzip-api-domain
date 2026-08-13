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
import test.domain.housing.LhLeaseInfoBatch;
import test.domain.housing.LhLeaseInfoBatchRepository;
import test.domain.housing.SupplyType;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.match.LhLeaseInfoUnitTypeMatch;
import test.domain.match.LhLeaseInfoUnitTypeMatchRepository;
import test.domain.match.LhLeaseInfoUnitTypeMatchStatus;
import test.domain.source.SourceSystem;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-08-13에 15059475에서 직접 읽은 응답 구조를 저장·매칭한다. HTTP 호출은 제외한다. */
@DataJpaTest
class LhLeaseInfoIngestServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-13T04:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Autowired private HousingComplexRepository complexRepository;
    @Autowired private UnitTypeRepository unitTypeRepository;
    @Autowired private LhLeaseInfoBatchRepository batchRepository;
    @Autowired private LhLeaseInfoUnitTypeMatchRepository matchRepository;
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
            matchRepository.deleteAll();
            batchRepository.deleteAll();
            unitTypeRepository.deleteAll();
            complexRepository.deleteAll();
        });

        committed.executeWithoutResult(status -> {
            HousingComplex complex = complexRepository.save(new HousingComplex(
                    "강릉교동 행복주택", address("강원특별자치도", "강릉시"),
                    SourceSystem.MYHOME_PORTAL, "10001", SupplyType.HAPPY_HOUSE, "행복주택", 180, "LH"));
            matched36 = unitTypeRepository.save(new UnitType(complex, "36", area("36.9700"), area("20.1000")));
            matched36.updateBaseRentTerms(new BaseRentTerms(10_000_000L, 100_000L, 3_000_000L));
            matched26 = unitTypeRepository.save(new UnitType(complex, "26", area("26.9500"), area("15.0000")));
            ambiguousA = unitTypeRepository.save(new UnitType(complex, "21A", area("21.8400"), area("12.0000")));
            ambiguousB = unitTypeRepository.save(new UnitType(complex, "21B", area("21.8600"), area("12.1000")));

            HousingComplex nearAreaComplex = complexRepository.save(new HousingComplex(
                    "강릉교동 근접 행복주택", address("강원특별자치도", "강릉시"),
                    SourceSystem.MYHOME_PORTAL, "10002", SupplyType.HAPPY_HOUSE, "행복주택", 70, "LH"));
            nearArea = unitTypeRepository.save(
                    new UnitType(nearAreaComplex, "36", area("36.9200"), area("20.1000")));
        });

        service = new LhLeaseInfoIngestService(null, MAPPER, batchRepository, matchRepository,
                complexRepository, unitTypeRepository, transactionManager, FIXED_CLOCK);
    }

    @Test
    @DisplayName("15059475의 면적별 HSH_CNT를 원천행으로 남기고 유일 매칭 주택형에만 반영한다")
    void storesSourceRowsAndUpdatesOnlyUniquelyMatchedUnitTypes() {
        service.apply(List.of(MAPPER.readTree(RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        LhLeaseInfoBatch batch = batchRepository.findAll().getFirst();
        List<LhLeaseInfoUnitTypeMatch> matches = matchRepository.findAll().stream()
                .sorted(Comparator.comparingInt(match -> match.getLhLeaseInfo().getDisplayOrder()))
                .toList();

        assertThat(batch.getLeaseInfos()).hasSize(4);
        assertThat(batch.getLeaseInfos().get(0).getComplexTotalUnitCount()).isEqualTo(180);
        assertThat(batch.getLeaseInfos().get(0).getTotalUnitCount()).isEqualTo(72);
        assertThat(batch.getLeaseInfos().get(0).getDeposit()).isEqualTo(19_546_000L);
        assertThat(batch.getLeaseInfos().get(0).getMonthlyRent()).isEqualTo(195_460L);
        assertThat(matches).extracting(LhLeaseInfoUnitTypeMatch::getStatus).containsExactly(
                LhLeaseInfoUnitTypeMatchStatus.MATCHED,
                LhLeaseInfoUnitTypeMatchStatus.MATCHED,
                LhLeaseInfoUnitTypeMatchStatus.UNMATCHED,
                LhLeaseInfoUnitTypeMatchStatus.CONFLICT_PROGRAM_UNIT_COUNT);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getTotalUnitCount()).isEqualTo(72);
        assertThat(unitTypeRepository.findById(matched26.getId()).orElseThrow().getTotalUnitCount()).isEqualTo(36);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getBaseRentTerms().getDeposit())
                .isEqualTo(19_546_000L);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getBaseRentTerms().getMonthlyRent())
                .isEqualTo(195_460L);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getBaseRentTerms()
                .getConvertibleDepositLimit()).isEqualTo(3_000_000L);
        assertThat(unitTypeRepository.findById(ambiguousA.getId()).orElseThrow().getTotalUnitCount()).isNull();
        assertThat(unitTypeRepository.findById(ambiguousB.getId()).orElseThrow().getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("새 전국 스냅샷은 이전 원천행과 더 이상 확인되지 않는 주택형 총세대수를 함께 교체한다")
    void replacesThePreviousSnapshotAndClearsStaleUnitTypeCounts() {
        service.apply(List.of(MAPPER.readTree(RESPONSE)));
        service.apply(List.of(MAPPER.readTree(UPDATED_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        assertThat(batchRepository.count()).isOne();
        assertThat(batchRepository.findAll().getFirst().getLeaseInfos()).hasSize(1);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getTotalUnitCount()).isEqualTo(70);
        assertThat(unitTypeRepository.findById(matched26.getId()).orElseThrow().getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("전용면적은 스케일을 제외하고 정확히 같을 때만 매칭한다")
    void matchesOnlyExactlyEqualExclusiveAreas() {
        service.apply(List.of(MAPPER.readTree(NEAR_AREA_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        LhLeaseInfoUnitTypeMatch match = matchRepository.findAll().getFirst();
        assertThat(match.getStatus()).isEqualTo(LhLeaseInfoUnitTypeMatchStatus.UNMATCHED);
        assertThat(match.getUnitType()).isNull();
        assertThat(unitTypeRepository.findById(nearArea.getId()).orElseThrow().getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("복수 원천행이 같은 주택형을 가리키면 모두 보류하고 총세대수를 갱신하지 않는다")
    void doesNotUpdateAUnitTypeWhenMultipleSourceRowsTargetIt() {
        service.apply(List.of(MAPPER.readTree(DUPLICATE_TARGET_RESPONSE)));
        entityManager.flush();
        entityManager.clear();

        List<LhLeaseInfoUnitTypeMatch> matches = matchRepository.findAll();
        assertThat(matches).extracting(LhLeaseInfoUnitTypeMatch::getStatus)
                .containsOnly(LhLeaseInfoUnitTypeMatchStatus.AMBIGUOUS);
        assertThat(matches).extracting(LhLeaseInfoUnitTypeMatch::getCandidateCount).containsOnly(2);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getTotalUnitCount()).isNull();
    }

    @Test
    @DisplayName("빈 dsList 또는 누락된 dsList는 기존 전국 스냅샷을 지우지 않는다")
    void preservesPreviousSnapshotWhenTheDatasetIsMissingOrEmpty() {
        service.apply(List.of(MAPPER.readTree(RESPONSE)));

        assertThat(service.apply(List.of(MAPPER.readTree(MISSING_LIST_RESPONSE))).failed()).isEqualTo(1);
        assertThat(service.apply(List.of(MAPPER.readTree(EMPTY_LIST_RESPONSE))).failed()).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(batchRepository.count()).isOne();
        assertThat(batchRepository.findAll().getFirst().getLeaseInfos()).hasSize(4);
        assertThat(matchRepository.count()).isEqualTo(4);
        assertThat(unitTypeRepository.findById(matched36.getId()).orElseThrow().getTotalUnitCount()).isEqualTo(72);
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
                "AIS_TP_CD_NM":"행복주택","SBD_LGO_NM":"강릉교동 행복주택","DDO_AR":"21.85"},
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
