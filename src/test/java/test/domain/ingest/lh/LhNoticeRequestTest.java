package test.domain.ingest.lh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LhNoticeRequestTest {

    private static final URI FULL_URI = URI.create("https://apply.lh.or.kr/selectWrtancInfo.do"
            + "?panId=2015122300020512&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10");

    @Test
    @DisplayName("aisTpCd 가 없어도 나머지 필수 코드만으로 요청을 만든다")
    void acceptsMissingOptionalAnnouncementTypeCode() {
        URI uri = URI.create("https://apply.lh.or.kr/selectWrtancInfo.do"
                + "?panId=2015122300020512&ccrCnntSysDsCd=03&uppAisTpCd=06");

        LhNoticeRequest request = LhNoticeRequest.from(uri, "063").orElseThrow();

        assertThat(request.toParams()).containsEntry("PAN_ID", List.of("2015122300020512"));
        assertThat(request.toParams()).doesNotContainKey("AIS_TP_CD");
        assertThat(request.toParams()).containsEntry("SPL_INF_TP_CD", List.of("063"));
    }

    @Test
    @DisplayName("링크에 aisTpCd 가 있으면 요청에 그대로 담는다")
    void includesAnnouncementTypeCodeWhenPresent() {
        LhNoticeRequest request = LhNoticeRequest.from(FULL_URI, "060").orElseThrow();

        assertThat(request.toParams()).containsEntry("AIS_TP_CD", List.of("10"));
        assertThat(request.toParams()).containsEntry("PG_SZ", List.of("100"));
        assertThat(request.toParams()).containsEntry("PAGE", List.of("1"));
    }

    @Test
    @DisplayName("panId 가 없으면 빈 값을 돌려준다")
    void requiresPanId() {
        URI uri = URI.create("https://apply.lh.or.kr/selectWrtancInfo.do"
                + "?ccrCnntSysDsCd=03&uppAisTpCd=06");

        assertThat(LhNoticeRequest.from(uri, "063")).isEmpty();
    }

    @Test
    @DisplayName("ccrCnntSysDsCd 가 없으면 빈 값을 돌려준다")
    void requiresConnectionSystemDivisionCode() {
        URI uri = URI.create("https://apply.lh.or.kr/selectWrtancInfo.do"
                + "?panId=2015122300020512&uppAisTpCd=06");

        assertThat(LhNoticeRequest.from(uri, "063")).isEmpty();
    }

    @Test
    @DisplayName("uppAisTpCd 가 없으면 빈 값을 돌려준다")
    void requiresUpperAnnouncementTypeCode() {
        URI uri = URI.create("https://apply.lh.or.kr/selectWrtancInfo.do"
                + "?panId=2015122300020512&ccrCnntSysDsCd=03");

        assertThat(LhNoticeRequest.from(uri, "063")).isEmpty();
    }
}
