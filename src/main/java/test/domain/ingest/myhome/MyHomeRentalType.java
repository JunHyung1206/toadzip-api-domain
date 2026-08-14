package test.domain.ingest.myhome;

import test.domain.ingest.SourceValues;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 15108420 이 {@code suplyTy} 로 여는 공급유형 필터 목록. 원천이 코드로만 필터를 받아서, 어느 코드를
 * 돌지 정하려면 이 표가 필요하다.
 *
 * <p>공급유형을 저장할 때 쓰던 enum 은 없앴지만 이건 남는다 — 저장 값이 아니라 <b>요청 파라미터 목록</b>이다.
 * {@link #responseLabel} 은 응답의 {@code suplyTyNm} 원문과 같은 값이라 그대로 공고에 저장된다.
 */
public enum MyHomeRentalType {

    PERMANENT_RENTAL("01", "영구임대"),
    NATIONAL_RENTAL("02", "국민임대"),
    FIFTY_YEAR_RENTAL("03", "50년임대"),
    TEN_YEAR_RENTAL("05", "10년임대"),
    FIVE_YEAR_RENTAL("06", "5년임대"),
    LONG_TERM_JEONSE("07", "장기전세"),
    HAPPY_HOUSE("10", "행복주택"),
    INTEGRATED_PUBLIC_RENTAL("12", "통합공공임대");

    private final String requestCode;
    private final String responseLabel;

    MyHomeRentalType(String requestCode, String responseLabel) {
        this.requestCode = requestCode;
        this.responseLabel = responseLabel;
    }

    public String requestCode() {
        return requestCode;
    }

    public String responseLabel() {
        return responseLabel;
    }

    public static List<String> requestCodes() {
        return Arrays.stream(values()).map(MyHomeRentalType::requestCode).toList();
    }

    public static Optional<MyHomeRentalType> fromResponseLabel(String rawLabel) {
        String label = SourceValues.trimToNull(rawLabel);
        if (label == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.responseLabel.equals(label)).findFirst();
    }
}
