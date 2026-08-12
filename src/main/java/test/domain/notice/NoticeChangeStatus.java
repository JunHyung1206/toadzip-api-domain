package test.domain.notice;

public enum NoticeChangeStatus {

    ORIGINAL,
    CORRECTION,
    CANCELLATION;

    /**
     * 마이홈 원천은 sttusNm 으로 상태를 직접 준다. 실데이터 305건에서는 "일반공고"(286) 와 "정정공고"(19) 만 나왔고,
     * 취소공고는 표본에 없었다. 모르는 값이 오면 원문 그대로 보존할 수 없으니 예외로 드러낸다.
     */
    public static NoticeChangeStatus fromStatusName(String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return ORIGINAL;
        }
        return switch (statusName.strip()) {
            case "일반공고" -> ORIGINAL;
            case "정정공고" -> CORRECTION;
            case "취소공고" -> CANCELLATION;
            default -> throw new IllegalArgumentException("처음 보는 공고상태입니다: " + statusName);
        };
    }

    /**
     * LH 원천(15058530)에는 상태 코드가 없어서 공고명 말머리로 판정할 수밖에 없다.
     * 마이홈을 기준 공고 원천으로 쓰면 이 경로는 안 탄다.
     */
    public static NoticeChangeStatus fromNoticeTitle(String title) {
        if (title == null) {
            return ORIGINAL;
        }
        if (title.contains("취소")) {
            return CANCELLATION;
        }
        if (title.contains("정정") || title.contains("변경")) {
            return CORRECTION;
        }
        return ORIGINAL;
    }
}
