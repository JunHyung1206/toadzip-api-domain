package test.domain.ingest.myhome;

import test.domain.housing.SupplyType;
import test.domain.ingest.SourceValues;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum MyHomeRentalType {

    PERMANENT_RENTAL("01", "영구임대", SupplyType.PERMANENT_RENTAL),
    NATIONAL_RENTAL("02", "국민임대", SupplyType.NATIONAL_RENTAL),
    FIFTY_YEAR_RENTAL("03", "50년임대", SupplyType.FIFTY_YEAR_RENTAL),
    TEN_YEAR_RENTAL("05", "10년임대", SupplyType.TEN_YEAR_RENTAL),
    FIVE_YEAR_RENTAL("06", "5년임대", SupplyType.FIVE_YEAR_RENTAL),
    LONG_TERM_JEONSE("07", "장기전세", SupplyType.LONG_TERM_JEONSE),
    HAPPY_HOUSE("10", "행복주택", SupplyType.HAPPY_HOUSE),
    INTEGRATED_PUBLIC_RENTAL("12", "통합공공임대", SupplyType.INTEGRATED_PUBLIC_RENTAL);

    private final String requestCode;
    private final String responseLabel;
    private final SupplyType supplyType;

    MyHomeRentalType(String requestCode, String responseLabel, SupplyType supplyType) {
        this.requestCode = requestCode;
        this.responseLabel = responseLabel;
        this.supplyType = supplyType;
    }

    public String requestCode() {
        return requestCode;
    }

    public String responseLabel() {
        return responseLabel;
    }

    public SupplyType supplyType() {
        return supplyType;
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
