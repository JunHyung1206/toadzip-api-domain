package test.domain.ingest.lh;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import test.domain.ingest.SourceValues;

import java.net.URI;
import java.util.Optional;

/**
 * 마이홈이 준 LH 공고 링크와 {@link LhSupplyInfoTypeResolver} 가 정한 공급정보코드를 묶어
 * 15057999 {@code lhLeaseNoticeDtlInfo1} 호출 파라미터로 옮긴다.
 *
 * <p>{@code panId}, {@code ccrCnntSysDsCd}, {@code uppAisTpCd}, 공급정보코드는 필수다. 하나라도
 * 없으면 호출할 수 없으므로 {@link #from} 이 {@link Optional#empty()} 를 돌려준다.
 * {@code aisTpCd}(공고유형코드)는 링크에 없는 경우가 있어 생략 가능하다.
 */
public record LhNoticeRequest(String panId,
                              String connectionSystemDivisionCode,
                              String upperAnnouncementTypeCode,
                              String announcementTypeCode,
                              String supplyInfoTypeCode) {

    private static final String PAGE_SIZE = "100";
    private static final String PAGE = "1";

    public static Optional<LhNoticeRequest> from(URI detailUrl, String supplyInfoTypeCode) {
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(detailUrl)
                .build()
                .getQueryParams();

        String panId = SourceValues.trimToNull(query.getFirst("panId"));
        String connectionSystemDivisionCode = SourceValues.trimToNull(query.getFirst("ccrCnntSysDsCd"));
        String upperAnnouncementTypeCode = SourceValues.trimToNull(query.getFirst("uppAisTpCd"));
        if (panId == null || connectionSystemDivisionCode == null || upperAnnouncementTypeCode == null
                || supplyInfoTypeCode == null) {
            return Optional.empty();
        }

        String announcementTypeCode = SourceValues.trimToNull(query.getFirst("aisTpCd"));
        return Optional.of(new LhNoticeRequest(panId, connectionSystemDivisionCode,
                upperAnnouncementTypeCode, announcementTypeCode, supplyInfoTypeCode));
    }

    public MultiValueMap<String, String> toParams() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("PAN_ID", panId);
        params.add("CCR_CNNT_SYS_DS_CD", connectionSystemDivisionCode);
        params.add("UPP_AIS_TP_CD", upperAnnouncementTypeCode);
        if (announcementTypeCode != null) {
            params.add("AIS_TP_CD", announcementTypeCode);
        }
        params.add("SPL_INF_TP_CD", supplyInfoTypeCode);
        params.add("PG_SZ", PAGE_SIZE);
        params.add("PAGE", PAGE);
        return params;
    }
}
