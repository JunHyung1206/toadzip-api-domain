package test.domain.notice;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 한 {@link LhNoticeSupplement} 안에서 일정·첨부를 단지명(complexLabel) 원문으로 단지 하나에 조립한다.
 *
 * <p>PNU 가 없어 이름으로만 이을 수 있는데, LH 가 공고 하나에 단지를 여럿 담을 때 이름이 겹치면
 * 어느 쪽 것인지 알 수 없다. 그래서 같은 supplement 안에서 {@link LhComplexDetail#getComplexLabel()} 과
 * 정확히 한 번만 일치할 때만 그 단지 view 에 붙이고, 0건이거나 2건 이상이면 원천 자식은 supplement 에
 * 그대로 두고 어느 view 에도 붙이지 않는다. trim 외의 변형(공백 정규화, 접미어 제거 등)은 하지 않는다.
 */
@Component
public class LhSupplementComplexAssembler {

    public List<LhSupplementComplexView> assemble(LhNoticeSupplement supplement) {
        Map<String, Long> labelCounts = countByLabel(supplement.getComplexDetails());
        List<LhSupplementComplexView> views = new ArrayList<>();
        for (LhComplexDetail detail : supplement.getComplexDetails()) {
            String label = detail.getComplexLabel();
            boolean assignable = isAssignable(label, labelCounts);
            views.add(new LhSupplementComplexView(
                    detail,
                    assignable ? matching(supplement.getSchedules(), NoticeSchedule::getComplexLabel, label)
                            : List.of(),
                    assignable ? matching(supplement.getAttachments(), NoticeAttachment::getComplexLabel, label)
                            : List.of()));
        }
        return views;
    }

    /** 어느 단지 view 에도 붙지 못한 일정. 원천 행은 supplement 에 그대로 남아 있다. */
    public List<NoticeSchedule> unassignedSchedules(LhNoticeSupplement supplement) {
        Map<String, Long> labelCounts = countByLabel(supplement.getComplexDetails());
        return supplement.getSchedules().stream()
                .filter(schedule -> !isAssignable(schedule.getComplexLabel(), labelCounts))
                .toList();
    }

    /** 어느 단지 view 에도 붙지 못한 첨부. 원천 행은 supplement 에 그대로 남아 있다. */
    public List<NoticeAttachment> unassignedAttachments(LhNoticeSupplement supplement) {
        Map<String, Long> labelCounts = countByLabel(supplement.getComplexDetails());
        return supplement.getAttachments().stream()
                .filter(attachment -> !isAssignable(attachment.getComplexLabel(), labelCounts))
                .toList();
    }

    private boolean isAssignable(String label, Map<String, Long> labelCounts) {
        return label != null && labelCounts.getOrDefault(label, 0L) == 1;
    }

    private Map<String, Long> countByLabel(List<LhComplexDetail> details) {
        return details.stream()
                .map(LhComplexDetail::getComplexLabel)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private <T> List<T> matching(List<T> rows, Function<T, String> labelOf, String label) {
        return rows.stream()
                .filter(row -> label.equals(labelOf.apply(row)))
                .toList();
    }
}
