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
import test.domain.ingest.lh.LhUnitSupplyIngestService;
import test.domain.ingest.myhome.MyHomeComplexIngestService;
import test.domain.ingest.myhome.MyHomeNoticeIngestService;
import test.domain.match.NoticeHousingCatalogMatchService;
import test.domain.match.NoticeHousingLhMatchService;
import test.domain.match.NoticeHousingUnitTypeMatchService;
import tools.jackson.databind.JsonNode;

/** 적재 트리거와 원천 응답 확인용. 운영에 열 엔드포인트가 아니라 개발 중 확인용이다. */
@RestController
@RequestMapping("/admin/ingest")
public class IngestController {

    private final MyHomeComplexIngestService complexIngestService;
    private final MyHomeNoticeIngestService noticeIngestService;
    private final LhNoticeDetailIngestService lhNoticeDetailIngestService;
    private final LhUnitSupplyIngestService lhUnitSupplyIngestService;
    private final NoticeHousingCatalogMatchService catalogMatchService;
    private final NoticeHousingLhMatchService lhMatchService;
    private final NoticeHousingUnitTypeMatchService unitTypeMatchService;
    private final OpenApiClient lhApiClient;
    private final OpenApiClient myhomeNoticeApiClient;
    private final OpenApiClient myhomeComplexApiClient;

    public IngestController(MyHomeComplexIngestService complexIngestService,
                            MyHomeNoticeIngestService noticeIngestService,
                            LhNoticeDetailIngestService lhNoticeDetailIngestService,
                            LhUnitSupplyIngestService lhUnitSupplyIngestService,
                            NoticeHousingCatalogMatchService catalogMatchService,
                            NoticeHousingLhMatchService lhMatchService,
                            NoticeHousingUnitTypeMatchService unitTypeMatchService,
                            @Qualifier("lhApiClient") OpenApiClient lhApiClient,
                            @Qualifier("myhomeNoticeApiClient") OpenApiClient myhomeNoticeApiClient,
                            @Qualifier("myhomeComplexApiClient") OpenApiClient myhomeComplexApiClient) {
        this.complexIngestService = complexIngestService;
        this.noticeIngestService = noticeIngestService;
        this.lhNoticeDetailIngestService = lhNoticeDetailIngestService;
        this.lhUnitSupplyIngestService = lhUnitSupplyIngestService;
        this.catalogMatchService = catalogMatchService;
        this.lhMatchService = lhMatchService;
        this.unitTypeMatchService = unitTypeMatchService;
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

    /** 임대공고만 받는다. 분양공고를 안 담는 이유는 {@link MyHomeNoticeIngestService#RENTAL_PATH} 에 있다. */
    @PostMapping("/notices")
    public IngestReport ingestNotices(@RequestParam(defaultValue = "100") int pageSize,
                                      @RequestParam(defaultValue = "50") int maxPages) {
        return noticeIngestService.ingest(pageSize, maxPages);
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
     * 이미 적재된 LH 공고에 15056765 주택형별 공급정보를 덧입힌다({@link LhUnitSupplyIngestService}).
     * 공고를 먼저 적재해야 한다. {@code /notice-details} 와는 별도 호출이라 순서가 안 얽힌다.
     */
    @PostMapping("/unit-supplies")
    public IngestReport ingestUnitSupplies() {
        return lhUnitSupplyIngestService.ingest();
    }

    /** {@link NoticeHousingCatalogMatchService} 로 공고 세대를 단지 카탈로그 PNU와 잇는다. */
    @PostMapping("/matches/catalog")
    public void matchCatalog(@RequestParam Long noticeVersionId) {
        catalogMatchService.match(noticeVersionId, "catalog-pnu-v1");
    }

    /** {@link NoticeHousingLhMatchService} 로 공고 세대를 LH 상세 주소·세대수와 잇는다. */
    @PostMapping("/matches/lh")
    public void matchLh(@RequestParam Long noticeVersionId) {
        lhMatchService.match(noticeVersionId, "lh-address-unit-v1");
    }

    /**
     * {@link NoticeHousingUnitTypeMatchService} 로 공고 세대를 카탈로그 주택형과 잇는다.
     * {@code /matches/catalog} 를 먼저 돌려 둬야 단지가 확정되어 있다.
     */
    @PostMapping("/matches/unit-type")
    public void matchUnitType(@RequestParam Long noticeVersionId) {
        unitTypeMatchService.match(noticeVersionId, "catalog-pnu-v1", "unit-type-area-v1");
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
