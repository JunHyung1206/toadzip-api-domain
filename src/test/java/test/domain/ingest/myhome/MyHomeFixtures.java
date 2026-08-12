package test.domain.ingest.myhome;

import test.domain.ingest.OpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 실제 원천 응답을 그대로 잘라 붙였다. 필드명·타입·빈 값 처리까지 진짜 데이터로 검증하려는 것이라
 * 값을 임의로 다듬지 않았다(totHshldCo 가 숫자로 오는 것, suplyHoCo·houseTyNm 이 "" 인 것 등).
 */
final class MyHomeFixtures {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final String LIST_POINTER = "/response/body/item";

    private MyHomeFixtures() {
    }

    /**
     * HWSPR04 — 서울 노원구 "중계센트럴파크"(SH공사). 건설임대 아파트라 이 카탈로그가 담는 대상이다.
     *
     * <p>주택형이 `49A`, `49A-2`, `49A-S`, `49B` 처럼 아파트 형식이고 호실 번호가 섞이지 않는다.
     * 그리고 <b>`49A` 가 국민임대와 장기전세로 두 번 온다 — 주택형명도 면적도 같고 공급유형만 다르다.</b>
     * 자연키에 공급유형이 왜 필요한지 보여 주는 실제 행이다.
     */
    static List<MyHomeComplexItem> constructedComplexItems() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"5","numOfRows":"5","pageNo":"1","item":[
                  {"hsmpSn":30965288,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"350","signguNm":"노원구","hsmpNm":"중계센트럴파크",
                   "rnAdres":"서울특별시 노원구 덕릉로70가길 21","pnu":"1135010500113220000",
                   "competDe":"20160117","hshldCo":115,"suplyTyNm":"국민임대","styleNm":"49A",
                   "suplyPrvuseAr":49.9,"suplyCmnuseAr":22.68,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"혼합식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":472,"bassRentGtn":63850000,"bassMtRntchrg":377200,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":30965288,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"350","signguNm":"노원구","hsmpNm":"중계센트럴파크",
                   "rnAdres":"서울특별시 노원구 덕릉로70가길 21","pnu":"1135010500113220000",
                   "competDe":"20160117","hshldCo":114,"suplyTyNm":"장기전세","styleNm":"49A",
                   "suplyPrvuseAr":49.9,"suplyCmnuseAr":22.68,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"혼합식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":472,"bassRentGtn":311220000,"bassMtRntchrg":0,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":30965288,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"350","signguNm":"노원구","hsmpNm":"중계센트럴파크",
                   "rnAdres":"서울특별시 노원구 덕릉로70가길 21","pnu":"1135010500113220000",
                   "competDe":"20160117","hshldCo":114,"suplyTyNm":"장기전세","styleNm":"49A-2",
                   "suplyPrvuseAr":49.73,"suplyCmnuseAr":22.91,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"혼합식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":472,"bassRentGtn":311220000,"bassMtRntchrg":0,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":30965288,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"350","signguNm":"노원구","hsmpNm":"중계센트럴파크",
                   "rnAdres":"서울특별시 노원구 덕릉로70가길 21","pnu":"1135010500113220000",
                   "competDe":"20160117","hshldCo":115,"suplyTyNm":"국민임대","styleNm":"49A-S",
                   "suplyPrvuseAr":49.87,"suplyCmnuseAr":22.7,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"혼합식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":472,"bassRentGtn":63850000,"bassMtRntchrg":377200,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":30965288,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"350","signguNm":"노원구","hsmpNm":"중계센트럴파크",
                   "rnAdres":"서울특별시 노원구 덕릉로70가길 21","pnu":"1135010500113220000",
                   "competDe":"20160117","hshldCo":115,"suplyTyNm":"국민임대","styleNm":"49B",
                   "suplyPrvuseAr":49.49,"suplyCmnuseAr":22.01,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"혼합식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":472,"bassRentGtn":63850000,"bassMtRntchrg":377200,"bassCnvrsGtnLmt":0}
                 ]}}}
                """, MyHomeComplexItem.class);
    }

    /**
     * HWSPR04 — 서울 종로구 <b>매입임대</b>. 이제 적재 대상이 아니라 걸러진다.
     * 주택형 자리에 면적 반올림값이 오고 난방·주차·준공일이 전부 비어 있는, 매입임대의 전형이다.
     */
    static List<MyHomeComplexItem> purchasedComplexItems() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"379","numOfRows":"3","pageNo":"1","item":[
                  {"hsmpSn":31846097,"insttNm":"LH서울","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"110","signguNm":"종로구","hsmpNm":"서울특별시 종로구",
                   "rnAdres":"서울특별시 종로구 평창문화로 67","pnu":"1111018300101870001","competDe":"",
                   "hshldCo":20,"suplyTyNm":"매입임대","styleNm":"36","suplyPrvuseAr":36.66,
                   "suplyCmnuseAr":10.86,"houseTyNm":"","heatMthdDetailNm":"","buldStleNm":"",
                   "elvtrInstlAtNm":"","parkngCo":0,"bassRentGtn":6482000,"bassMtRntchrg":496090,
                   "bassCnvrsGtnLmt":0},
                  {"hsmpSn":31846097,"insttNm":"LH서울","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"110","signguNm":"종로구","hsmpNm":"서울특별시 종로구",
                   "rnAdres":"서울특별시 종로구 평창문화로 67","pnu":"1111018300101870001","competDe":"",
                   "hshldCo":20,"suplyTyNm":"매입임대","styleNm":"36","suplyPrvuseAr":36.74,
                   "suplyCmnuseAr":11.87,"houseTyNm":"","heatMthdDetailNm":"","buldStleNm":"",
                   "elvtrInstlAtNm":"","parkngCo":0,"bassRentGtn":6620000,"bassMtRntchrg":471610,
                   "bassCnvrsGtnLmt":0},
                  {"hsmpSn":31846097,"insttNm":"LH서울","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"110","signguNm":"종로구","hsmpNm":"서울특별시 종로구",
                   "rnAdres":"서울특별시 종로구 평창문화로 67","pnu":"1111018300101870001","competDe":"",
                   "hshldCo":20,"suplyTyNm":"매입임대","styleNm":"36","suplyPrvuseAr":36.83,
                   "suplyCmnuseAr":10.94,"houseTyNm":"","heatMthdDetailNm":"","buldStleNm":"",
                   "elvtrInstlAtNm":"","parkngCo":0,"bassRentGtn":6482000,"bassMtRntchrg":510100,
                   "bassCnvrsGtnLmt":0}
                 ]}}}
                """, MyHomeComplexItem.class);
    }

    /**
     * HWSPR04 — <b>공급유형만 건설임대인 매입임대.</b> 라벨로는 안 걸러지는 행들이다.
     *
     * <p>앞 둘은 성남시 "매입임대주택" — 단지명과 주택형 자리에는 매입임대라고 적혀 있는데 정작
     * {@code suplyTyNm} 은 '10년임대'다. 셋째는 대구 노블힐즈4로, 같은 hsmpSn 에 '매입임대' 행이
     * 따로 오는 건물인데 이 행만 '장기전세'로 온다. 셋 다 준공일·난방·주차가 비어 있다.
     */
    static List<MyHomeComplexItem> purchasedItemsUnderConstructedLabel() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"3","numOfRows":"3","pageNo":"1","item":[
                  {"hsmpSn":30485314,"insttNm":"성남시","brtcCode":"41","brtcNm":"경기도",
                   "signguCode":"131","signguNm":"성남시 수정구","hsmpNm":"매입임대주택",
                   "rnAdres":"경기도 성남시 수정구 탄리로96번길 24","pnu":"4113110200133130000",
                   "competDe":"","hshldCo":1,"suplyTyNm":"10년임대","styleNm":"매입임대",
                   "suplyPrvuseAr":48.96,"suplyCmnuseAr":0,"houseTyNm":"다세대주택",
                   "heatMthdDetailNm":"","buldStleNm":"","elvtrInstlAtNm":"","parkngCo":0,
                   "bassRentGtn":19200000,"bassMtRntchrg":0,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":30485313,"insttNm":"성남시","brtcCode":"41","brtcNm":"경기도",
                   "signguCode":"131","signguNm":"성남시 수정구","hsmpNm":"매입임대주택",
                   "rnAdres":"경기도 성남시 수정구 탄리로8번길 10-1","pnu":"4113110100169240000",
                   "competDe":"","hshldCo":1,"suplyTyNm":"10년임대","styleNm":"매입임대",
                   "suplyPrvuseAr":59.72,"suplyCmnuseAr":0,"houseTyNm":"다세대주택",
                   "heatMthdDetailNm":"","buldStleNm":"","elvtrInstlAtNm":"","parkngCo":0,
                   "bassRentGtn":28350000,"bassMtRntchrg":0,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":1058,"insttNm":"대구도시개발공사","brtcCode":"27","brtcNm":"대구광역시",
                   "signguCode":"230","signguNm":"북구","hsmpNm":"노블힐즈4",
                   "rnAdres":"대구광역시 북구 대학로9길 62-2","pnu":"2723011100112460002",
                   "competDe":"","hshldCo":8,"suplyTyNm":"장기전세","styleNm":"1",
                   "suplyPrvuseAr":19.52,"suplyCmnuseAr":2.84,"houseTyNm":"다가구주택",
                   "heatMthdDetailNm":"","buldStleNm":"","elvtrInstlAtNm":"","parkngCo":0,
                   "bassRentGtn":1697000,"bassMtRntchrg":43150,"bassCnvrsGtnLmt":0}
                 ]}}}
                """, MyHomeComplexItem.class);
    }

    /**
     * HWSPR04 — 인천 남동구 "만부마을 행복주택". <b>아파트가 아닌 건설임대</b>다.
     * 다세대주택이지만 준공일·난방·복도·승강기·주차가 다 차 있어서 지어진 단지인 게 드러난다.
     */
    static List<MyHomeComplexItem> constructedMultiplexItems() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"2","numOfRows":"2","pageNo":"1","item":[
                  {"hsmpSn":31392787,"insttNm":"인천도시공사","brtcCode":"28","brtcNm":"인천광역시",
                   "signguCode":"200","signguNm":"남동구","hsmpNm":"만부마을 행복주택",
                   "rnAdres":"인천광역시 남동구 만수로 120","pnu":"2820010300100010172",
                   "competDe":"20201230","hshldCo":9,"suplyTyNm":"행복주택","styleNm":"23",
                   "suplyPrvuseAr":23.67,"suplyCmnuseAr":22.42,"houseTyNm":"다세대주택",
                   "heatMthdDetailNm":"개별난방","buldStleNm":"복도식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":9,"bassRentGtn":26588000,"bassMtRntchrg":61830,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":31392787,"insttNm":"인천도시공사","brtcCode":"28","brtcNm":"인천광역시",
                   "signguCode":"200","signguNm":"남동구","hsmpNm":"만부마을 행복주택",
                   "rnAdres":"인천광역시 남동구 만수로 120","pnu":"2820010300100010172",
                   "competDe":"20201230","hshldCo":9,"suplyTyNm":"행복주택","styleNm":"23B",
                   "suplyPrvuseAr":23.67,"suplyCmnuseAr":22.42,"houseTyNm":"다세대주택",
                   "heatMthdDetailNm":"개별난방","buldStleNm":"복도식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":9,"bassRentGtn":5678000,"bassMtRntchrg":179600,"bassCnvrsGtnLmt":0}
                 ]}}}
                """, MyHomeComplexItem.class);
    }

    /**
     * HWSPR04 — 서울 강남 대치푸르지오써밋. 같은 단지·같은 51A·같은 면적인데 공급유형이 국민임대/행복주택으로
     * 갈리고 임대조건과 세대수가 다르다. 주택형 자연키에 공급유형이 왜 필요한지 보여 주는 실제 행이다.
     */
    static List<MyHomeComplexItem> complexItemsWithTwoSupplyTypes() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"2","numOfRows":"2","pageNo":"1","item":[
                  {"hsmpSn":31696890,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"680","signguNm":"강남구","hsmpNm":"대치푸르지오써밋",
                   "rnAdres":"서울특별시 강남구 삼성로72길 12","pnu":"1168010600109630000",
                   "competDe":"20230605","hshldCo":10,"suplyTyNm":"국민임대","styleNm":"51A",
                   "suplyPrvuseAr":51.7377,"suplyCmnuseAr":18.5038,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"계단식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":547,"bassRentGtn":125070000,"bassMtRntchrg":211600,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":31696890,"insttNm":"SH공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"680","signguNm":"강남구","hsmpNm":"대치푸르지오써밋",
                   "rnAdres":"서울특별시 강남구 삼성로72길 12","pnu":"1168010600109630000",
                   "competDe":"20230605","hshldCo":8,"suplyTyNm":"행복주택","styleNm":"51A",
                   "suplyPrvuseAr":51.7377,"suplyCmnuseAr":18.5038,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"계단식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":547,"bassRentGtn":196800000,"bassMtRntchrg":771000,"bassCnvrsGtnLmt":0}
                 ]}}}
                """, MyHomeComplexItem.class);
    }

    /** HWSPR04 — {@link #noticeItems()} 의 공급행과 PNU 가 일치하는 단지 둘. 재매칭 확인용. */
    static List<MyHomeComplexItem> complexItemsMatchingNotice() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"2","numOfRows":"2","pageNo":"1","item":[
                  {"hsmpSn":31500001,"insttNm":"LH경기북부","brtcCode":"41","brtcNm":"경기도",
                   "signguCode":"310","signguNm":"구리시","hsmpNm":"구리수택 행복주택",
                   "rnAdres":"경기도 구리시 체육관로74번길 67","pnu":"4131010500108520000",
                   "competDe":"20210330","hshldCo":394,"suplyTyNm":"행복주택","styleNm":"26",
                   "suplyPrvuseAr":26.7,"suplyCmnuseAr":10.1,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"개별난방","buldStleNm":"계단식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":300,"bassRentGtn":37224000,"bassMtRntchrg":156000,"bassCnvrsGtnLmt":0},
                  {"hsmpSn":31500002,"insttNm":"LH경기북부","brtcCode":"41","brtcNm":"경기도",
                   "signguCode":"360","signguNm":"남양주시","hsmpNm":"별가람마을 1-8단지",
                   "rnAdres":"경기도 남양주시 순화궁로 458-58","pnu":"4136011100108220000",
                   "competDe":"20190815","hshldCo":872,"suplyTyNm":"행복주택","styleNm":"36",
                   "suplyPrvuseAr":36.32,"suplyCmnuseAr":13.5,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"혼합식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":700,"bassRentGtn":22896000,"bassMtRntchrg":103000,"bassCnvrsGtnLmt":0}
                 ]}}}
                """, MyHomeComplexItem.class);
    }

    /** 새 공급유형은 아파트여도 허용 목록에 올리기 전까지 자동 적재하지 않는다. */
    static List<MyHomeComplexItem> unknownSupplyTypeComplexItems() {
        return parse("""
                {"response":{"body":{"item":[
                  {"hsmpSn":39999999,"insttNm":"서울주택도시공사","brtcCode":"11","brtcNm":"서울특별시",
                   "signguCode":"680","signguNm":"강남구","hsmpNm":"미등록 임대단지",
                   "rnAdres":"서울특별시 강남구 테스트로 1","pnu":"1168010100100010001",
                   "competDe":"20260101","hshldCo":10,"suplyTyNm":"청년안심주택","styleNm":"39A",
                   "suplyPrvuseAr":39.0,"suplyCmnuseAr":15.0,"houseTyNm":"아파트",
                   "heatMthdDetailNm":"지역난방","buldStleNm":"계단식","elvtrInstlAtNm":"전체동 설치",
                   "parkngCo":10,"bassRentGtn":10000000,"bassMtRntchrg":100000,"bassCnvrsGtnLmt":0}
                ]}}}
                """, MyHomeComplexItem.class);
    }

    /**
     * HWSPR02 — 원공고 20965(일반공고)와 그 정정공고 20989.
     * 응답 순서는 최신(정정공고)이 먼저다. 적재가 순서를 다시 잡는지 보려고 그대로 뒀다.
     */
    static List<MyHomeNoticeItem> noticeItems() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"3","numOfRows":"3","pageNo":"1","item":[
                  {"pblancId":"20989","houseSn":1,"sttusNm":"정정공고",
                   "pblancNm":"구리,남양주시 행복주택 예비입주자모집(26.08.05공고)","suplyInsttNm":"LH",
                   "houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"20965",
                   "rcritPblancDe":"20260805","przwnerPresnatnDe":"20261120","suplyHoCo":"",
                   "refrnc":"LH 콜센터 : 1600-1004 (평일 : 09:00 ~ 18:00)",
                   "url":"https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300020536",
                   "pcUrl":"https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=1",
                   "mobileUrl":"https://m.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=1",
                   "hsmpNm":"구리수택","brtcNm":"경기도","signguNm":"구리시",
                   "fullAdres":"경기도 구리시 체육관로74번길 67 ","rnCodeNm":"체육관로74번길",
                   "refrnLegaldongNm":"","pnu":"4131010500108520000","heatMthdNm":"개별난방",
                   "totHshldCo":394,"sumSuplyCo":50,"rentGtn":37224000,"enty":1862000,"prtpay":0,
                   "surlus":35362000,"mtRntchrg":156000,"beginDe":"20260818","endDe":"20260820"},
                  {"pblancId":"20989","houseSn":3,"sttusNm":"정정공고",
                   "pblancNm":"구리,남양주시 행복주택 예비입주자모집(26.08.05공고)","suplyInsttNm":"LH",
                   "houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"20965",
                   "rcritPblancDe":"20260805","przwnerPresnatnDe":"20261120","suplyHoCo":"",
                   "refrnc":"LH 콜센터 : 1600-1004 (평일 : 09:00 ~ 18:00)",
                   "url":"https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300020536",
                   "pcUrl":"https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=3",
                   "mobileUrl":"https://m.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=3",
                   "hsmpNm":"남양주별내 A24BL","brtcNm":"경기도","signguNm":"남양주시",
                   "fullAdres":"경기도 남양주시 순화궁로 458-58 ","rnCodeNm":"순화궁로",
                   "refrnLegaldongNm":"","pnu":"4136011100108220000","heatMthdNm":"지역난방",
                   "totHshldCo":872,"sumSuplyCo":117,"rentGtn":22896000,"enty":1115000,"prtpay":0,
                   "surlus":21751000,"mtRntchrg":103000,"beginDe":"20260818","endDe":"20260820"},
                  {"pblancId":"20965","houseSn":1,"sttusNm":"일반공고",
                   "pblancNm":"구리,남양주시 행복주택 예비입주자모집(26.08.05공고)","suplyInsttNm":"LH",
                   "houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"",
                   "rcritPblancDe":"20260805","przwnerPresnatnDe":"20261120","suplyHoCo":"",
                   "refrnc":"LH 콜센터 : 1600-1004 (평일 : 09:00 ~ 18:00)",
                   "url":"https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=2015122300020519",
                   "pcUrl":"https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20965&houseSn=1",
                   "mobileUrl":"https://m.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20965&houseSn=1",
                   "hsmpNm":"구리수택","brtcNm":"경기도","signguNm":"구리시",
                   "fullAdres":"경기도 구리시 체육관로74번길 67 ","rnCodeNm":"체육관로74번길",
                   "refrnLegaldongNm":"","pnu":"4131010500108520000","heatMthdNm":"개별난방",
                   "totHshldCo":394,"sumSuplyCo":50,"rentGtn":37224000,"enty":1862000,"prtpay":0,
                   "surlus":35362000,"mtRntchrg":156000,"beginDe":"20260811","endDe":"20260813"}
                 ]}}}
                """, MyHomeNoticeItem.class);
    }

    /** 전세임대처럼 대상 단지가 정해지지 않은 공고. pnu·hsmpNm·fullAdres 가 통째로 비어 있다. */
    static List<MyHomeNoticeItem> noticeItemsWithoutComplex() {
        return parse("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},
                 "body":{"totalCount":"2","numOfRows":"2","pageNo":"1","item":[
                  {"pblancId":"20976","houseSn":0,"sttusNm":"일반공고",
                   "pblancNm":"2026 다자녀 전세임대 입주자 수시모집 공고","suplyInsttNm":"LH",
                   "houseTyNm":"다가구주택","suplyTyNm":"전세임대","beforePblancId":"",
                   "rcritPblancDe":"20260701","przwnerPresnatnDe":"20261231","suplyHoCo":"1170호",
                   "refrnc":"LH 콜센터 : 1600-1004","url":"https://apply.lh.or.kr/",
                   "pcUrl":"https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20976",
                   "mobileUrl":"https://m.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20976",
                   "hsmpNm":"","brtcNm":"경상남도","signguNm":"통영시","fullAdres":"","rnCodeNm":"",
                   "refrnLegaldongNm":"","pnu":"","heatMthdNm":"","totHshldCo":"","sumSuplyCo":81,
                   "rentGtn":0,"enty":0,"prtpay":0,"surlus":0,"mtRntchrg":0,
                   "beginDe":"20260701","endDe":"20261231"},
                  {"pblancId":"20976","houseSn":0,"sttusNm":"일반공고",
                   "pblancNm":"2026 다자녀 전세임대 입주자 수시모집 공고","suplyInsttNm":"LH",
                   "houseTyNm":"다가구주택","suplyTyNm":"전세임대","beforePblancId":"",
                   "rcritPblancDe":"20260701","przwnerPresnatnDe":"20261231","suplyHoCo":"1170호",
                   "refrnc":"LH 콜센터 : 1600-1004","url":"https://apply.lh.or.kr/",
                   "pcUrl":"https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20976",
                   "mobileUrl":"https://m.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20976",
                   "hsmpNm":"","brtcNm":"경상남도","signguNm":"양산시","fullAdres":"","rnCodeNm":"",
                   "refrnLegaldongNm":"","pnu":"","heatMthdNm":"","totHshldCo":"","sumSuplyCo":78,
                   "rentGtn":0,"enty":0,"prtpay":0,"surlus":0,"mtRntchrg":0,
                   "beginDe":"20260701","endDe":"20261231"}
                 ]}}}
                """, MyHomeNoticeItem.class);
    }

    /** 행복주택 공고 안에 정상 공급행과 PNU가 깨진 공급행이 함께 온 경우. */
    static List<MyHomeNoticeItem> noticeItemsWithInvalidSupplyLine() {
        return parse("""
                {"response":{"body":{"item":[
                  {"pblancId":"30001","houseSn":1,"sttusNm":"일반공고","pblancNm":"행복주택 모집",
                   "suplyInsttNm":"LH","houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"",
                   "rcritPblancDe":"20260801","przwnerPresnatnDe":"20261001","refrnc":"1600-1004",
                   "url":"https://apply.lh.or.kr/?panId=valid","pcUrl":"https://myhome/1","mobileUrl":"https://m.myhome/1",
                   "hsmpNm":"정상단지","brtcNm":"경기도","signguNm":"성남시","fullAdres":"경기도 성남시 테스트로 1",
                   "rnCodeNm":"테스트로","refrnLegaldongNm":"테스트동","pnu":"4113111600104160001",
                   "heatMthdNm":"지역난방","totHshldCo":"100","sumSuplyCo":10,"rentGtn":10000000,
                   "enty":1000000,"surlus":9000000,"mtRntchrg":100000,"beginDe":"20260810","endDe":"20260812"},
                  {"pblancId":"30001","houseSn":2,"sttusNm":"일반공고","pblancNm":"행복주택 모집",
                   "suplyInsttNm":"LH","houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"",
                   "rcritPblancDe":"20260801","przwnerPresnatnDe":"20261001","refrnc":"1600-1004",
                   "url":"https://apply.lh.or.kr/?panId=valid","pcUrl":"https://myhome/2","mobileUrl":"https://m.myhome/2",
                   "hsmpNm":"깨진단지","brtcNm":"경기도","signguNm":"성남시","fullAdres":"경기도 성남시 테스트로 2",
                   "pnu":"INVALID","sumSuplyCo":5,"rentGtn":10000000,"enty":1000000,"surlus":9000000,
                   "mtRntchrg":100000,"beginDe":"20260810","endDe":"20260812"}
                ]}}}
                """, MyHomeNoticeItem.class);
    }

    /** 같은 공고 ID 안에 허용 유형과 비지원 유형이 섞인 깨진 원천 응답. */
    static List<MyHomeNoticeItem> noticeItemsWithMixedSupplyTypes() {
        return parse("""
                {"response":{"body":{"item":[
                  {"pblancId":"30003","houseSn":1,"sttusNm":"일반공고","pblancNm":"혼합 공급유형 공고",
                   "suplyInsttNm":"LH","houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"",
                   "rcritPblancDe":"20260801","url":"https://apply.lh.or.kr/?panId=mixed",
                   "hsmpNm":"행복단지","fullAdres":"경기도 성남시 테스트로 1",
                   "pnu":"4113111600104160001","sumSuplyCo":10,"beginDe":"20260810","endDe":"20260812"},
                  {"pblancId":"30003","houseSn":2,"sttusNm":"일반공고","pblancNm":"혼합 공급유형 공고",
                   "suplyInsttNm":"LH","houseTyNm":"다가구주택","suplyTyNm":"전세임대","beforePblancId":"",
                   "rcritPblancDe":"20260801","url":"https://apply.lh.or.kr/?panId=mixed",
                   "hsmpNm":"전세 대상","fullAdres":"경기도 성남시 테스트로 2",
                   "pnu":"4113111600104160002","sumSuplyCo":5,"beginDe":"20260810","endDe":"20260812"}
                ]}}}
                """, MyHomeNoticeItem.class);
    }

    /** 공급유형은 허용되지만 대상 주택을 식별할 수 없는 공고. */
    static List<MyHomeNoticeItem> constructionNoticeWithoutValidSupplyLine() {
        return parse("""
                {"response":{"body":{"item":[
                  {"pblancId":"30002","houseSn":0,"sttusNm":"일반공고","pblancNm":"행복주택 모집",
                   "suplyInsttNm":"LH","houseTyNm":"아파트","suplyTyNm":"행복주택","beforePblancId":"",
                   "rcritPblancDe":"20260801","przwnerPresnatnDe":"20261001","refrnc":"1600-1004",
                   "url":"https://apply.lh.or.kr/?panId=invalid","hsmpNm":"","fullAdres":"","pnu":"",
                   "sumSuplyCo":5,"beginDe":"20260810","endDe":"20260812"}
                ]}}}
                """, MyHomeNoticeItem.class);
    }

    /** 원천 공고 ID가 빠져 어떤 aggregate에도 넣을 수 없는 행. */
    static List<MyHomeNoticeItem> noticeItemWithoutIdentity() {
        return parse("""
                {"response":{"body":{"item":[
                  {"pblancId":"","houseSn":1,"sttusNm":"일반공고","pblancNm":"행복주택 모집",
                   "suplyInsttNm":"LH","houseTyNm":"아파트","suplyTyNm":"행복주택",
                   "hsmpNm":"정상단지","fullAdres":"경기도 성남시 테스트로 1","pnu":"4113111600104160001"}
                ]}}}
                """, MyHomeNoticeItem.class);
    }

    private static <T> List<T> parse(String json, Class<T> type) {
        List<JsonNode> rows = OpenApiClient.findRows(MAPPER.readTree(json), LIST_POINTER);
        List<T> items = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            items.add(MAPPER.convertValue(row, type));
        }
        return items;
    }
}
