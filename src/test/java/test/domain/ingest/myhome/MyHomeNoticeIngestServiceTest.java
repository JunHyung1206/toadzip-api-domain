package test.domain.ingest.myhome;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.OpenApiClient;
import test.domain.notice.Notice;
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 15108420 실제 응답 모양 그대로 태운다. HTTP만 빠져 있다. */
@DataJpaTest
class MyHomeNoticeIngestServiceTest {

    @Autowired
    private NoticeRepository noticeRepository;
    @Autowired
    private NoticeSupplyRepository supplyRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    private FakeMyHomeClient fakeClient;
    private List<String> capturedSupplyTypeCodes;
    private MyHomeNoticeIngestService service;

    @BeforeEach
    void setUp() {
        // 공고 저장이 REQUIRES_NEW 로 커밋되어 @DataJpaTest 기본 롤백을 우회하므로,
        // 테스트 사이에 남는 데이터를 매번 별도 트랜잭션으로 직접 비운다.
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.executeWithoutResult(status -> {
            supplyRepository.deleteAll();
            noticeRepository.deleteAll();
        });

        fakeClient = new FakeMyHomeClient();
        capturedSupplyTypeCodes = fakeClient.capturedSupplyTypeCodes;
        service = new MyHomeNoticeIngestService(
                fakeClient, noticeRepository, supplyRepository,
                new ConstructionRentalPolicy(), transactionManager);
    }

    /** 원천은 suplyTy(공급유형)별로만 필터를 열어 둬서, ingest 가 어떤 코드를 요청하는지 여기서 기록한다. */
    private static final class FakeMyHomeClient extends OpenApiClient {
        private final List<String> capturedSupplyTypeCodes = new ArrayList<>();
        private final Set<String> typesThatNeverEnd = new HashSet<>();

        FakeMyHomeClient() {
            super(JsonMapper.builder().build(), "unused", "unused", "fake-myhome-notice");
        }

        /** 이 공급유형은 매 페이지 pageSize 만큼 꽉 찬 행을 계속 돌려줘서 maxPages 안에 끝나지 않게 한다. */
        void returnFullPagesUntilLimit(String supplyTypeCode) {
            typesThatNeverEnd.add(supplyTypeCode);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> getList(String path, MultiValueMap<String, String> params,
                                   String listKey, Class<T> type) {
            String supplyTypeCode = params.getFirst("suplyTy");
            capturedSupplyTypeCodes.add(supplyTypeCode);
            if (!typesThatNeverEnd.contains(supplyTypeCode)) {
                return List.of();
            }
            int pageSize = Integer.parseInt(params.getFirst("numOfRows"));
            List<MyHomeNoticeItem> page = new ArrayList<>();
            for (int i = 0; i < pageSize; i++) {
                page.add(MyHomeFixtures.partialTypeFullPageItem());
            }
            return (List<T>) page;
        }
    }

    private Notice notice(String sourceNoticeId) {
        return noticeRepository.findBySourceNoticeId(sourceNoticeId).orElseThrow();
    }

    @Test
    @DisplayName("허용된 8개 공급유형 코드만 요청한다")
    void requestsOnlyEightApprovedSupplyTypeCodes() {
        service.ingest(100, 50);

        assertThat(capturedSupplyTypeCodes)
                .containsExactly("01", "02", "03", "05", "06", "07", "10", "12")
                .doesNotContain("13");
    }

    @Test
    @DisplayName("한 공급유형이 마지막 페이지에 닿지 못하면 그 유형의 행은 하나도 반영하지 않는다")
    void doesNotApplyRowsFromATypeThatDoesNotReachItsLastPage() {
        fakeClient.returnFullPagesUntilLimit("10");

        IngestReport report = service.ingest(1, 2);

        assertThat(report.failed()).isOne();
        assertThat(noticeRepository.findBySourceNoticeId("happy-partial")).isEmpty();
    }

