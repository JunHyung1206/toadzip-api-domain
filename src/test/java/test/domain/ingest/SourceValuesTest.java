package test.domain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class SourceValuesTest {

    @Test
    @DisplayName("마이홈 압축 날짜와 LH 점 구분 날짜를 모두 변환한다")
    void convertsSupportedDateFormats() {
        assertThat(SourceValues.toDate("20260907")).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(SourceValues.toDate("2026.09.07")).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(SourceValues.toDate("2026-09-07")).isNull();
    }

    @Test
    @DisplayName("LH 입주예정월을 YearMonth로 변환한다")
    void convertsSupportedYearMonthFormats() {
        assertThat(SourceValues.toYearMonth("202711")).isEqualTo(YearMonth.of(2027, 11));
        assertThat(SourceValues.toYearMonth("2027.11")).isEqualTo(YearMonth.of(2027, 11));
        assertThat(SourceValues.toYearMonth("2027.13")).isNull();
        assertThat(SourceValues.toYearMonth(" ")).isNull();
    }
}
