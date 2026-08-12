package test.domain.housing;

public enum HeatingType {

    INDIVIDUAL("개별"),
    CENTRAL("중앙"),
    DISTRICT("지역");

    private final String sourcePrefix;

    HeatingType(String sourcePrefix) {
        this.sourcePrefix = sourcePrefix;
    }

    /**
     * 원천 값은 7가지였다: 개별난방, 개별가스난방, 개별전기난방, 중앙난방, 지역난방, 지역가스난방, 지역폐열난방.
     * 열원(가스/전기/폐열)까지 나눌 이유가 아직 없어서 앞 두 글자만 본다.
     * 모르는 값은 억지로 넣지 않고 null(=미상)로 둔다.
     */
    public static HeatingType from(String label) {
        if (label == null) {
            return null;
        }
        String stripped = label.strip();
        for (HeatingType type : values()) {
            if (stripped.startsWith(type.sourcePrefix)) {
                return type;
            }
        }
        return null;
    }
}
