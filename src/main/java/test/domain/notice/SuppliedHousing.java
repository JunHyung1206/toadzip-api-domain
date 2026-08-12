package test.domain.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고가 말하는 대상 주택 정보를 공고 시점 그대로 보존한 것.
 *
 * <p>단지 테이블에 붙일 수 있으면 {@link SupplyLine#getComplex()} 로 붙지만, 305행 중 PNU가 있는 행이
 * 147개뿐이라 절반은 못 붙는다. 붙는 경우에도 이건 지우지 않는다. 공고버전이 불변 스냅샷인데
 * 단지 카탈로그는 계속 갱신되므로, 공고가 그때 뭐라고 했는지는 여기 남아야 한다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SuppliedHousing {

    /** 원천 hsmpNm. */
    @Column(name = "supplied_complex_name")
    private String complexName;

    /** 원천 fullAdres. 도로명주소가 아니라 지번이 섞여 온다. */
    @Column(name = "supplied_full_address")
    private String fullAddress;

    /** 원천 pnu. 단지 매칭에 쓴 키를 그대로 남긴다. */
    @Column(name = "supplied_pnu", length = 19)
    private String pnu;

    /** 원천 rnCodeNm. */
    @Column(name = "supplied_road_name", length = 100)
    private String roadName;

    /** 원천 refrnLegaldongNm. 305행 중 6행만 채워진다. */
    @Column(name = "supplied_reference_legal_dong_name", length = 50)
    private String referenceLegalDongName;

    /** 원천 brtcNm. */
    @Column(name = "supplied_province_name", length = 30)
    private String provinceName;

    /** 원천 signguNm. */
    @Column(name = "supplied_district_name", length = 30)
    private String districtName;

    /** 원천 heatMthdNm. */
    @Column(name = "supplied_heating_type_name", length = 30)
    private String heatingTypeName;

    /** 원천 totHshldCo. 원천이 문자열로도 숫자로도 준다. */
    @Column(name = "supplied_total_unit_count")
    private Integer totalUnitCount;

    public SuppliedHousing(String complexName,
                           String fullAddress,
                           String pnu,
                           String roadName,
                           String referenceLegalDongName,
                           String provinceName,
                           String districtName,
                           String heatingTypeName,
                           Integer totalUnitCount) {
        this.complexName = complexName;
        this.fullAddress = fullAddress;
        this.pnu = pnu;
        this.roadName = roadName;
        this.referenceLegalDongName = referenceLegalDongName;
        this.provinceName = provinceName;
        this.districtName = districtName;
        this.heatingTypeName = heatingTypeName;
        this.totalUnitCount = totalUnitCount;
    }

    /** 단지가 안 붙는 행에서 사용자에게 보여줄 유일한 위치. */
    public String regionName() {
        if (provinceName == null || provinceName.isBlank()) {
            return null;
        }
        return districtName == null || districtName.isBlank()
                ? provinceName
                : provinceName + " " + districtName;
    }
}
