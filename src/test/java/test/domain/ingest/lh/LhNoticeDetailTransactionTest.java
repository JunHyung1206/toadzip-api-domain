package test.domain.ingest.lh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import test.domain.ingest.IngestReport;
import test.domain.notice.LhNoticeSupplement;
import test.domain.notice.LhNoticeSupplementRepository;
import test.domain.notice.NoticeAttachmentRepository;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeScheduleRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.ReceptionPlaceRepository;
import test.domain.notice.RecruitmentNotice;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.source.SourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공고 하나의 보충 aggregate 저장 실패가 다른 공고의 저장을 막지도, 이미 커밋된 데이터를 되돌리지도
 * 않는지 확인한다({@code REQUIRES_NEW} + try/catch, {@link LhNoticeDetailIngestService#apply}).
 *
 * <p>REQUIRES_NEW 가 실제로 커밋되는지 봐야 해서 {@code @DataJpaTest} 의 기본 롤백을 끈다
 * ({@code Propagation.NOT_SUPPORTED}). 다른 테스트가 이 커밋의 영향을 받지 않도록 클래스를 분리했다
 * (마이홈 쪽 {@code MyHomeNoticeTransactionTest}/{@code MyHomeComplexTransactionTest} 와 같은 이유).
 */
@DataJpaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LhNoticeDetailTransactionTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T04:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String FAILING_PAN_ID = "2015122300030001";
    private static final String SAVED_PAN_ID = "2015122300030002";

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private LhNoticeSupplementRepository supplementRepository;
    @Autowired
    private NoticeScheduleRepository scheduleRepository;
    @Autowired
    private ReceptionPlaceRepository receptionPlaceRepository;
    @Autowired
    private NoticeAttachmentRepository attachmentRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("한 공고의 보충 저장 실패는 그 공고만 롤백하고, 다음 공고 저장은 계속된다")
    void rollsBackOnlyTheFailingNoticeAndContinuesWithTheNext() {
        NoticeVersion failingNotice = save("40001", FAILING_PAN_ID);
        NoticeVersion savedNotice = save("40002", SAVED_PAN_ID);

        LhNoticeSupplementRepository failingOnFirstNotice = mock(LhNoticeSupplementRepository.class);
        when(failingOnFirstNotice.existsByNoticeVersionId(any()))
                .thenAnswer(invocation -> supplementRepository.existsByNoticeVersionId(invocation.getArgument(0)));
        when(failingOnFirstNotice.save(any(LhNoticeSupplement.class))).thenAnswer(invocation -> {
            LhNoticeSupplement supplement = invocation.getArgument(0);
            if (FAILING_PAN_ID.equals(supplement.getSourcePanId())) {
                throw new IllegalStateException("보충 저장 실패");
            }
            return supplementRepository.save(supplement);
        });

        LhNoticeDetailIngestService service = new LhNoticeDetailIngestService(
                null, MAPPER, noticeVersionRepository, failingOnFirstNotice,
                new LhSupplyInfoTypeResolver(), transactionManager, FIXED_CLOCK);

        IngestReport failingReport = service.apply(failingNotice, root());
        IngestReport savedReport = service.apply(savedNotice, root());

        assertThat(failingReport.failed()).isOne();
        assertThat(savedReport.created()).isOne();

        assertThat(supplementRepository.existsByNoticeVersionId(failingNotice.getId())).isFalse();
        assertThat(supplementRepository.existsByNoticeVersionId(savedNotice.getId())).isTrue();
        assertThat(supplementRepository.count()).isOne();
        assertThat(scheduleRepository.count()).isOne();
        assertThat(receptionPlaceRepository.count()).isOne();
        assertThat(attachmentRepository.count()).isOne();
    }

    private NoticeVersion save(String sourceNoticeId, String panId) {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, sourceNoticeId));
        return noticeVersionRepository.save(NoticeVersion.firstVersion(
                root, sourceNoticeId, null, snapshot(panId)));
    }

    private NoticeSnapshot snapshot(String panId) {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 5, 0, 0),
                "행복주택 입주자 모집",
                "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do"
                        + "?panId=" + panId + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10",
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "행복주택", "LH 콜센터 : 1600-1004");
    }

    private JsonNode root() {
        return MAPPER.readTree("""
                [
                 {"dsSplScdl":[{"SBD_LGO_NM":"테스트 단지","ACP_DTTM":"2026.08.18 10:00 ~ 2026.08.20 16:00"}]},
                 {"dsCtrtPlc":[{"CTRT_PLC_ADR":"서울특별시 강남구","SIL_OFC_TLNO":"1600-1004"}]},
                 {"dsAhflInfo":[{"AHFL_URL":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=1",
                                 "SL_PAN_AHFL_DS_CD_NM":"공고문(PDF)","CMN_AHFL_NM":"모집공고문.pdf"}]},
                 {"resHeader":[{"RS_DTTM":"20260812123144","SS_CODE":"Y"}]}
                ]
                """);
    }
}