    @Test
    @DisplayName("(pblancId, houseSn) 이 같은 행은 하나로 합치되, 내용이 갈리면 공고 전체를 제외한다")
    void collapsesIdenticalSourceKeysButRejectsConflictingNoticeRows() {
        IngestReport report = service.apply(MyHomeFixtures.rowsWithExactDuplicateAndConflictingDuplicate());

        assertThat(supplyRepository.count()).isOne();
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
    }

    @Test
    @DisplayName("PNU·주소가 없어도 houseSn 이 있으면 공급행을 저장한다")
    void keepsIdentifiedHousingEvenWhenPnuAndAddressAreMissing() {
        IngestReport report = service.apply(List.of(MyHomeFixtures.rowWithHouseSnButNoPnuOrAddress()));

        assertThat(report.created()).isOne();
        assertThat(supplyRepository.findAll()).singleElement().satisfies(supply -> {
            assertThat(supply.getHouseSn()).isEqualTo(1);
            assertThat(supply.getSuppliedPnu()).isNull();
            assertThat(supply.getSuppliedAddress()).isNull();
        });
    }

    @Test
    @DisplayName("19자리 숫자가 아닌 PNU는 카탈로그와 비교할 수 없으므로 저장하지 않는다")
    void keepsOnlyWellFormedPnu() {
        service.apply(MyHomeFixtures.noticeItems());

        assertThat(supplyRepository.findAll()).extracting(NoticeSupply::getSuppliedPnu)
                .allSatisfy(pnu -> assertThat(pnu).hasSize(19));
    }

    @Test
    @DisplayName("정정 체인은 입력 순서나 숫자 정렬과 무관하게 해소된다")
    void resolvesCorrectionChainRegardlessOfInputOrder() {
        service.apply(MyHomeFixtures.correctionRowsBeforeOriginalRows());

        Notice original = notice("20965");
        Notice correction = notice("20989");
        assertThat(correction.getRootSourceNoticeId()).isEqualTo(original.getRootSourceNoticeId());
        assertThat(correction.getSupersedesNotice().getId()).isEqualTo(original.getId());
    }

    @Test
    @DisplayName("정정공고는 새 pblancId로 오지만 원공고와 한 체인으로 묶인다")
    void linksCorrectionToOriginalChain() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItems());

        assertThat(report).isEqualTo(new IngestReport(1, 1, 0, 0, Map.of()));

        Notice original = notice("20965");
        Notice correction = notice("20989");

        assertThat(original.getVersionNumber()).isEqualTo(1);
        assertThat(original.getNoticeChangeStatusName()).isEqualTo("일반공고");
        assertThat(original.getSupersedesNotice()).isNull();
        assertThat(original.getRootSourceNoticeId()).isEqualTo("20965");

