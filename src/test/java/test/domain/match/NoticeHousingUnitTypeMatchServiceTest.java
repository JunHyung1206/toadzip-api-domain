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

@DataJpaTest
class NoticeHousingUnitTypeMatchServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-13T04:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String CATALOG_VERSION = "catalog-pnu-v1";
    private static final String MATCHER_VERSION = "unit-type-area-v1";

    @Autowired
    private HousingProviderAgencyRepository agencyRepository;
    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private ComplexRentalProgramRepository programRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeHousingRepository housingRepository;
    @Autowired
    private NoticeHousingCatalogMatchRepository catalogMatchRepository;
    @Autowired
    private LhUnitSupplyBatchRepository unitSupplyBatchRepository;
    @Autowired
    private NoticeHousingUnitTypeMatchRepository repository;

    private NoticeVersion version;
    private NoticeHousing matchedHousing;
    private NoticeHousing noSupplyRowHousing;
    private NoticeHousing unmatchedCatalogHousing;
    private NoticeHousingUnitTypeMatchService service;

    @BeforeEach
    void setUp() {
        HousingProviderAgency agency = agencyRepository.save(new HousingProviderAgency("LH", "한국토지주택공사"));

        HousingComplex complex = complexRepository.save(new HousingComplex(
                "울산구영1BL 국민임대", address("1111010100100010001"), agency, SourceSystem.MYHOME_PORTAL, "10001"));
        ComplexRentalProgram program = programRepository.save(
                new ComplexRentalProgram(complex, "국민임대", SupplyType.NATIONAL_RENTAL, 235));
        UnitType type59 = unitTypeRepository.save(
                new UnitType(program, "59", new BigDecimal("59.9400"), new BigDecimal("82.1224")));
        unitTypeRepository.save(new UnitType(program, "33A", new BigDecimal("33.7500"), new BigDecimal("46.0447")));
        unitTypeRepository.save(new UnitType(program, "33B", new BigDecimal("33.7700"), new BigDecimal("46.1")));

        HousingComplex complexWithoutSupplyRow = complexRepository.save(new HousingComplex(
                "안내단지", address("1111010100100010002"), agency, SourceSystem.MYHOME_PORTAL, "10002"));

        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "40001"));
        version = noticeVersionRepository.save(NoticeVersion.firstVersion(root, "40001", null, snapshot()));

        matchedHousing = housingRepository.save(new NoticeHousing(version, 0, 1, 20,
                suppliedHousing("1111010100100010001"), rentTerms(), null, null));
        noSupplyRowHousing = housingRepository.save(new NoticeHousing(version, 1, 2, 10,
                suppliedHousing("1111010100100010002"), rentTerms(), null, null));
        unmatchedCatalogHousing = housingRepository.save(new NoticeHousing(version, 2, 3, 5,
                suppliedHousing("9999999999999999999"), rentTerms(), null, null));

        catalogMatchRepository.save(new NoticeHousingCatalogMatch(matchedHousing, complex,
                NoticeHousingCatalogMatchStatus.MATCHED_PNU, "1111010100100010001", 1,
                CATALOG_VERSION, LocalDateTime.now(FIXED_CLOCK)));
        catalogMatchRepository.save(new NoticeHousingCatalogMatch(noSupplyRowHousing, complexWithoutSupplyRow,
                NoticeHousingCatalogMatchStatus.MATCHED_PNU, "1111010100100010002", 1,
                CATALOG_VERSION, LocalDateTime.now(FIXED_CLOCK)));
        catalogMatchRepository.save(new NoticeHousingCatalogMatch(unmatchedCatalogHousing, null,
                NoticeHousingCatalogMatchStatus.UNMATCHED, "9999999999999999999", 0,
                CATALOG_VERSION, LocalDateTime.now(FIXED_CLOCK)));

        LhUnitSupplyBatch batch = new LhUnitSupplyBatch(version, SourceSystem.LH_CHEONGYAK_PLUS,
                "2015122300018780", "03", "06", "07", "062",
                LocalDateTime.of(2026, 8, 13, 12, 11, 52), LocalDateTime.now(FIXED_CLOCK), true);
        batch.addUnitSupply(0, "울산구영1BL 국민임대", "59㎡", new BigDecimal("59.94"), new BigDecimal("82.1224"), 235, 20);
        batch.addUnitSupply(1, "울산구영1BL 국민임대", "33㎡", new BigDecimal("33.76"), new BigDecimal("46.05"), 132, 8);
        batch.addUnitSupply(2, "울산구영1BL 국민임대", "100㎡", new BigDecimal("100.00"), new BigDecimal("120.00"), 10, 2);
        batch.addUnitSupply(3, "전혀 다른 단지", "20㎡", new BigDecimal("20.00"), new BigDecimal("25.00"), 50, 5);
        unitSupplyBatchRepository.save(batch);

        service = new NoticeHousingUnitTypeMatchService(repository, noticeVersionRepository, housingRepository,
                catalogMatchRepository, unitSupplyBatchRepository, programRepository, unitTypeRepository,
                FIXED_CLOCK);
    }

    @Test
    @DisplayName("확정된 단지 위에서 전용면적으로 주택형을 매칭하고, 못 하면 이유별로 남긴다")
    void matchesConfirmedComplexesByExclusiveArea() {
        service.match(version.getId(), CATALOG_VERSION, MATCHER_VERSION);

        List<NoticeHousingUnitTypeMatch> results = repository.findAll().stream()
                .sorted(Comparator.comparing(NoticeHousingUnitTypeMatch::getId))
                .toList();

        // matchedHousing: 공급행 3개 → 59형 확정 매칭, 33형은 두 후보(33A/33B)가 오차 안에 들어 모호, 100형은 후보 없음
        List<NoticeHousingUnitTypeMatch> forMatchedHousing = results.stream()
                .filter(r -> r.getNoticeHousing().getId().equals(matchedHousing.getId()))
                .toList();
        assertThat(forMatchedHousing).extracting(NoticeHousingUnitTypeMatch::getStatus)
                .containsExactly(NoticeHousingUnitTypeMatchStatus.MATCHED,
                        NoticeHousingUnitTypeMatchStatus.AMBIGUOUS,
                        NoticeHousingUnitTypeMatchStatus.UNMATCHED);
        assertThat(forMatchedHousing.get(0).getUnitType().getTypeName()).isEqualTo("59");
        assertThat(forMatchedHousing.get(0).getSuppliedUnitCount()).isEqualTo(20);
        assertThat(forMatchedHousing.get(1).getCandidateCount()).isEqualTo(2);
        assertThat(forMatchedHousing.get(2).getUnitType()).isNull();

        // noSupplyRowHousing: 단지는 확정됐지만 "안내단지" 라벨의 공급행이 없다
        assertThat(results).filteredOn(r -> r.getNoticeHousing().getId().equals(noSupplyRowHousing.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(NoticeHousingUnitTypeMatchStatus.NO_SUPPLY_ROW));

        // unmatchedCatalogHousing: 애초에 단지 카탈로그 매칭이 안 됐다
        assertThat(results).filteredOn(r -> r.getNoticeHousing().getId().equals(unmatchedCatalogHousing.getId()))
                .singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(NoticeHousingUnitTypeMatchStatus.NO_CATALOG_MATCH));
    }

    @Test
    @DisplayName("공급정보 배치가 아직 없으면 전부 SOURCE_DATA_MISSING으로 남긴다")
    void marksSourceDataMissingWhenBatchAbsent() {
        unitSupplyBatchRepository.deleteAll();

        service.match(version.getId(), CATALOG_VERSION, MATCHER_VERSION);

        assertThat(repository.findAll()).extracting(NoticeHousingUnitTypeMatch::getStatus)
                .containsOnly(NoticeHousingUnitTypeMatchStatus.SOURCE_DATA_MISSING);
    }

    @Test
    @DisplayName("같은 matcherVersion을 다시 돌리면 그 버전의 기존 결과만 지우고 다시 만든다")
    void replacesOnlyTheSameMatcherVersion() {
        service.match(version.getId(), CATALOG_VERSION, MATCHER_VERSION);
        int firstRunCount = repository.findAll().size();

        service.match(version.getId(), CATALOG_VERSION, "unit-type-area-v2");
        service.match(version.getId(), CATALOG_VERSION, MATCHER_VERSION);

        assertThat(repository.findAll()).hasSize(firstRunCount * 2);
    }

    private Address address(String pnu) {
        return new Address("울산광역시 어딘가길 1", pnu, "31", "울산광역시", "110", "구영동");
    }

    private NoticeSnapshot snapshot() {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                "국민임대 입주자 모집", "https://www.myhome.go.kr/detail",
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "국민임대", "LH 콜센터 : 1600-1004");
    }

    private SuppliedHousing suppliedHousing(String pnu) {
        return new SuppliedHousing("울산구영1BL 국민임대", "울산광역시 어딘가길 1", pnu,
                "어딘가길", null, "울산광역시", "구영동", "지역난방", 235);
    }

    private RentTerms rentTerms() {
        return new RentTerms(19_546_000L, 977_000L, 18_569_000L, 195_460L);
    }
}
