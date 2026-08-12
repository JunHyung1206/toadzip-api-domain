package test.domain.ingest.myhome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 15108420 HWSPR02 의 한 행 = 공고 × 공급 단위(공급행). 임대공고(rsdtRcritNtcList)만 받는다.
 *
 * <p>행이 2개 이상인 공고 59건으로 확인한 필드 레벨:
 * <ul>
 *   <li><b>공고 단위</b>(값이 안 갈림) — sttusNm, pblancNm, suplyInsttNm, houseTyNm, suplyTyNm,
 *       beforePblancId, rcritPblancDe, przwnerPresnatnDe, refrnc, url, beginDe, endDe</li>
 *   <li><b>행 단위</b>(행마다 다름) — houseSn, pcUrl, mobileUrl, hsmpNm, brtcNm, signguNm, fullAdres,
 *       rnCodeNm, pnu, heatMthdNm, totHshldCo, sumSuplyCo, rentGtn, enty, surlus, mtRntchrg</li>
 * </ul>
 *
 * <p>totHshldCo 는 원천이 문자열로도 숫자로도 줘서 String 으로 받아 뒤에서 파싱한다.
 * suplyHoCo(공급호수)와 prtpay(중도금)는 건설임대에서 각각 0/70, 늘 0이라 받지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MyHomeNoticeItem(
        String pblancId,
        Integer houseSn,
        String sttusNm,
        String pblancNm,
        String suplyInsttNm,
        String houseTyNm,
        String suplyTyNm,
        String beforePblancId,
        String rcritPblancDe,
        String przwnerPresnatnDe,
        String beginDe,
        String endDe,
        String refrnc,
        String url,
        String pcUrl,
        String mobileUrl,
        String hsmpNm,
        String brtcNm,
        String signguNm,
        String fullAdres,
        String rnCodeNm,
        String refrnLegaldongNm,
        String pnu,
        String heatMthdNm,
        String totHshldCo,
        Integer sumSuplyCo,
        Long rentGtn,
        Long enty,
        Long surlus,
        Long mtRntchrg
) {
}
