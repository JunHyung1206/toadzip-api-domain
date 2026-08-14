package test.domain.ingest.myhome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.IngestReport;
import test.domain.ingest.OpenApiClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ingestNationwide}/{@code ingestRegion} — 지역 하나는 페이지를 전부 모은 뒤에만 저장하고,
 * 한 지역의 실패가 전국 순회를 막지도 다른 지역을 오염시키지도 않는지 검증한다.
 *
 * <p>HTTP는 지역·페이지별 응답을 미리 정해 둔 {@link FakeMyHomeApiClient} 로 대체한다.
 */
@DataJpaTest
class MyHomeComplexNationwideIngestTest {

    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MyHomeRegionCatalog regionCatalog = new MyHomeRegionCatalog();
    private FakeMyHomeApiClient fakeClient;
    private MyHomeComplexIngestService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.executeWithoutResult(status -> {
            unitTypeRepository.deleteAll();
            complexRepository.deleteAll();
        });
        fakeClient = new FakeMyHomeApiClient();
        service = new MyHomeComplexIngestService(
                fakeClient, complexRepository, unitTypeRepository,
                new ConstructionRentalPolicy(), transactionManager, regionCatalog);
    }

    @Test
    @DisplayName("첫 페이지가 다 비어 있어도 공식 256개 지역을 정확히 한 번씩 요청한다")
    void requestsEveryOfficialRegionExactlyOnceWhenFirstPagesAreEmpty() {
        IngestReport report = service.ingestNationwide(500, 50);

        assertThat(report.failed()).isZero();
        assertThat(fakeClient.capturedRegions()).containsExactlyInAnyOrderElementsOf(
                regionCatalog.all().stream().map(MyHomeRegion::fullCode).toList());
        assertThat(fakeClient.capturedRegions()).hasSize(256).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("maxPages 안에 지역이 안 끝나면 그 지역은 아무것도 저장하지 않는다")
    void doesNotPersistARegionWhenMaxPagesCutsItOff() {
        fakeClient.returnFullPageForEveryRequest();

        IngestReport report = service.ingestRegion(region("11", "350"), 1, 2);

        assertThat(report.failed()).isOne();
        assertThat(complexRepository.count()).isZero();
    }

    @Test
    @DisplayName("나중 페이지가 실패한 지역은 저장하지 않고 전국 순회는 다음 지역으로 계속된다")
    void doesNotPersistARegionWhenLaterPageFailsAndContinuesNationwide() {
        fakeClient.respondWithPages("11350", List.of(fillerPage(500, 70_000_000L)));
        fakeClient.failOnPage("11350", 2);
        fakeClient.respondWithPages("41310", List.of(List.of(guriRow())));

        IngestReport report = service.ingestNationwide(500, 50);

        assertThat(report.failed()).isOne();
        assertThat(complexRepository.findBySourceComplexIdAndSupplyTypeName("90310001", "행복주택"))
                .isPresent();
        // 실패한 지역(11350)은 아무것도 남기지 않아 전국에서 단지가 하나만 저장돼야 한다.
        assertThat(complexRepository.count()).isOne();
    }

    @Test
    @DisplayName("같은 hsmpSn 이 페이지 경계에 걸쳐 나뉘어도 단지 하나로 한 번만 저장된다")
    void groupsSameHsmpSnAcrossPageBoundary() {
        MyHomeComplexItem firstHalf = complexItem(80_000_001L, "국민임대", "50A");
        MyHomeComplexItem secondHalf = complexItem(80_000_001L, "행복주택", "50B");
        fakeClient.respondWithPages("11350", List.of(List.of(firstHalf), List.of(secondHalf)));

        IngestReport report = service.ingestRegion(region("11", "350"), 1, 10);

        assertThat(report.created()).isEqualTo(2);
        assertThat(complexRepository.count()).isEqualTo(2);
        assertThat(unitTypeRepository.count()).isEqualTo(2);
    }

    private MyHomeRegion region(String brtcCode, String signguCode) {
        return regionCatalog.all().stream()
                .filter(candidate -> candidate.brtcCode().equals(brtcCode) && candidate.signguCode().equals(signguCode))
                .findFirst()
                .orElseThrow();
    }

    /** 경기도 구리시 "구리수택 행복주택" — {@link MyHomeFixtures#complexItemsMatchingNotice()} 와 같은 실제 행. */
    private MyHomeComplexItem guriRow() {
        return new MyHomeComplexItem(
                90_310_001L, "LH경기북부", "41", "경기도", "310", "구리시", "구리수택 행복주택",
                "경기도 구리시 체육관로74번길 67", "4131010500108520000", "20210330", 394,
                "행복주택", "26", new BigDecimal("26.70"), new BigDecimal("10.10"), "아파트",
                "개별난방", "계단식", "전체동 설치", 300, 37_224_000L, 156_000L, 0L);
    }

    private MyHomeComplexItem complexItem(long hsmpSn, String suplyTyNm, String styleNm) {
        return new MyHomeComplexItem(
                hsmpSn, "테스트기관", "11", "서울특별시", "350", "노원구", "테스트단지",
                "서울특별시 노원구 테스트로 1", "1135010500100000000", "20200101", 10,
                suplyTyNm, styleNm, new BigDecimal("39.00"), new BigDecimal("15.00"), "아파트",
                "지역난방", "계단식", "전체동 설치", 100, 10_000_000L, 100_000L, 0L);
    }

    /** maxPages 컷오프·중간 실패를 유도하려고 만든, 내용 자체는 버려질 채움 페이지. */
    private List<MyHomeComplexItem> fillerPage(int size, long startHsmpSn) {
        List<MyHomeComplexItem> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(complexItem(startHsmpSn + i, "국민임대", "50A"));
        }
        return rows;
    }

    /** 지역·페이지별 응답을 미리 정해 둔 값으로 갈아치우는 가짜 원천. HTTP만 빠져 있다. */
    private static final class FakeMyHomeApiClient extends OpenApiClient {

        private final List<String> capturedRegions = new ArrayList<>();
        private final Map<String, List<List<MyHomeComplexItem>>> pagesByRegion = new HashMap<>();
        private final Map<String, Integer> failingPageByRegion = new HashMap<>();
        private boolean fullPageForEveryRequest;

        FakeMyHomeApiClient() {
            super(JsonMapper.builder().build(), "unused", "unused", "fake-myhome-complex");
        }

        List<String> capturedRegions() {
            return capturedRegions;
        }

        /** 모든 지역·모든 페이지가 pageSize 만큼 꽉 찬 행을 돌려주게 한다(마지막 페이지가 결코 오지 않음). */
        void returnFullPageForEveryRequest() {
            fullPageForEveryRequest = true;
        }

        void respondWithPages(String regionFullCode, List<List<MyHomeComplexItem>> pages) {
            pagesByRegion.put(regionFullCode, pages);
        }

        void failOnPage(String regionFullCode, int page) {
            failingPageByRegion.put(regionFullCode, page);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> getList(String path, MultiValueMap<String, String> params,
                                   String listKey, Class<T> type) {
            String brtcCode = params.getFirst("brtcCode");
            String signguCode = params.getFirst("signguCode");
            String regionFullCode = brtcCode + signguCode;
            int page = Integer.parseInt(params.getFirst("pageNo"));
            int pageSize = Integer.parseInt(params.getFirst("numOfRows"));
            capturedRegions.add(regionFullCode);

            Integer failingPage = failingPageByRegion.get(regionFullCode);
            if (failingPage != null && failingPage == page) {
                throw new IllegalStateException(
                        "가짜 원천 실패: region=" + regionFullCode + ", page=" + page);
            }

            List<List<MyHomeComplexItem>> pages = pagesByRegion.get(regionFullCode);
            if (pages != null) {
                return (List<T>) (page <= pages.size() ? pages.get(page - 1) : List.of());
            }
            if (fullPageForEveryRequest) {
                return (List<T>) fillerRows(pageSize, brtcCode, signguCode, page);
            }
            return (List<T>) List.of();
        }

        private List<MyHomeComplexItem> fillerRows(int size, String brtcCode, String signguCode, int page) {
            List<MyHomeComplexItem> rows = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                rows.add(new MyHomeComplexItem(
                        (long) page * 1_000_000 + i, "테스트기관", brtcCode, "테스트도", signguCode, "테스트구",
                        "테스트단지", "테스트주소 1", "1100000000100000000", "20200101", 10,
                        "국민임대", "50A", new BigDecimal("39.00"), new BigDecimal("15.00"), "아파트",
                        "지역난방", "계단식", "전체동 설치", 100, 10_000_000L, 100_000L, 0L));
            }
            return rows;
        }
    }
}
