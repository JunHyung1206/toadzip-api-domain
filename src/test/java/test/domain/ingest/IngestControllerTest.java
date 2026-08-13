package test.domain.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import test.domain.ingest.lh.LhNoticeDetailIngestService;
import test.domain.ingest.lh.LhUnitSupplyIngestService;
import test.domain.ingest.myhome.MyHomeComplexIngestService;
import test.domain.ingest.myhome.MyHomeNoticeIngestService;
import test.domain.match.NoticeHousingCatalogMatchService;
import test.domain.match.NoticeHousingLhMatchService;
import test.domain.match.NoticeHousingUnitTypeMatchService;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 컨트롤러가 요청을 그대로 서비스에 위임하는지만 본다. 서비스 자체 동작은 각 서비스 테스트가 맡는다. */
@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyHomeComplexIngestService complexIngestService;
    @MockitoBean
    private MyHomeNoticeIngestService noticeIngestService;
    @MockitoBean
    private LhNoticeDetailIngestService lhNoticeDetailIngestService;
    @MockitoBean
    private LhUnitSupplyIngestService lhUnitSupplyIngestService;
    @MockitoBean
    private NoticeHousingCatalogMatchService catalogMatchService;
    @MockitoBean
    private NoticeHousingLhMatchService lhMatchService;
    @MockitoBean
    private NoticeHousingUnitTypeMatchService unitTypeMatchService;
    @MockitoBean
    @Qualifier("lhApiClient")
    private OpenApiClient lhApiClient;
    @MockitoBean
    @Qualifier("myhomeNoticeApiClient")
    private OpenApiClient myhomeNoticeApiClient;
    @MockitoBean
    @Qualifier("myhomeComplexApiClient")
    private OpenApiClient myhomeComplexApiClient;

    @Test
    void delegatesEachEndpointToItsService() throws Exception {
        when(complexIngestService.ingestNationwide(500, 50)).thenReturn(IngestReport.empty());
        mockMvc.perform(post("/admin/ingest/complexes")
                        .param("pageSize", "500")
                        .param("maxPages", "50"))
                .andExpect(status().isOk());
        verify(complexIngestService).ingestNationwide(500, 50);

        when(noticeIngestService.ingest(100, 50)).thenReturn(IngestReport.empty());
        mockMvc.perform(post("/admin/ingest/notices")
                        .param("pageSize", "100")
                        .param("maxPages", "50"))
                .andExpect(status().isOk());
        verify(noticeIngestService).ingest(100, 50);

        mockMvc.perform(post("/admin/ingest/matches/catalog").param("noticeVersionId", "7"))
                .andExpect(status().isOk());
        verify(catalogMatchService).match(7L, "catalog-pnu-v1");

        mockMvc.perform(post("/admin/ingest/matches/lh").param("noticeVersionId", "7"))
                .andExpect(status().isOk());
        verify(lhMatchService).match(7L, "lh-address-unit-v1");

        mockMvc.perform(post("/admin/ingest/matches/unit-type").param("noticeVersionId", "7"))
                .andExpect(status().isOk());
        verify(unitTypeMatchService).match(7L, "catalog-pnu-v1", "unit-type-area-v1");
    }
}
