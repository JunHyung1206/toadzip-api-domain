package test.domain.match;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.housing.Address;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.HousingProviderAgency;
import test.domain.housing.HousingProviderAgencyRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static test.domain.match.NoticeHousingCatalogMatchStatus.AMBIGUOUS;
import static test.domain.match.NoticeHousingCatalogMatchStatus.MATCHED_PNU;
import static test.domain.match.NoticeHousingCatalogMatchStatus.UNMATCHED;

@DataJpaTest
class NoticeHousingCatalogMatchServiceTest {

    private static final String PNU_MATCHED = "1111010100100010001";
    private static final String PNU_UNMATCHED = "1111010100100010002";
    private static final String PNU_AMBIGUOUS = "1111010100100010003";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T04:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeHousingRepository housingRepository;
    @Autowired
    private HousingProviderAgencyRepository agencyRepository;
    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private NoticeHousingCatalogMatchRepository repository;

    private final int noticeHousingCount = 3;

    private NoticeVersion version;
    private NoticeHousingCatalogMatchService service;

    @BeforeEach
    void setUp() {
        HousingProviderAgency agency = agencyRepository.save(new HousingProviderAgency("LH", "한국토지주택공사"));
        complexRepository.save(new HousingComplex(
                "매칭단지", address(PNU_MATCHED), agency, SourceSystem.MYHOME_PORTAL, "10001"));
        complexRepository.save(new HousingComplex(
                "중복단지1", address(PNU_AMBIGUOUS), agency, SourceSystem.MYHOME_PORTAL, "10002"));
        complexRepository.save(new HousingComplex(
                "중복단지2", address(PNU_AMBIGUOUS), agency, SourceSystem.MYHOME_PORTAL, "10003"));

        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "40001"));
        version = noticeVersionRepository.save(NoticeVersion.firstVersion(root, "40001", null, snapshot()));

        housingRepository.save(new NoticeHousing(version, 0, 1, 100,
                suppliedHousing(PNU_MATCHED), rentTerms(), null, null));
        housingRepository.save(new NoticeHousing(version, 1, 2, 100,
                suppliedHousing(PNU_UNMATCHED), rentTerms(), null, null));
        housingRepository.save(new NoticeHousing(version, 2, 3, 100,
                suppliedHousing(PNU_AMBIGUOUS), rentTerms(), null, null));

        service = new NoticeHousingCatalogMatchService(repository, housingRepository, complexRepository, FIXED_CLOCK);
    }

    @Test
    void matchesOnlyOneExactPnuCandidate() {
        service.match(version.getId(), "catalog-pnu-v1");

        assertThat(results()).extracting(NoticeHousingCatalogMatch::getStatus)
                .containsExactly(MATCHED_PNU, UNMATCHED, AMBIGUOUS);
        assertThat(results()).extracting(NoticeHousingCatalogMatch::getCandidateCount)
                .containsExactly(1, 0, 2);
        assertThat(results().get(0).getHousingComplex()).isNotNull();
        assertThat(results().get(1).getHousingComplex()).isNull();
        assertThat(results().get(2).getHousingComplex()).isNull();
    }

    @Test
    void replacesOnlyTheSameMatcherVersion() {
        service.match(version.getId(), "catalog-pnu-v1");
        service.match(version.getId(), "catalog-pnu-v2");
        service.match(version.getId(), "catalog-pnu-v1");

        assertThat(repository.findAll()).hasSize(noticeHousingCount * 2);
    }

    private List<NoticeHousingCatalogMatch> results() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(NoticeHousingCatalogMatch::getId))
                .toList();
    }

    private Address address(String pnu) {
        return new Address("서울특별시 종로구 어딘가길 1", pnu, "11", "서울특별시", "110", "종로구");
    }

    private NoticeSnapshot snapshot() {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                "행복주택 입주자 모집", "https://www.myhome.go.kr/detail",
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "행복주택", "LH 콜센터 : 1600-1004");
    }

    private SuppliedHousing suppliedHousing(String pnu) {
        return new SuppliedHousing("구리수택", "경기도 구리시 수택동", pnu,
                "체육관로74번길", null, "경기도", "구리시", "개별난방", 394);
    }

    private RentTerms rentTerms() {
        return new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L);
    }
}
