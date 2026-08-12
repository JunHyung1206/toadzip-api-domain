package test.domain.ingest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 원천이 문자열로 흘려주는 값들을 도메인 타입으로 옮기는 자리. */
public final class SourceValues {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int EARLIEST_COMPLETION_YEAR = 1900;
    private static final int LATEST_COMPLETION_YEAR = 2100;

    private SourceValues() {
    }

    public static String trimToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.strip();
    }

    /** "20260701" → 2026-07-01. 빈 값이거나 형식이 깨졌으면 null. */
    public static LocalDate toDate(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, COMPACT_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 원천이 숫자를 문자열로도 주고 int 로도 준다(totHshldCo). "1,000호" 같은 꼬리표도 붙는다. */
    public static Integer toInt(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    /**
     * 준공일 "20250627" 에서 연도만 뽑는다.
     * 실데이터에 "00201110" 같은 깨진 값이 섞여 있어서 범위를 벗어나면 버린다.
     */
    public static Integer completionYear(String raw) {
        String value = trimToNull(raw);
        if (value == null || value.length() < 4) {
            return null;
        }
        String head = value.substring(0, 4);
        if (!head.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int year = Integer.parseInt(head);
        return (year >= EARLIEST_COMPLETION_YEAR && year <= LATEST_COMPLETION_YEAR) ? year : null;
    }
}