        assertThat(correction.getVersionNumber()).isEqualTo(2);
        assertThat(correction.getNoticeChangeStatusName()).isEqualTo("정정공고");
        assertThat(correction.getSupersedesNotice().getId()).isEqualTo(original.getId());
        // 루트 테이블 없이 두 버전이 같은 공고라는 사실은 rootSourceNoticeId 가 붙든다.
        assertThat(correction.getRootSourceNoticeId()).isEqualTo("20965");
        assertThat(correction.getBeforeSourceNoticeId()).isEqualTo("20965");
        assertThat(noticeRepository.findByRootSourceNoticeIdOrderByVersionNumber("20965"))
                .extracting(Notice::getSourceNoticeId)
                .containsExactly("20965", "20989");
    }

    @Test
    @DisplayName("정정공고를 먼저 받고 원공고를 나중에 받아도 뿌리와 순번이 옮겨진다")
    void rebasesEarlierCorrectionWhenOriginalArrivesLater() {
        List<MyHomeNoticeItem> all = MyHomeFixtures.noticeItems();
        service.apply(all.stream().filter(item -> item.pblancId().equals("20989")).toList());

        // 원공고를 아직 모르므로 정정공고가 스스로 뿌리다.
        assertThat(notice("20989").getRootSourceNoticeId()).isEqualTo("20989");
        assertThat(notice("20989").getVersionNumber()).isEqualTo(1);

        service.apply(all.stream().filter(item -> item.pblancId().equals("20965")).toList());
        // 위 적재는 REQUIRES_NEW 로 커밋되므로, 테스트 영속성 컨텍스트에 캐시된 옛 인스턴스를 버린다.
        entityManager.clear();

        Notice correction = notice("20989");
        assertThat(correction.getRootSourceNoticeId()).isEqualTo("20965");
        assertThat(correction.getVersionNumber()).isEqualTo(2);
        assertThat(correction.getSupersedesNotice().getSourceNoticeId()).isEqualTo("20965");
    }

    @Test
    @DisplayName("원공고는 지워지지 않고 접수기간이 그대로 남는다")
    void keepsSupersededVersionIntact() {
        service.apply(MyHomeFixtures.noticeItems());

        Notice original = notice("20965");
        assertThat(original.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(original.getApplicationEndOn()).isEqualTo(LocalDate.of(2026, 8, 13));

        Notice correction = notice("20989");
        assertThat(correction.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(correction.getWinnerAnnouncedOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("공고 단위 값은 공고에, 행 단위 URL은 공급행에 들어간다")
    void putsNoticeLevelAndRowLevelValuesInTheRightPlace() {
        service.apply(MyHomeFixtures.noticeItems());

        Notice correction = notice("20989");
        assertThat(correction.getSupplyInstitutionName()).isEqualTo("LH");
        // 공급유형·주택유형은 표준값으로 옮기지 않고 원천 표기 그대로 담는다.
        assertThat(correction.getSupplyTypeName()).isEqualTo("행복주택");
        assertThat(correction.getHouseTypeName()).isEqualTo("아파트");
        assertThat(correction.getContact()).startsWith("LH 콜센터");
        // 공고 단위 URL은 pcUrl 이 아니라 url 이다. pcUrl 은 houseSn 이 붙어 행마다 다르다.
        assertThat(correction.getDetailUrl()).contains("apply.lh.or.kr").doesNotContain("houseSn");

        List<NoticeSupply> lines = supplyRepository.findByNoticeOrderByDisplayOrder(correction);
        assertThat(lines).extracting(NoticeSupply::getDetailUrl)
                .containsExactly(
                        "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=1",
                        "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=3");
    }

    @Test
    @DisplayName("한 공고의 여러 행은 단지 단위 공급행이 되고 행마다 임대조건이 따라간다")
    void turnsRowsIntoSupplyLines() {
        service.apply(MyHomeFixtures.noticeItems());

        List<NoticeSupply> lines = supplyRepository.findByNoticeOrderByDisplayOrder(notice("20989"));

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(NoticeSupply::getComplexSupplyCount).containsExactly(50, 117);
        assertThat(lines).extracting(NoticeSupply::getDisplayOrder).containsExactly(0, 1);
        assertThat(lines).extracting(NoticeSupply::getHouseSn).containsExactly(1, 3);
        // LH 15056765 를 받기 전이라 주택형 칸은 아직 비어 있다.
        assertThat(lines).extracting(NoticeSupply::getTypeName).containsOnlyNulls();
        assertThat(lines).extracting(NoticeSupply::getUnitSupplyCount).containsOnlyNulls();

        NoticeSupply guri = lines.get(0);
        assertThat(guri.getComplexName()).isEqualTo("구리수택");
        assertThat(guri.getSuppliedPnu()).isEqualTo("4131010500108520000");
        assertThat(guri.getSuppliedAddress()).isEqualTo("경기도 구리시 체육관로74번길 67");
        assertThat(guri.getComplexTotalUnitCount()).isEqualTo(394);

        assertThat(guri.getRentTerms().getDeposit()).isEqualTo(37_224_000L);
        assertThat(guri.getRentTerms().getDownPayment()).isEqualTo(1_862_000L);
        assertThat(guri.getRentTerms().getBalance()).isEqualTo(35_362_000L);
        assertThat(guri.getRentTerms().getMonthlyRent()).isEqualTo(156_000L);
    }

    @Test
    @DisplayName("전세임대 공고는 적재하지 않는다")
    void skipsJeonseRentalNotice() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithoutComplex());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 1);
        assertThat(noticeRepository.count()).isZero();
        assertThat(supplyRepository.count()).isZero();
    }

    @Test
    @DisplayName("한 공고에 깨진 공급행이 섞여도 정상 행만 순서를 다시 매겨 저장한다")
    void rejectsOnlyInvalidSupplyLines() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithInvalidSupplyLine());

        assertThat(report.created()).isOne();
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(supplyRepository.findAll()).singleElement()
                .satisfies(line -> {
                    assertThat(line.getDisplayOrder()).isZero();
                    assertThat(line.getComplexName()).isEqualTo("정상단지");
                });
    }

    @Test
    @DisplayName("한 공고 안에서 공급유형이 갈리면 공고 전체를 저장하지 않는다")
    void rejectsNoticeWithInconsistentSupplyTypes() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithMixedSupplyTypes());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(noticeRepository.count()).isZero();
        assertThat(supplyRepository.count()).isZero();
    }

    @Test
    @DisplayName("한 공고의 공급행이 페이지 경계에 걸려도 모두 모아 한 번 저장한다")
    void groupsNoticeAcrossPageBoundaries() {
        List<MyHomeNoticeItem> rows = MyHomeFixtures.noticeItems().stream()
                .filter(item -> item.pblancId().equals("20989"))
                .toList();
        OpenApiClient pagedClient = new OpenApiClient(
                JsonMapper.builder().build(), "unused", "unused", "paged") {
            private int call;

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> getList(String path, MultiValueMap<String, String> params,
                                       String listKey, Class<T> type) {
                return call < rows.size()
                        ? (List<T>) List.of(rows.get(call++))
                        : List.of();
            }
        };
        service = new MyHomeNoticeIngestService(
                pagedClient, noticeRepository, supplyRepository,
                new ConstructionRentalPolicy(), transactionManager);

        List<MyHomeNoticeItem> fetched = service.fetchComplete(MyHomeRentalType.HAPPY_HOUSE, 1, 10).orElseThrow();
        IngestReport report = service.apply(fetched);

        assertThat(report.created()).isOne();
        assertThat(report.unchanged()).isZero();
        assertThat(supplyRepository.findByNoticeOrderByDisplayOrder(notice("20989"))).hasSize(2);
    }

    @Test
    @DisplayName("건설형 임대 공고라도 유효한 공급행이 없으면 공고도 저장하지 않는다")
    void rejectsNoticeWithoutValidSupplyLine() {
        IngestReport report = service.apply(MyHomeFixtures.constructionNoticeWithoutValidSupplyLine());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(noticeRepository.count()).isZero();
        assertThat(supplyRepository.count()).isZero();
    }

    @Test
    @DisplayName("공고 ID가 없는 행은 조용히 사라지지 않고 제외 사유로 남는다")
    void reportsMissingNoticeIdentity() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemWithoutIdentity());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.MISSING_IDENTITY, 1);
        assertThat(noticeRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 응답을 다시 읽어도 버전이 늘지 않는다")
    void isIdempotent() {
        service.apply(MyHomeFixtures.noticeItems());

        IngestReport second = service.apply(MyHomeFixtures.noticeItems());

        assertThat(second).isEqualTo(new IngestReport(0, 0, 2, 0, Map.of()));
        assertThat(noticeRepository.count()).isEqualTo(2);
        assertThat(supplyRepository.count()).isEqualTo(3);
    }
}
