package test.domain.ingest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import test.domain.ingest.lh.LhNoticeDetailIngestService;
import test.domain.ingest.myhome.MyHomeComplexIngestService;
import test.domain.ingest.myhome.MyHomeNoticeIngestService;
import tools.jackson.databind.JsonNode;

/** 적재 트리거와 원천 응답 확인용. 운영에 열 엔드포인트가 아니라 개발 중 확인용이다. */
@RestController
@RequestMapping("/admin/ingest")
public class IngestController {

    private final MyHomeComplexIngestService complexIngestService;
    private final MyHomeNoticeIngestService noticeIngestService;
    private final LhNoticeDetailIngestService lhNoticeDetailIngestService;
    private final OpenApiClient lhApiClient;
    private final OpenApiClient myhomeNoticeApiClient;
    private final OpenApiClient myhomeComplexApiClient;

    public IngestController(MyHomeComplexIngestService complexIngestService,
                            MyHomeNoticeIngestService noticeIngestService,
                            LhNoticeDetailIngestService lhNoticeDetailIngestService,
                            @Qualifier("lhApiClient") OpenApiClient lhApiClient,
                            @Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeNoticeApiClient,
                            @Qualifier("myhomeComplexApiClient") OpenApiClient myhomeComplexApiClient) {
        this.complexIngestService = complexIngestService;
        this.noticeIngestService = noticeIngestService;
        this.lhNoticeDetailIngestService = lhNoticeDetailIngestService;
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

    /** 임대공고만 받는다. 분양공고를 안 담는 이유는 {@link MyHomeNoticeIngestService#RENTAL_PATH} 에 있다. */
    @PostMapping("/notices")
    public IngestReport ingestNotices(@RequestParam(defaultValue = "100") int pageSize,
                                      @RequestParam(defaultValue = "50") int maxPages) {
        return noticeIngestService.ingest(MyHomeNoticeIngestService.RENTAL_PATH, pageSize, maxPages);
    }

    /**
     * 이미 적재된 LH 공고에 일정·접수처·공고 시점 단지정보·첨부·정정사유를 덧입힌다.
     * 공고를 먼저 적재해야 한다.
     */
    @PostMapping("/notice-details")
    public IngestReport ingestNoticeDetails() {
        return lhNoticeDetailIngestService.ingest();
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
