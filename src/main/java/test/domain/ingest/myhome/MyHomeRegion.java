package test.domain.ingest.myhome;

import java.util.Objects;
import java.util.regex.Pattern;

public record MyHomeRegion(String brtcCode, String signguCode, String brtcName, String signguName) {

    private static final Pattern BRTC_CODE = Pattern.compile("\\d{2}");
    private static final Pattern SIGNGU_CODE = Pattern.compile("\\d{3}");

    public MyHomeRegion {
        if (brtcCode == null || !BRTC_CODE.matcher(brtcCode).matches()) {
            throw new IllegalArgumentException("brtcCode must be a two-digit code");
        }
        if (signguCode == null || !SIGNGU_CODE.matcher(signguCode).matches()) {
            throw new IllegalArgumentException("signguCode must be a three-digit code");
        }
        Objects.requireNonNull(brtcName, "brtcName must not be null");
        Objects.requireNonNull(signguName, "signguName must not be null");
    }

    public String fullCode() {
        return brtcCode + signguCode;
    }
}
