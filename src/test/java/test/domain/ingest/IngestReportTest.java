package test.domain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestReportTest {

    @Test
    @DisplayName("생성·실패·제외 사유를 서로 잃지 않고 합산한다")
    void addsCountsAndRejectionReasons() {
        IngestReport report = IngestReport.oneCreated()
                .plus(IngestReport.oneRejected(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE))
                .plus(IngestReport.oneRejected(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE))
                .plus(IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW))
                .plus(IngestReport.oneFailed());

        assertThat(report.created()).isOne();
        assertThat(report.versioned()).isZero();
        assertThat(report.unchanged()).isZero();
        assertThat(report.failed()).isOne();
        assertThat(report.rejected()).isEqualTo(3);
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE, 2)
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
    }

    @Test
    @DisplayName("외부에서 제외 사유 맵을 바꿀 수 없다")
    void keepsRejectionCountsImmutable() {
        IngestReport report = IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY);

        assertThatThrownBy(() -> report.rejectedByReason()
                .put(IngestRejectionReason.INVALID_SOURCE_ROW, 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
