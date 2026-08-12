package test.domain.notice;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.source.SourceSystem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class NoticeSupplementPersistenceTest {

    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeSupplementRepository supplementRepository;
    @Autowired
    private NoticeScheduleRepository scheduleRepository;
    @Autowired
    private ReceptionPlaceRepository receptionPlaceRepository;
    @Autowired
    private NoticeComplexSnapshotRepository complexSnapshotRepository;
    @Autowired
    private NoticeAttachmentRepository attachmentRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("LH 보충 스냅샷을 한 번 저장하면 모든 자식과 입주예정월이 함께 보존된다")
    void savesSupplementAggregate() {
        NoticeVersion notice = noticeVersionRepository.save(NoticeVersion.firstVersion(
                "21006", SourceSystem.MYHOME_PORTAL, snapshot()));
        NoticeSupplement supplement = new NoticeSupplement(
                notice, SourceSystem.LH_CHEONGYAK_PLUS, "공급일정 일부 수정");
        supplement.addSchedule(0, "성남금토 A-4블록", "2026.08.24 10:00 ~ 2026.08.26 16:10",
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 14),
                LocalDate.of(2027, 1, 19), LocalDate.of(2027, 1, 21));
        supplement.addSchedule(1, "성남금토 A-5블록", null,
                null, null, null, null, null);
        supplement.addReceptionPlace(0, "경기도 성남시 분당구 성남대로54번길 3", "1층 105호",
                "2026.08.25", "2026.08.25", "1600-1004", "고령자 및 장애인 현장접수");
        supplement.addComplexSnapshot(0, "성남금토 A-4블록", "경기도 성남시 수정구 금토동", "417-4",
                384, "지역난방", "55.83~55.99", YearMonth.of(2027, 11));
        supplement.addAttachment(0, "공고문(PDF)", "입주자모집공고문.pdf",
                "https://apply.lh.or.kr/file.pdf", null);

        supplementRepository.saveAndFlush(supplement);
        entityManager.clear();

        NoticeSupplement saved = supplementRepository.findByNoticeVersionId(notice.getId()).orElseThrow();
        assertThat(saved.getCorrectionReason()).isEqualTo("공급일정 일부 수정");
        assertThat(saved.getSchedules()).extracting(NoticeSchedule::getDisplayOrder)
                .containsExactly(0, 1);
        assertThat(saved.getReceptionPlaces()).singleElement()
                .extracting(ReceptionPlace::getPhone)
                .isEqualTo("1600-1004");
        assertThat(saved.getComplexSnapshots()).singleElement()
                .extracting(NoticeComplexSnapshot::getExpectedMoveInYearMonth)
                .isEqualTo(YearMonth.of(2027, 11));
        assertThat(saved.getAttachments()).singleElement()
                .extracting(NoticeAttachment::getKind)
                .isEqualTo("공고문(PDF)");

        assertThat(scheduleRepository.count()).isEqualTo(2);
        assertThat(receptionPlaceRepository.count()).isOne();
        assertThat(complexSnapshotRepository.count()).isOne();
        assertThat(attachmentRepository.count()).isOne();

        assertThatThrownBy(() -> saved.getAttachments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> saved.addAttachment(1, "공고문(PDF)", "추가.pdf",
                "https://apply.lh.or.kr/extra.pdf", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("저장된 보충 스냅샷");
    }

    private NoticeSnapshot snapshot() {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                "성남금토 행복주택 입주자 모집", "https://apply.lh.or.kr/?panId=2015122300020534",
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 26), LocalDate.of(2027, 1, 7),
                "LH", "아파트", "행복주택", "1600-1004");
    }
}
