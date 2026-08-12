package test.domain.ingest.lh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LhNoticeDetailMappingTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("LH 상세 일정·접수처·단지 스냅샷 필드를 도메인 이름으로 매핑한다")
    void mapsSupplementDatasets() {
        JsonNode root = mapper.readTree("""
                {
                  "PPR_SBM_OPE_ANC_DT":"2026.09.07",
                  "PPR_ACP_ST_DT":"2026.09.08",
                  "PPR_ACP_CLSG_DT":"2026.09.14",
                  "CTRT_ST_DT":"2027.01.19",
                  "CTRT_ED_DT":"2027.01.21",
                  "SBD_LGO_NM":"성남금토 A-4블록",
                  "ACP_DTTM":"2026.08.24 10:00 ~ 2026.08.26 16:10",
                  "CTRT_PLC_ADR":"경기도 성남시 분당구 성남대로54번길 3",
                  "CTRT_PLC_DTL_ADR":"1층 105호",
                  "TSK_ST_DTTM":"2026.08.25 10:00",
                  "TSK_ED_DTTM":"2026.08.25 16:00",
                  "SIL_OFC_TLNO":"1600-1004",
                  "SIL_OFC_GUD_FCTS":"고령자 및 장애인 현장접수",
                  "LGDN_ADR":"경기도 성남시 수정구 금토동",
                  "LGDN_DTL_ADR":"417-4",
                  "HSH_CNT":"384",
                  "HTN_FMLA_DESC":"지역난방",
                  "LCC_NT_NM":"성남금토 A-4블록",
                  "DDO_AR":"55.83~55.99",
                  "MVIN_XPC_YM":"2027.11",
                  "SPL_INF_GUD_FCTS":"공급 안내 원문"
                }
                """);

        LhNoticeDetail.Schedule schedule = mapper.convertValue(root, LhNoticeDetail.Schedule.class);
        LhNoticeDetail.Reception reception = mapper.convertValue(root, LhNoticeDetail.Reception.class);
        LhNoticeDetail.ComplexDetail complex = mapper.convertValue(root, LhNoticeDetail.ComplexDetail.class);

        assertThat(schedule.contractEndDate()).isEqualTo("2027.01.21");
        assertThat(schedule.complexName()).isEqualTo("성남금토 A-4블록");
        assertThat(reception.phone()).isEqualTo("1600-1004");
        assertThat(reception.detailAddress()).isEqualTo("1층 105호");
        assertThat(complex.expectedMoveInYearMonth()).isEqualTo("2027.11");
        assertThat(complex.totalUnitCount()).isEqualTo("384");
        assertThat(complex.guidanceText()).isEqualTo("공급 안내 원문");
    }
}
