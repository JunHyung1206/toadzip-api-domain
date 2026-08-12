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
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNoticeRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("공급행 저장이 실패하면 같은 공고버전도 남지 않는다")
    void rollsBackWholeNoticeWhenSupplyLineFails() {
        NoticeHousingRepository failingNoticeHousingRepository = mock(NoticeHousingRepository.class);
        when(failingNoticeHousingRepository.save(any(NoticeHousing.class)))
                .thenThrow(new IllegalStateException("공급행 저장 실패"));
        MyHomeNoticeIngestService service = new MyHomeNoticeIngestService(
                null, recruitmentNoticeRepository, noticeVersionRepository, failingNoticeHousingRepository,
                new ConstructionRentalPolicy(), transactionManager);

        assertThatThrownBy(() -> service.apply(
                MyHomeFixtures.noticeItems().stream()
                        .filter(item -> item.pblancId().equals("20989"))
                        .toList()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("공급행 저장 실패");

        assertThat(noticeVersionRepository.count()).isZero();
    }
}
