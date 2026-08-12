package test.domain.ingest;

/**
 * @param created   새로 만든 행
 * @param versioned 내용이 바뀌어 버전을 올린 건
 * @param unchanged 원천은 읽었지만 내용이 같아 넘어간 건
 * @param skipped   식별자가 없어 적재할 수 없던 건
 */
public record IngestReport(int created, int versioned, int unchanged, int skipped) {

    public static IngestReport empty() {
        return new IngestReport(0, 0, 0, 0);
    }

    public IngestReport plus(IngestReport other) {
        return new IngestReport(
                created + other.created,
                versioned + other.versioned,
                unchanged + other.unchanged,
                skipped + other.skipped);
    }
}
