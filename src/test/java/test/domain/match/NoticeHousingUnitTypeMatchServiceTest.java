package test.domain.match;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.housing.Address;
import test.domain.housing.ComplexRentalProgram;
import test.domain.housing.ComplexRentalProgramRepository;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.HousingProviderAgency;
import test.domain.housing.HousingProviderAgencyRepository;
import test.domain.housing.SupplyType;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.notice.LhComplexDetail;
import test.domain.notice.LhNoticeSupplement;
import test.domain.notice.LhNoticeSupplementRepository;
import test.domain.notice.LhUnitSupplyBatch;
import test.domain.notice.LhUnitSupplyBatchRepository;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNotice;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.notice.RentTerms;
import test.domain.notice.SuppliedHousing;
import test.domain.source.SourceSystem;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 체인 네 구간(LH단지명 → 주소 → PNU → 전용면적)이 각각 끊겼을 때를 모두 태운다.
 *
 * <p>단지명은 <b>일부러 카탈로그와 다르게</b> 지었다("울산구영1BL 국민임대" vs "구영주공1단지") —
 * 실데이터가 그렇고, 카탈로그 이름과 대조하는 구현이면 이 테스트가 깨진다.
 */
@DataJpaTest
class NoticeHousingUnitTypeMatchServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-13T04:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String LH_VERSION = "lh-address-unit-v1";
    private static final String CATALOG_VERSION = "catalog-pnu-v1";
    private static final String MATCHER_VERSION = "unit-type-area-v1";

    @Autowired private HousingProviderAgencyRepository agencyRepository;
    @Autowired private HousingComplexRepository complexRepository;
    @Autowired private ComplexRentalProgramRepository programRepository;
    @Autowired private UnitTypeRepository unitTypeRepository;
    @Autowired private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired private NoticeVersionRepository noticeVersionRepository;
    @Autowired private NoticeHousingRepository housingRepository;
    @Autowired private LhNoticeSupplementRepository supplementRepository;
    @Autowired private LhUnitSupplyBatchRepository batchRepository;
    @Autowired private NoticeHousingLhMatchRepository lhMatchRepository;
    @Autowired private NoticeHousingCatalogMatchRepository catalogMatchRepository;
    @Autowired private NoticeHousingUnitTypeMatchRepository repository;

    private NoticeVersion version;
    private NoticeHousingUnitTypeMatchService service;

    @BeforeEach
    void setUp() {
        HousingProviderAgency agency = agencyRepository.save(new HousingProviderAgency("LH", "한국토지주택공사"));

        // 카탈로그 단지명은 마이홈식("구영주공1단지"), LH는 사업지구명식("울산구영1BL 국민임대")이다.
        HousingComplex complex = complexRepository.save(new HousingComplex(
                "구영주공1단지", address("3171010200100010001"), agency, SourceSystem.MYHOME_PORTAL, "10001"));
        ComplexRentalProgram program = programRepository.save(
                new ComplexRentalProgram(complex, "국민임대", SupplyType.NATIONAL_RENTAL, 235));
        unitTypeRepository.save(new UnitType(program, "59", new BigDecimal("59.9400"), new BigDecimal("82.1224")));
        unitTypeRepository.save(new UnitType(program, "33A", new BigDecimal("33.7500"), new BigDecimal("46.0447")));
        unitTypeRepository.save(new UnitType(program, "33B", new BigDecimal("33.7700"), new BigDecimal("46.1000")));

        HousingComplex unconfirmed = complexRepository.save(new HousingComplex(
                "미확정단지", address("3171010200100010002"), agency, SourceSystem.MYHOME_PORTAL, "10002"));

        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "40001"));
        version = noticeVersionRepository.save(NoticeVersion.firstVersion(root, "40001", null, snapshot()));

        NoticeHousing confirmedHousing = housingRepository.save(new NoticeHousing(version, 0, 1, 20,
                suppliedHousing("3171010200100010001"), rentTerms(), null, null));
        NoticeHousing pnuUnconfirmedHousing = housingRepository.save(new NoticeHousing(version, 1, 2, 10,
                suppliedHousing("3171010200100010002"), rentTerms(), null, null));

        LhNoticeSupplement supplement = new LhNoticeSupplement(version, SourceSystem.LH_CHEONGYAK_PLUS,
                "2015122300018780", "03", "06", "07", "062",
                LocalDateTime.of(2026, 8, 13, 12, 11, 52), LocalDateTime.now(FIXED_CLOCK), true, null);
        supplement.addComplexDetail(0, "울산구영1BL 국민임대", "울산광역시 울주군 범서읍 구영리", "1",
                235, "지역난방", "33.75~59.94", null, null);
        supplement.addComplexDetail(1, "울산미확정 국민임대", "울산광역시 울주군 어딘가", "2",
                100, "지역난방", "40~50", null, null);
        supplement.addComplexDetail(2, "주소미매칭 단지", "울산광역시 어딘가", "3",
                50, "지역난방", "30~40", null, null);
        supplementRepository.save(supplement);
        List<LhComplexDetail> details = supplement.getComplexDetails();

        // 주소 구간: 0번·1번 단지상세만 공급행에 확정 연결. 2번은 연결 없음.
        lhMatchRepository.save(new NoticeHousingLhMatch(version, confirmedHousing, details.get(0),
                NoticeHousingLhMatchStatus.MATCHED_ADDRESS_AND_UNIT_COUNT, 1, 1, LH_VERSION,
                LocalDateTime.now(FIXED_CLOCK), null, 0));
        lhMatchRepository.save(new NoticeHousingLhMatch(version, pnuUnconfirmedHousing, details.get(1),
                NoticeHousingLhMatchStatus.MATCHED_ADDRESS_ONLY, 1, 1, LH_VERSION,
                LocalDateTime.now(FIXED_CLOCK), null, 1));

        // PNU 구간: 첫 공급행만 단지 확정. 두 번째는 후보 다수라 미확정.
        catalogMatchRepository.save(new NoticeHousingCatalogMatch(confirmedHousing, complex,
                NoticeHousingCatalogMatchStatus.MATCHED_PNU, "3171010200100010001", 1,
                CATALOG_VERSION, LocalDateTime.now(FIXED_CLOCK)));
        catalogMatchRepository.save(new NoticeHousingCatalogMatch(pnuUnconfirmedHousing, null,
                NoticeHousingCatalogMatchStatus.AMBIGUOUS, "3171010200100010002", 2,
                CATALOG_VERSION, LocalDateTime.now(FIXED_CLOCK)));
        assertThat(unconfirmed.getId()).isNotNull();

        LhUnitSupplyBatch batch = new LhUnitSupplyBatch(version, SourceSystem.LH_CHEONGYAK_PLUS,
                "2015122300018780", "03", "06", "07", "062",
                LocalDateTime.of(2026, 8, 13, 12, 11, 52), LocalDateTime.now(FIXED_CLOCK), true);
        batch.addUnitSupply(0, "울산구영1BL 국민임대", "59㎡", new BigDecimal("59.94"), new BigDecimal("82.1224"), 235, 20);
        batch.addUnitSupply(1, "울산구영1BL 국민임대", "33㎡", new BigDecimal("33.76"), new BigDecimal("46.05"), 132, 8);
        batch.addUnitSupply(2, "울산구영1BL 국민임대", "100㎡", new BigDecimal("100.00"), new BigDecimal("120.00"), 10, 2);
        batch.addUnitSupply(3, "울산미확정 국민임대", "45㎡", new BigDecimal("45.00"), new BigDecimal("60.00"), 100, 5);
        batch.addUnitSupply(4, "주소미매칭 단지", "35㎡", new BigDecimal("35.00"), new BigDecimal("45.00"), 50, 3);
        batch.addUnitSupply(5, "어느 상세에도 없는 단지", "20㎡", new BigDecimal("20.00"), new BigDecimal("25.00"), 30, 1);
        batchRepository.save(batch);

        service = new NoticeHousingUnitTypeMatchService(repository, noticeVersionRepository, batchRepository,
                supplementRepository, lhMatchRepository, catalogMatchRepository, programRepository,
                unitTypeRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("LH 이름끼리 이어 주택형을 확정하고, 끊긴 구간은 이유를 달아 남긴다")
    void walksTheChainAndRecordsWhereItBreaks() {
        service.match(version.getId(), LH_VERSION, CATALOG_VERSION, MATCHER_VERSION);

        List<NoticeHousingUnitTypeMatch> results = repository.findAll().stream()
                .sorted(Comparator.comparingInt(NoticeHousingUnitTypeMatch::getResultOrder))
                .toList();

        // 공급행 6개 → 결과 6줄. 못 맞춘 것도 버리지 않는다.
        assertThat(results).hasSize(6);
        assertThat(results).extracting(NoticeHousingUnitTypeMatch::getStatus).containsExactly(
                NoticeHousingUnitTypeMatchStatus.MATCHED,          // 59.94 → 유일
                NoticeHousingUnitTypeMatchStatus.AMBIGUOUS,        // 33.76 → 33A·33B 둘 다 오차 안
                NoticeHousingUnitTypeMatchStatus.UNMATCHED,        // 100.00 → 후보 없음
                NoticeHousingUnitTypeMatchStatus.NO_CATALOG_PATH,  // PNU 미확정
                NoticeHousingUnitTypeMatchStatus.NO_CATALOG_PATH,  // 주소 미매칭
                NoticeHousingUnitTypeMatchStatus.NO_CATALOG_PATH); // LH 단지상세에 이름 없음

        assertThat(results.get(0).getUnitType().getTypeName()).isEqualTo("59");
        assertThat(results.get(0).getSuppliedUnitCount()).isEqualTo(20);
        assertThat(results.get(0).getNoticeHousing()).isNotNull();
        assertThat(results.get(1).getCandidateCount()).isEqualTo(2);
        assertThat(results.get(1).getUnitType()).isNull();

        assertThat(results.get(3).getReason()).contains("PNU");
        assertThat(results.get(4).getReason()).contains("주소");
        assertThat(results.get(5).getReason()).contains("단지명");
        assertThat(results.get(5).getNoticeHousing()).isNull();

        // 원문은 매칭 성공 여부와 무관하게 전부 남는다.
        assertThat(results).allSatisfy(row -> {
            assertThat(row.getLhUnitSupply()).isNotNull();
            assertThat(row.getSourceComplexLabel()).isNotBlank();
            assertThat(row.getSourceExclusiveArea()).isNotNull();
        });
    }

    @Test
    @DisplayName("공급정보 배치가 없으면 아무 결과도 만들지 않는다")
    void writesNothingWithoutSourceBatch() {
        batchRepository.deleteAll();

        service.match(version.getId(), LH_VERSION, CATALOG_VERSION, MATCHER_VERSION);

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("같은 matcherVersion을 다시 돌리면 그 버전의 기존 결과만 지우고 다시 만든다")
    void replacesOnlyTheSameMatcherVersion() {
        service.match(version.getId(), LH_VERSION, CATALOG_VERSION, MATCHER_VERSION);
        int first = repository.findAll().size();

        service.match(version.getId(), LH_VERSION, CATALOG_VERSION, "unit-type-area-v2");
        service.match(version.getId(), LH_VERSION, CATALOG_VERSION, MATCHER_VERSION);

        assertThat(repository.findAll()).hasSize(first * 2);
    }

    private Address address(String pnu) {
        return new Address("울산광역시 울주군 범서읍 구영리 1", pnu, "31", "울산광역시", "710", "울주군");
    }

    private NoticeSnapshot snapshot() {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                "국민임대 입주자 모집", "https://www.myhome.go.kr/detail",
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "국민임대", "LH 콜센터 : 1600-1004");
    }

    private SuppliedHousing suppliedHousing(String pnu) {
        return new SuppliedHousing("구영주공1단지", "울산광역시 울주군 범서읍 구영리", pnu,
                "구영로", null, "울산광역시", "울주군", "지역난방", 235);
    }

    private RentTerms rentTerms() {
        return new RentTerms(19_546_000L, 977_000L, 18_569_000L, 195_460L);
    }
}
