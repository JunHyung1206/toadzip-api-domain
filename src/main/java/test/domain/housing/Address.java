package test.domain.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주소 1 : 1 단지. 단지 없이 존재할 이유가 없어서 별도 테이블 대신 값 타입으로 묻었다.
 *
 * <p>법정동코드는 원천이 직접 주지 않는다. 대신 19자리 PNU(필지고유번호)를 주는데 구조가
 * [법정동코드 10 + 필지구분 1 + 본번 4 + 부번 4] 라서 앞 10자리를 잘라 쓴다. 표본 7,317행 전부 19자리였다.
 * 예: 3011013600 1 0190 0001 → 법정동코드 3011013600 (대전 동구 낭월동)
 *
 * <p>PNU 원본도 들고 있는다. 단지 API(HWSPR04)와 공고 API(HWSPR02)를 잇는 유일한 안전한 키다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

    private static final int PNU_LENGTH = 19;
    private static final int LEGAL_DONG_CODE_LENGTH = 10;

    @Column(name = "road_address", nullable = false)
    private String roadAddress;

    @Column(name = "legal_dong_code", length = LEGAL_DONG_CODE_LENGTH)
    private String legalDongCode;

    /** 필지고유번호 19자리. 원천 간 단지 매칭 키. */
    @Column(name = "pnu", length = PNU_LENGTH)
    private String pnu;

    /** 원천 brtcCode. 법정동코드 앞 2자리와 같지만 원천이 따로 주므로 그대로 보관한다. */
    @Column(name = "province_code", length = 10)
    private String provinceCode;

    @Column(name = "province_name", length = 30)
    private String provinceName;

    /** 원천 signguCode. 5자리 시군구코드의 뒤 3자리다(서울 종로 11110 → brtc 11 + signgu 110). */
    @Column(name = "district_code", length = 10)
    private String districtCode;

    @Column(name = "district_name", length = 30)
    private String districtName;

    public Address(String roadAddress,
                   String pnu,
                   String provinceCode,
                   String provinceName,
                   String districtCode,
                   String districtName) {
        if (roadAddress == null || roadAddress.isBlank()) {
            throw new IllegalArgumentException("도로명주소는 필수입니다.");
        }
        this.roadAddress = roadAddress.strip();
        this.pnu = normalizePnu(pnu);
        this.legalDongCode = this.pnu == null ? null : this.pnu.substring(0, LEGAL_DONG_CODE_LENGTH);
        this.provinceCode = provinceCode;
        this.provinceName = provinceName;
        this.districtCode = districtCode;
        this.districtName = districtName;
    }

    /**
     * 19자리 숫자만 PNU 로 받는다. 공고 공급행도 같은 규칙으로 걸러야 카탈로그와 비교가 성립한다
     * ({@code notice_supply.supplied_pnu}).
     */
    public static String normalizePnu(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        boolean valid = stripped.length() == PNU_LENGTH && stripped.chars().allMatch(Character::isDigit);
        return valid ? stripped : null;
    }
}
