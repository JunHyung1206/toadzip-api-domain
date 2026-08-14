package test.domain.ingest;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import test.domain.ingest.lh.LhLeaseInfoIngestService;
import test.domain.ingest.lh.LhNoticeIngestService;
import test.domain.ingest.myhome.MyHomeComplexIngestService;
import test.domain.ingest.myhome.MyHomeNoticeIngestService;
import tools.jackson.databind.JsonNode;

/**
 * 적재 트리거와 원천 응답 확인용. 운영에 열 엔드포인트가 아니라 개발 중 확인용이다.
 *
 * <p><b>순서가 있다.</b> 공급행이 카탈로그를 가리키려면 단지가 먼저 있어야 하고, 주택형까지 쪼개려면
 * LH 원천이 먼저 와야 한다.
 *
 * <pre>
 *   1. /complexes     15110581 → 단지·주택형 카탈로그
 *   2. /lease-infos   15059475 → 주택형 전체 세대수·기준 임대조건 보강
 *   3. /notices       15108420 → 공고 + 단지 단위 공급행
 *   4. /lh-notices    15057999 + 15056765 → 일정·접수처·첨부 + 공급행을 주택형 단위로 재구성
 *   5. /links                  → 공급행에 카탈로그 단지·주택형 FK 채우기
 * </pre>
 */
@RestController
@RequestMapping("/admin/ingest")
public class IngestController {

    private final MyHomeComplexIngestService complexIngestService;
    private final MyHomeNoticeIngestService noticeIngestService;
    private final LhNoticeIngestService lhNoticeIngestService;
    private final LhLeaseInfoIngestService lhLeaseInfoIngestService;
    private final NoticeSupplyCatalogLinker catalogLinker;
    private final OpenApiClient lhApiClient;
    private final OpenApiClient myhomeNoticeApiClient;
    private final OpenApiClient myhomeComplexApiClient;

    public IngestController(MyHomeComplexIngestService complexIngestService,
                            MyHomeNoticeIngestService noticeIngestService,
                            LhNoticeIngestService lhNoticeIngestService,
                            LhLeaseInfoIngestService lhLeaseInfoIngestService,
                            NoticeSupplyCatalogLinker catalogLinker,
                            @Qualifier("lhApiClient") OpenApiClient lhApiClient,
                            @Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeNoticeApiClient,
                            @Qualifier("myhomeComplexApiClient") OpenApiClient myhomeComplexApiClient) {
        this.complexIngestService = complexIngestService;
        this.noticeIngestService = noticeIngestService;
        this.lhNoticeIngestService = lhNoticeIngestService;
        this.lhLeaseInfoIngestService = lhLeaseInfoIngestService;
        this.catalogLinker = catalogLinker;
        this.lhApiClient = lhApiClient;
        this.myhomeNoticeApiClient = myhomeNoticeApiClient;
        this.myhomeComplexApiClient = myhomeComplexApiClient;
    }

    /** {@link MyHomeComplexIngestService#ingestNationwide} 로 전국 256개 시군구를 돈다. */
    @PostMapping("/complexes")
    public IngestReport ingestComplexes(@RequestParam(defaultValue = "200") int pageSize,
                                        @RequestParam(defaultValue = "50") int maxPages) {
        return complexIngestService.ingestNationwide(pageSize, maxPages);
    }

    /** 15059475를 전부 읽어 확정된 주택형의 전체 세대수·기준 임대조건을 카탈로그에 반영한다. */
    @PostMapping("/lease-infos")
    public IngestReport ingestLeaseInfos(@RequestParam(defaultValue = "9999") int pageSize,
                                         @RequestParam(defaultValue = "1") int maxPages) {
        return lhLeaseInfoIngestService.ingest(pageSize, maxPages);
    }

    /** 임대공고만 받는다. 분양공고를 안 담는 이유는 {@link MyHomeNoticeIngestService#RENTAL_PATH} 에 있다. */
    @PostMapping("/notices")
    public IngestReport ingestNotices(@RequestParam(defaultValue = "100") int pageSize,
                                      @RequestParam(defaultValue = "50") int maxPages) {
        return noticeIngestService.ingest(pageSize, maxPages);
    }

    /**
     * 이미 적재된 LH 공고에 일정·접수처·첨부·정정사유를 붙이고, 공급행을 주택형 단위로 다시 쓴다
     * ({@link LhNoticeIngestService}). 공고를 먼저 적재해야 한다.
     */
    @PostMapping("/lh-notices")
    public IngestReport ingestLhNotices() {
        return lhNoticeIngestService.ingest();
    }

    /**
     * 공급행에 카탈로그 단지·주택형 FK를 채운다. 공고와 카탈로그가 서로 다른 시점에 들어오므로
     * 둘 다 적재한 뒤에 돌린다. 규칙을 고쳤을 때 원천 재호출 없이 다시 돌려도 된다.
     */
    @PostMapping("/links")
    public IngestReport linkCatalog() {
        return catalogLinker.linkAll();
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
