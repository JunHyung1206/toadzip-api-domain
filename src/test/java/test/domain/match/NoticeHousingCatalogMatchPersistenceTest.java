package test.domain.match;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class NoticeHousingCatalogMatchPersistenceTest {

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeHousingRepository housingRepository;
    @Autowired
    private NoticeHousingCatalogMatchRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void savesMatchWithNullableHousingComplex() {
        NoticeHousing housing = saveNoticeHousing("50001", 1, "1111010100100010001");

        NoticeHousingCatalogMatch unmatched = repository.save(new NoticeHousingCatalogMatch(
                housing, null, NoticeHousingCatalogMatchStatus.UNMATCHED, null, 0, "catalog-pnu-v1",
                LocalDateTime.of(2026, 8, 12, 0, 0)));

        assertThat(unmatched.getHousingComplex()).isNull();
        assertThat(unmatched.getStatus()).isEqualTo(NoticeHousingCatalogMatchStatus.UNMATCHED);
        assertThat(unmatched.getCandidateCount()).isZero();
    }

    @Test
    void enforcesUniqueNoticeHousingAndMatcherVersionPair() {
        NoticeHousing housing = saveNoticeHousing("50002", 1, "1111010100100010002");
        repository.save(new NoticeHousingCatalogMatch(
                housing, null, NoticeHousingCatalogMatchStatus.UNMATCHED, null, 0, "catalog-pnu-v1",
                LocalDateTime.of(2026, 8, 12, 0, 0)));
        entityManager.flush();

        assertThatThrownBy(() -> repository.save(new NoticeHousingCatalogMatch(
                housing, null, NoticeHousingCatalogMatchStatus.UNMATCHED, null, 0, "catalog-pnu-v1",
                LocalDateTime.of(2026, 8, 12, 0, 0))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private NoticeHousing saveNoticeHousing(String sourceNoticeId, int houseSn, String pnu) {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, sourceNoticeId));
        NoticeVersion version = noticeVersionRepository.save(
                NoticeVersion.firstVersion(root, sourceNoticeId, null, snapshot()));
        return housingRepository.save(new NoticeHousing(version, 0, houseSn, 100,
                suppliedHousing(pnu), rentTerms(), null, null));
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
