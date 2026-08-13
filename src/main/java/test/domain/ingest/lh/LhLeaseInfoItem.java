package test.domain.ingest.lh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 15059475 {@code lhLeaseInfo1/lhLeaseInfo1} 응답의 {@code dsList} 한 행. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LhLeaseInfoItem(
        @JsonProperty("ARA_NM") String areaName,
        @JsonProperty("AIS_TP_CD_NM") String supplyTypeName,
        @JsonProperty("SBD_LGO_NM") String complexLabel,
        @JsonProperty("SUM_HSH_CNT") String complexTotalUnitCount,
        @JsonProperty("DDO_AR") String exclusiveArea,
        @JsonProperty("HSH_CNT") String totalUnitCount
) {
}
