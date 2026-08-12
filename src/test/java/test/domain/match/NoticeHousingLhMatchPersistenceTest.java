package test.domain.match;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import test.domain.notice.LhComplexDetail;
import test.domain.notice.LhNoticeSupplement;
import test.domain.notice.LhNoticeSupplementRepository;
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
class NoticeHousingLhMatchPersistenceTest {

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeHousingRepository housingRepository;
    @Autowired
    private LhNoticeSupplementRepository supplementRepository;
    @Autowired
    private NoticeHousingLhMatchRepository repository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void savesMatchWithNullableLhComplexDetail() {
        NoticeVersion version = saveNoticeVersion("60001");
        NoticeHousing housing = saveNoticeHousing(version, 1, "산내마을1단지", "경기도 파주시 산내로 1", 620);

        NoticeHousingLhMatch unmatched = repository.save(new NoticeHousingLhMatch(
                version, housing, null, NoticeHousingLhMatchStatus.UNMATCHED, 0, 0,
                "lh-address-unit-v1", LocalDateTime.of(2026, 8, 12, 0, 0),
                evidence("경기도 파주시 산내로 1", null, null, null), 0));

        assertThat(unmatched.getLhComplexDetail()).isNull();
        assertThat(unmatched.getNoticeHousing()).isNotNull();
        assertThat(unmatched.getStatus()).isEqualTo(NoticeHousingLhMatchStatus.UNMATCHED);
    }

    @Test
    void savesMatchWithNullableNoticeHousing() {
        NoticeVersion version = saveNoticeVersion("60002");
        LhComplexDetail detail = saveLhComplexDetail(version, "LH단독단지", "경기도 하남시 미사대로 1", 300);

        NoticeHousingLhMatch lhOnly = repository.save(new NoticeHousingLhMatch(
                version, null, detail, NoticeHousingLhMatchStatus.UNMATCHED, 0, 0,
                "lh-address-unit-v1", LocalDateTime.of(2026, 8, 12, 0, 0),
                evidence(null, null, "경기도 하남시 미사대로 1", 300), 0));

        assertThat(lhOnly.getNoticeHousing()).isNull();
        assertThat(lhOnly.getLhComplexDetail()).isNotNull();
    }

    @Test
    void enforcesUniqueNoticeVersionMatcherVersionAndResultOrder() {
        NoticeVersion version = saveNoticeVersion("60003");
        NoticeHousing housing = saveNoticeHousing(version, 1, "테스트단지", "경기도 성남시 테스트로 1", 300);
        repository.save(new NoticeHousingLhMatch(
                version, housing, null, NoticeHousingLhMatchStatus.UNMATCHED, 0, 0,
                "lh-address-unit-v1", LocalDateTime.of(2026, 8, 12, 0, 0),
                evidence("경기도 성남시 테스트로 1", null, null, null), 0));
        entityManager.flush();

        assertThatThrownBy(() -> repository.save(new NoticeHousingLhMatch(
                version, housing, null, NoticeHousingLhMatchStatus.UNMATCHED, 0, 0,
                "lh-address-unit-v1", LocalDateTime.of(2026, 8, 12, 0, 0),
                evidence("경기도 성남시 테스트로 1", null, null, null), 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private NoticeVersion saveNoticeVersion(String sourceNoticeId) {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, sourceNoticeId));
        return noticeVersionRepository.save(NoticeVersion.firstVersion(root, sourceNoticeId, null, snapshot()));
    }

    private NoticeHousing saveNoticeHousing(NoticeVersion version, int houseSn, String complexName,
                                            String address, Integer unitCount) {
        return housingRepository.save(new NoticeHousing(version, 0, houseSn, 50,
                new SuppliedHousing(complexName, address, null, null, null, null, null, null, unitCount),
                rentTerms(), null, null));
    }

    private LhComplexDetail saveLhComplexDetail(NoticeVersion version, String complexLabel,
                                                String address, Integer unitCount) {
        LhNoticeSupplement supplement = new LhNoticeSupplement(version, SourceSystem.LH_CHEONGYAK_PLUS,
                "2015122300060000", "03", "06", "10", "063",
                LocalDateTime.of(2026, 8, 11, 12, 0), LocalDateTime.of(2026, 8, 11, 13, 0),
                true, null);
        supplement.addComplexDetail(0, complexLabel, address, null, unitCount, "지역난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);
        return supplement.getComplexDetails().get(0);
    }

    private NoticeHousingLhMatchEvidence evidence(String noticeAddress, Integer noticeUnitCount,
                                                   String lhAddress, Integer lhUnitCount) {
        return new NoticeHousingLhMatchEvidence(noticeAddress, noticeAddress, lhAddress, lhAddress,
                noticeUnitCount, lhUnitCount, null, "테스트 근거");
    }

    private NoticeSnapshot snapshot() {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                "행복주택 입주자 모집", "https://www.myhome.go.kr/detail",
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "행복주택", "LH 콜센터 : 1600-1004");
    }

    private RentTerms rentTerms() {
        return new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L);
    }
}
