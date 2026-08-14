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
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MyHomeNoticeTransactionTest {

    @Autowired
    private NoticeRepository noticeRepository;
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
        NoticeSupplyRepository failingSupplyRepository = mock(NoticeSupplyRepository.class);
        when(failingSupplyRepository.save(any(NoticeSupply.class))).thenAnswer(invocation -> {
            NoticeSupply supply = invocation.getArgument(0);
            if ("50002".equals(supply.getNotice().getSourceNoticeId())) {
                throw new IllegalStateException("공급행 저장 실패");
            }
            return supply;
        });
        MyHomeNoticeIngestService service = new MyHomeNoticeIngestService(
                null, noticeRepository, failingSupplyRepository,
                new ConstructionRentalPolicy(), transactionManager);

        IngestReport report = service.apply(MyHomeFixtures.itemsForTwoNoticesOneFailing());

        assertThat(report.failed()).isOne();
        assertThat(report.created()).isOne();
        assertThat(noticeRepository.findBySourceNoticeId("50001")).isPresent();
        assertThat(noticeRepository.findBySourceNoticeId("50002")).isEmpty();
    }
}
