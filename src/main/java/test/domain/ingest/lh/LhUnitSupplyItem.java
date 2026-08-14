package test.domain.ingest.lh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 15056765 {@code lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1} 응답의 {@code dsList01} 한 행.
 *
 * <p>2026-08-13 실측: 국민임대 공고 1건으로 확인했을 때 한 {@code PAN_ID} 안에 단지 6곳, 9행이 왔다.
 *
 * <p>{@code LS_GMY}(임대보증금)·{@code RFE}(월임대료)는 <b>문자열 그대로</b> 받는다. API 는 같은 응답의
 * {@code dsList01Nm} 에 "임대보증금(원)", "월임대료(원)" 이라고 스스로 써 주는데, 기록해 둔 실측
 * fixture 에서는 두 값이 {@code "공고문 참조"} 였다. 숫자로 파싱해 버리면 어느 쪽이 왔는지 셀 수가 없어서,
 * 우선 원문을 남기고 숫자가 오는 공고가 얼마나 되는지 확인한 뒤에 숫자 컬럼으로 승격한다.
 * 이 두 값은 <b>주택형별 임대료를 주는 유일한 원천</b>이다 — 마이홈은 단지 단위로만 준다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LhUnitSupplyItem(
        @JsonProperty("SBD_LGO_NM") String complexLabel,
        @JsonProperty("HTY_NNA") String typeName,
        @JsonProperty("DDO_AR") String exclusiveArea,
        @JsonProperty("SPL_AR") String supplyArea,
        @JsonProperty("HSH_CNT") String totalUnitCount,
        @JsonProperty("NOW_HSH_CNT") String suppliedUnitCount,
        @JsonProperty("LS_GMY") String deposit,
        @JsonProperty("RFE") String monthlyRent
) {
}
