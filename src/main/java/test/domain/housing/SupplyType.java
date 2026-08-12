package test.domain.housing;

/**
 * 공급유형. 원천은 코드 없이 한글 이름만 준다.
 *
 * <p>표본 7,821행에서 나온 값은 아래 10개가 전부였다. 값이 유한하고 화면 필터의 1순위 기준이라
 * 문자열로 두면 오타나 원천 표기 변경이 그대로 통과한다. {@link HeatingType} 과 같은 방식으로
 * enum 을 두되 <b>원문 문자열도 함께 보관</b>한다 — 모르는 값이 와도 적재가 죽지 않고 원문이 남는다.
 */
public enum SupplyType {

    PURCHASED_RENTAL("매입임대"),
    NATIONAL_RENTAL("국민임대"),
    PERMANENT_RENTAL("영구임대"),
    HAPPY_HOUSE("행복주택"),
    INTEGRATED_PUBLIC_RENTAL("통합공공임대"),
    JEONSE_RENTAL("전세임대"),
    LONG_TERM_JEONSE("장기전세"),
    FIVE_YEAR_RENTAL("5년임대"),
    TEN_YEAR_RENTAL("10년임대"),
    FIFTY_YEAR_RENTAL("50년임대");

    private final String sourceLabel;

    SupplyType(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    /** 이 서비스가 카탈로그와 모집공고로 다루는 건설형 공공임대인가. */
    public boolean isConstructionRental() {
        return switch (this) {
            case NATIONAL_RENTAL,
                 PERMANENT_RENTAL,
                 HAPPY_HOUSE,
                 INTEGRATED_PUBLIC_RENTAL,
                 LONG_TERM_JEONSE,
                 FIVE_YEAR_RENTAL,
                 TEN_YEAR_RENTAL,
                 FIFTY_YEAR_RENTAL -> true;
            case PURCHASED_RENTAL, JEONSE_RENTAL -> false;
        };
    }

    /** 모르는 값은 억지로 넣지 않고 null 로 둔다. 원문은 별도 칸에 그대로 남는다. */
    public static SupplyType from(String label) {
        if (label == null) {
            return null;
        }
        String stripped = label.strip();
        for (SupplyType type : values()) {
            if (type.sourceLabel.equals(stripped)) {
                return type;
            }
        }
        return null;
    }
}
