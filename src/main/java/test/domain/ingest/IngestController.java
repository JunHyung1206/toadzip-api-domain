package test.domain.ingest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import test.domain.ingest.myhome.MyHomeComplexIngestService;
import test.domain.ingest.myhome.MyHomeNoticeIngestService;
import tools.jackson.databind.JsonNode;

import java.util.List;

/** 적재 트리거와 원천 응답 확인용. 운영에 열 엔드포인트가 아니라 개발 중 확인용이다. */
@RestController
@RequestMapping("/admin/ingest")
public class IngestController {

    private final MyHomeComplexIngestService complexIngestService;
    private final MyHomeNoticeIngestService noticeIngestService;
    private final OpenApiClient lhApiClient;
    private final OpenApiClient myhomeNoticeApiClient;
    private final OpenApiClient myhomeComplexApiClient;

    public IngestController(MyHomeComplexIngestService complexIngestService,
                            MyHomeNoticeIngestService noticeIngestService,
                            @Qualifier("lhApiClient") OpenApiClient lhApiClient,
                            @Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeNoticeApiClient,
                            @Qualifier("myhomeComplexApiClient") OpenApiClient myhomeComplexApiClient) {
        this.complexIngestService = complexIngestService;
        this.noticeIngestService = noticeIngestService;
        this.lhApiClient = lhApiClient;
        this.myhomeNoticeApiClient = myhomeNoticeApiClient;
        this.myhomeComplexApiClient = myhomeComplexApiClient;
    }

    /** 원천이 시군구 단위로만 조회를 열어 둬서 지역 코드가 필수다. */
    @PostMapping("/complexes")
    public IngestReport ingestComplexes(@RequestParam String brtcCode,
                                        @RequestParam String signguCode,
                                        @RequestParam(defaultValue = "200") int pageSize,
                                        @RequestParam(defaultValue = "50") int maxPages) {
        return complexIngestService.ingest(brtcCode, signguCode, pageSize, maxPages);
    }

    /**
     * 여러 시군구를 한 번에 적재한다.
     *
     * <p>{@code codes} 를 안 주면 <b>이미 적재된 공고가 가리키는 지역</b>을 스스로 뽑아서 돈다.
     * 공고의 PNU 앞 5자리가 그대로 brtcCode + signguCode 라서, 별도 지역코드표 없이도
     * "공고를 단지에 붙이는 데 실제로 필요한 지역"만 정확히 받을 수 있다. 공고를 먼저 적재해야 한다.
     */
    @PostMapping("/complexes/regions")
    public IngestReport ingestComplexRegions(@RequestParam(required = false) List<String> codes,
                                             @RequestParam(defaultValue = "500") int pageSize,
                                             @RequestParam(defaultValue = "50") int maxPages) {
        List<String> targets = (codes == null || codes.isEmpty())
                ? noticeIngestService.regionCodesFromNotices()
                : codes;
        return complexIngestService.ingestRegions(targets, pageSize, maxPages);
    }

    /** 임대공고만 받는다. 분양공고를 안 담는 이유는 {@link MyHomeNoticeIngestService#RENTAL_PATH} 에 있다. */
    @PostMapping("/notices")
    public IngestReport ingestNotices(@RequestParam(defaultValue = "100") int pageSize,
                                      @RequestParam(defaultValue = "50") int maxPages) {
        return noticeIngestService.ingest(MyHomeNoticeIngestService.RENTAL_PATH, pageSize, maxPages);
    }

    /**
     * 단지를 나중에 적재했을 때 못 붙였던 공급행을 다시 붙인다.
     * 순서가 공고 → 단지가 될 수밖에 없어서(지역 목록을 공고에서 뽑으므로) 마지막에 한 번 돌린다.
     *
     * @return 새로 연결된 공급행 수
     */
    @PostMapping("/rematch")
    public int rematch() {
        return noticeIngestService.rematchComplexes();
    }

    /**
     * 원천 응답을 가공 없이 그대로 돌려준다.
     *
     * <p>예: {@code GET /admin/ingest/probe?source=lh&path=lhLeaseInfo1/lhLeaseInfo1&PG_SZ=5&PAGE=1}
     */
    @GetMapping("/probe")
    public JsonNode probe(@RequestParam String source,
                          @RequestParam String path,
                          @RequestParam MultiValueMap<String, String> allParams) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>(allParams);
        params.remove("source");
        params.remove("path");
        return client(source).getRaw(path, params);
    }

    private OpenApiClient client(String source) {
        return switch (source) {
            case "lh" -> lhApiClient;
            case "myhome-notice" -> myhomeNoticeApiClient;
            case "myhome-complex" -> myhomeComplexApiClient;
            default -> throw new IllegalArgumentException(
                    "source 는 lh, myhome-notice, myhome-complex 중 하나입니다: " + source);
        };
    }
}
