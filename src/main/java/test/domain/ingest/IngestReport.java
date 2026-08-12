package test.domain.ingest;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 원천 적재 결과. 의도적인 제외({@link #rejectedByReason})와 재시도가 필요한 외부 실패({@link #failed})를
 * 분리한다.
 *
 * @param created          새로 만든 aggregate 수
 * @param versioned        새 버전을 만든 aggregate 수
 * @param unchanged        이미 같은 내용이 있어 넘어간 aggregate 수
 * @param failed           인증·통신·응답 오류로 재시도가 필요한 수
 * @param rejectedByReason 도메인 경계나 최소 품질 조건 때문에 의도적으로 제외한 원천 행·aggregate 수
 */
public record IngestReport(int created,
                           int versioned,
                           int unchanged,
                           int failed,
                           Map<IngestRejectionReason, Integer> rejectedByReason) {

    public IngestReport {
        if (created < 0 || versioned < 0 || unchanged < 0 || failed < 0) {
            throw new IllegalArgumentException("적재 결과 개수는 음수일 수 없습니다.");
        }
        EnumMap<IngestRejectionReason, Integer> copy = new EnumMap<>(IngestRejectionReason.class);
        if (rejectedByReason != null) {
            rejectedByReason.forEach((reason, count) -> {
                if (reason == null || count == null || count < 1) {
                    throw new IllegalArgumentException("제외 사유와 개수는 필수이며 개수는 1 이상이어야 합니다.");
                }
                copy.put(reason, count);
            });
        }
        rejectedByReason = Collections.unmodifiableMap(copy);
    }

    /** 기존 호출부를 단계적으로 옮기는 동안 네 번째 값을 무효 원천 행 제외로 해석한다. */
    public IngestReport(int created, int versioned, int unchanged, int skipped) {
        this(created, versioned, unchanged, 0,
                skipped == 0
                        ? Map.of()
                        : Map.of(IngestRejectionReason.INVALID_SOURCE_ROW, skipped));
    }

    public static IngestReport empty() {
        return new IngestReport(0, 0, 0, 0, Map.of());
    }

    public static IngestReport oneCreated() {
        return new IngestReport(1, 0, 0, 0, Map.of());
    }

    public static IngestReport oneVersioned() {
        return new IngestReport(0, 1, 0, 0, Map.of());
    }

    public static IngestReport oneUnchanged() {
        return new IngestReport(0, 0, 1, 0, Map.of());
    }

    public static IngestReport oneFailed() {
        return new IngestReport(0, 0, 0, 1, Map.of());
    }

    public static IngestReport oneRejected(IngestRejectionReason reason) {
        return new IngestReport(0, 0, 0, 0, Map.of(reason, 1));
    }

    public int rejected() {
        return rejectedByReason.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 이전 API 이름. 모든 호출부가 사유 기반 결과로 이동하면 제거한다. */
    public int skipped() {
        return rejected();
    }

    public IngestReport plus(IngestReport other) {
        EnumMap<IngestRejectionReason, Integer> rejections = new EnumMap<>(IngestRejectionReason.class);
        rejectedByReason.forEach(rejections::put);
        other.rejectedByReason.forEach((reason, count) -> rejections.merge(reason, count, Integer::sum));
        return new IngestReport(
                created + other.created,
                versioned + other.versioned,
                unchanged + other.unchanged,
                failed + other.failed,
                rejections);
    }
}
