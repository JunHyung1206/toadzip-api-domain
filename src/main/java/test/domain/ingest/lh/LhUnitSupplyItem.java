package test.domain.ingest.lh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 15056765 {@code lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1} 응답의 {@code dsList01} 한 행.
 *
 * <p>2026-08-13 실측: 국민임대 공고 1건으로 확인했을 때 한 {@code PAN_ID} 안에 단지 6곳, 9행이 왔다.
 * {@code RFE}(월임대료)·{@code LS_GMY}(임대보증금)는 그 실측에서 전부 "공고문 참조" 문자열이라 받지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LhUnitSupplyItem(
        @JsonProperty("SBD_LGO_NM") String complexLabel,
        @JsonProperty("HTY_NNA") String typeName,
        @JsonProperty("DDO_AR") String exclusiveArea,
        @JsonProperty("SPL_AR") String supplyArea,
        @JsonProperty("HSH_CNT") String totalUnitCount,
        @JsonProperty("NOW_HSH_CNT") String suppliedUnitCount
) {
}
