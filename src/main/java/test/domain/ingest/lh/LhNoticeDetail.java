package test.domain.ingest.lh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 15057999 {@code lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1} 응답 조각들.
 *
 * <p>LH 는 마이홈과 달리 <b>한 응답에 데이터셋 여러 개</b>를 배열로 담아 준다.
 * 화면 라벨용 {@code ...Nm} 데이터셋은 제외하고 실제 데이터셋만 읽는다.
 *
 * <pre>
 *   dsEtcInfo    기타사항 + 정정/취소 사유
 *   dsAhflInfo   공고 첨부파일 — 공고문 hwp/PDF, 카탈로그
 *   dsSbdAhfl    단지 이미지 — 조감도·배치도·위치도. 단지명이 함께 온다
 * </pre>
 *
 * <p>필드명이 행정표준용어 약어라 여기서 도메인 이름으로 옮긴다
 * ({@code AHFL}=첨부파일, {@code CMN}=공통, {@code LCC_NT}=단지, {@code CRC}=정정).
 */
public final class LhNoticeDetail {

    private LhNoticeDetail() {
    }

    /** {@code dsEtcInfo} — 공고 하나에 한 행. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EtcInfo(
            @JsonProperty("CRC_RSN") String correctionReason,
            @JsonProperty("ETC_CTS") String etcContents
    ) {
    }

    /** {@code dsSplScdl} — 서류 제출과 계약 일정. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(
            @JsonProperty("SBD_LGO_NM") String complexName,
            @JsonProperty("ACP_DTTM") String applicationPeriod,
            @JsonProperty("PPR_SBM_OPE_ANC_DT") String documentTargetAnnouncementDate,
            @JsonProperty("PPR_ACP_ST_DT") String documentSubmissionBeginDate,
            @JsonProperty("PPR_ACP_CLSG_DT") String documentSubmissionEndDate,
            @JsonProperty("CTRT_ST_DT") String contractBeginDate,
            @JsonProperty("CTRT_ED_DT") String contractEndDate
    ) {
    }

    /** {@code dsCtrtPlc} — 방문 접수처와 운영 안내. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reception(
            @JsonProperty("CTRT_PLC_ADR") String address,
            @JsonProperty("CTRT_PLC_DTL_ADR") String detailAddress,
            @JsonProperty("TSK_ST_DTTM") String operationBegin,
            @JsonProperty("TSK_ED_DTTM") String operationEnd,
            @JsonProperty("SIL_OFC_TLNO") String phone,
            @JsonProperty("SIL_OFC_GUD_FCTS") String guidance
    ) {
    }

    /** {@code dsSbd} — 해당 공고 시점의 단지 정보. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ComplexSnapshot(
            @JsonProperty("LCC_NT_NM") String complexName,
            @JsonProperty("LGDN_ADR") String lotAddress,
            @JsonProperty("LGDN_DTL_ADR") String lotDetailAddress,
            @JsonProperty("HSH_CNT") String totalUnitCount,
            @JsonProperty("HTN_FMLA_DESC") String heatingDescription,
            @JsonProperty("DDO_AR") String exclusiveAreaRange,
            @JsonProperty("MVIN_XPC_YM") String expectedMoveInYearMonth
    ) {
    }

    /** {@code dsAhflInfo} — 공고문 원문 등. 65건 전부 hwp·PDF 를 준다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NoticeFile(
            @JsonProperty("SL_PAN_AHFL_DS_CD_NM") String kind,
            @JsonProperty("CMN_AHFL_NM") String name,
            @JsonProperty("AHFL_URL") String url
    ) {
    }

    /** {@code dsSbdAhfl} — 단지 이미지. 구분명 필드 이름만 위와 다르다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ComplexImage(
            @JsonProperty("LS_SPL_INF_UPL_FL_DS_CD_NM") String kind,
            @JsonProperty("CMN_AHFL_NM") String name,
            @JsonProperty("AHFL_URL") String url,
            @JsonProperty("LCC_NT_NM") String complexName
    ) {
    }
}
