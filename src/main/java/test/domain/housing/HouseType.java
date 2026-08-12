package test.domain.housing;

/**
 * 주택유형. 원천은 코드 없이 한글 이름만 준다.
 *
 * <p>표본 7,821행에서 나온 값은 아래 6개가 전부고, 7%는 아예 비어 있었다.
 * 건물 제원(준공일·난방·주차·복도·승강기)이 사실상 아파트에만 있어서 화면 분기가 이 값에 걸린다.
 * {@link SupplyType} 과 같이 enum + 원문 문자열을 함께 둔다.
 */
public enum HouseType {

    APARTMENT("아파트"),
    ROW_HOUSE("연립주택"),
    MULTIPLEX_HOUSE("다세대주택"),
    MULTI_FAMILY_HOUSE("다가구주택"),
    DETACHED_HOUSE("단독주택"),
    OFFICETEL("오피스텔");

    private final String sourceLabel;

    HouseType(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    /** 모르는 값과 빈 값은 null 로 둔다. 원문은 별도 칸에 그대로 남는다. */
    public static HouseType from(String label) {
        if (label == null) {
            return null;
        }
        String stripped = label.strip();
        for (HouseType type : values()) {
            if (type.sourceLabel.equals(stripped)) {
                return type;
            }
        }
        return null;
    }
}
