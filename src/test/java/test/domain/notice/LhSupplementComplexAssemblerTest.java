package test.domain.notice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import test.domain.source.SourceSystem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LhSupplementComplexAssemblerTest {

    private final LhSupplementComplexAssembler assembler = new LhSupplementComplexAssembler();

    @Test
    @DisplayName("단지명이 정확히 하나의 단지에만 일치할 때만 일정·첨부를 그 단지 view에 붙인다")
    void attachesLabelsOnlyWhenExactlyOneComplexDetailHasTheSameName() {
        LhNoticeSupplement supplement = supplementWithComplexLabels(
                List.of("파주운정 A1", "파주운정 A2"),
                List.of("파주운정 A1", "없는 단지"),
                List.of("파주운정 A2"));

        List<LhSupplementComplexView> views = assembler.assemble(supplement);

        assertThat(view(views, "파주운정 A1").schedules()).hasSize(1);
        assertThat(view(views, "파주운정 A2").attachments()).hasSize(1);
        assertThat(assembler.unassignedSchedules(supplement)).extracting(NoticeSchedule::getComplexLabel)
                .containsExactly("없는 단지");
    }

    @Test
    @DisplayName("같은 이름의 단지가 둘이면 어느 view에도 붙이지 않는다")
    void leavesLabelUnassignedWhenTwoDetailsHaveTheSameName() {
        LhNoticeSupplement supplement = supplementWithDuplicateComplexLabels("중복 단지");

        assertThat(assembler.assemble(supplement))
                .allSatisfy(view -> assertThat(view.schedules()).isEmpty());
        assertThat(assembler.unassignedSchedules(supplement)).hasSize(1);
    }

    @Test
    @DisplayName("일치하는 단지가 없는 첨부도 어느 view에도 붙지 않고 unassignedAttachments로 잡힌다")
    void leavesAttachmentUnassignedWhenNoComplexDetailMatches() {
        LhNoticeSupplement supplement = supplementWithComplexLabels(
                List.of("파주운정 A1"),
                List.of(),
                List.of("없는 단지"));

        assertThat(assembler.assemble(supplement)).allSatisfy(view -> assertThat(view.attachments()).isEmpty());
        assertThat(assembler.unassignedAttachments(supplement))
                .extracting(NoticeAttachment::getComplexLabel)
                .containsExactly("없는 단지");
    }

    private LhSupplementComplexView view(List<LhSupplementComplexView> views, String complexLabel) {
        return views.stream()
                .filter(view -> complexLabel.equals(view.complexDetail().getComplexLabel()))
                .findFirst()
                .orElseThrow();
    }

    private LhNoticeSupplement supplementWithComplexLabels(List<String> complexLabels,
                                                           List<String> scheduleLabels,
                                                           List<String> attachmentLabels) {
        LhNoticeSupplement supplement = newSupplement();
        for (int i = 0; i < complexLabels.size(); i++) {
            supplement.addComplexDetail(i, complexLabels.get(i), null, null, null, null, null, null, null);
        }
        for (int i = 0; i < scheduleLabels.size(); i++) {
            supplement.addSchedule(i, scheduleLabels.get(i), null, null, null, null, null, null);
        }
        for (int i = 0; i < attachmentLabels.size(); i++) {
            supplement.addAttachment(i, "공고문(PDF)", "파일" + i + ".pdf",
                    "https://apply.lh.or.kr/file" + i + ".pdf", attachmentLabels.get(i));
        }
        return supplement;
    }

    private LhNoticeSupplement supplementWithDuplicateComplexLabels(String complexLabel) {
        LhNoticeSupplement supplement = newSupplement();
        supplement.addComplexDetail(0, complexLabel, null, null, null, null, null, null, null);
        supplement.addComplexDetail(1, complexLabel, null, null, null, null, null, null, null);
        supplement.addSchedule(0, complexLabel, null, null, null, null, null, null);
        return supplement;
    }

    private LhNoticeSupplement newSupplement() {
        return new LhNoticeSupplement(null, SourceSystem.LH_CHEONGYAK_PLUS,
                "2015122300020501", "03", "06", "10", "063", null, null, true, null);
    }
}
