package test.domain.ingest.myhome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.IngestReport;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.source.SourceSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MyHomeNoticeTransactionTest {

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 서로 독립적인 공고 둘(50001, 50002) 중 50002 만 공급행 저장에서 실패하게 만들어
     * 공고 단위 트랜잭션 경계(REQUIRES_NEW + try/catch)를 확인한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("한 공고의 저장 실패는 그 공고만 롤백하고, 다른 공고 저장은 계속된다")
    void rollsBackOnlyTheFailingNoticeAndContinuesWithTheNext() {
        NoticeHousingRepository failingNoticeHousingRepository = mock(NoticeHousingRepository.class);
        when(failingNoticeHousingRepository.save(any(NoticeHousing.class))).thenAnswer(invocation -> {
            NoticeHousing housing = invocation.getArgument(0);
            if ("50002".equals(housing.getNoticeVersion().getSourceNoticeId())) {
                throw new IllegalStateException("공급행 저장 실패");
            }
            return housing;
        });
        MyHomeNoticeIngestService service = new MyHomeNoticeIngestService(
                null, recruitmentNoticeRepository, noticeVersionRepository, failingNoticeHousingRepository,
                new ConstructionRentalPolicy(), transactionManager);

        IngestReport report = service.apply(MyHomeFixtures.itemsForTwoNoticesOneFailing());

        assertThat(report.failed()).isOne();
        assertThat(report.created()).isOne();
        assertThat(noticeVersionRepository.findBySourceSystemAndSourceNoticeId(
                SourceSystem.MYHOME_PORTAL, "50001")).isPresent();
        assertThat(noticeVersionRepository.findBySourceSystemAndSourceNoticeId(
                SourceSystem.MYHOME_PORTAL, "50002")).isEmpty();
    }
}
