package test.domain.ingest.myhome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.housing.HouseType;
import test.domain.housing.SupplyType;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.ingest.OpenApiClient;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.notice.SuppliedHousing;
import test.domain.source.SourceSystem;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeHousingRepository noticeHousingRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

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
            noticeHousingRepository.deleteAll();
            noticeVersionRepository.deleteAll();
            recruitmentNoticeRepository.deleteAll();
        });

        fakeClient = new FakeMyHomeClient();
        capturedSupplyTypeCodes = fakeClient.capturedSupplyTypeCodes;
        service = new MyHomeNoticeIngestService(
                fakeClient, recruitmentNoticeRepository, noticeVersionRepository, noticeHousingRepository,
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

    private NoticeVersion version(String sourceNoticeId) {
        return noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, sourceNoticeId)
                .orElseThrow();
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
        assertThat(noticeVersionRepository.findBySourceSystemAndSourceNoticeId(
                SourceSystem.MYHOME_PORTAL, "happy-partial")).isEmpty();
    }

    @Test
    @DisplayName("(pblancId, houseSn) 이 같은 행은 하나로 합치되, 내용이 갈리면 공고 전체를 제외한다")
    void collapsesIdenticalSourceKeysButRejectsConflictingNoticeRows() {
        IngestReport report = service.apply(MyHomeFixtures.rowsWithExactDuplicateAndConflictingDuplicate());

        assertThat(noticeHousingRepository.count()).isOne();
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
    }

    @Test
    @DisplayName("PNU·주소가 없어도 houseSn 이 있으면 공급행을 저장한다")
    void keepsIdentifiedHousingEvenWhenPnuAndAddressAreMissing() {
        IngestReport report = service.apply(List.of(MyHomeFixtures.rowWithHouseSnButNoPnuOrAddress()));

        assertThat(report.created()).isOne();
        assertThat(noticeHousingRepository.findAll()).singleElement().satisfies(housing -> {
            assertThat(housing.getHouseSn()).isEqualTo(1);
            assertThat(housing.getSuppliedHousing().getPnu()).isNull();
            assertThat(housing.getSuppliedHousing().getFullAddress()).isNull();
        });
    }

    @Test
    @DisplayName("정정 체인은 입력 순서나 숫자 정렬과 무관하게 해소된다")
    void resolvesCorrectionChainRegardlessOfInputOrder() {
        service.apply(MyHomeFixtures.correctionRowsBeforeOriginalRows());

        NoticeVersion original = version("20965");
        NoticeVersion correction = version("20989");
        assertThat(correction.getRecruitmentNotice().getId())
                .isEqualTo(original.getRecruitmentNotice().getId());
        assertThat(correction.getSupersedesVersion().getId()).isEqualTo(original.getId());
    }

    @Test
    @DisplayName("정정공고는 새 pblancId로 오지만 원공고와 한 체인으로 묶인다")
    void linksCorrectionToOriginalChain() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItems());

        assertThat(report).isEqualTo(new IngestReport(1, 1, 0, 0, Map.of()));

        NoticeVersion original = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20965").orElseThrow();
        NoticeVersion correction = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20989").orElseThrow();

        assertThat(original.getVersionNumber()).isEqualTo(1);
        assertThat(original.getNoticeChangeStatus()).isEqualTo(NoticeChangeStatus.ORIGINAL);
        assertThat(original.getSupersedesVersion()).isNull();

        assertThat(correction.getVersionNumber()).isEqualTo(2);
        assertThat(correction.getNoticeChangeStatus()).isEqualTo(NoticeChangeStatus.CORRECTION);
        assertThat(correction.getSupersedesVersion().getId()).isEqualTo(original.getId());
        // 두 버전이 같은 공고라는 사실은 recruitmentNotice 가 붙든다.
        assertThat(correction.getRecruitmentNotice().getId()).isEqualTo(original.getRecruitmentNotice().getId());
        assertThat(correction.getBeforeSourceNoticeId()).isEqualTo("20965");
    }

    @Test
    @DisplayName("원공고는 지워지지 않고 접수기간이 그대로 남는다")
    void keepsSupersededVersionIntact() {
        service.apply(MyHomeFixtures.noticeItems());

        NoticeVersion original = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20965").orElseThrow();
        assertThat(original.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(original.getApplicationEndOn()).isEqualTo(LocalDate.of(2026, 8, 13));

        NoticeVersion correction = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20989").orElseThrow();
        assertThat(correction.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(correction.getWinnerAnnouncedOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("공고 단위 값은 공고에, 행 단위 URL은 공급행에 들어간다")
    void putsNoticeLevelAndRowLevelValuesInTheRightPlace() {
        service.apply(MyHomeFixtures.noticeItems());

        NoticeVersion correction = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20989").orElseThrow();
        assertThat(correction.getSupplyInstitutionName()).isEqualTo("LH");
        assertThat(correction.getSupplyTypeName()).isEqualTo("행복주택");
        assertThat(correction.getHouseTypeName()).isEqualTo("아파트");
        // 공고 쪽 enum 은 단지 쪽 unit_type.supply_type 과 같은 타입이라 그대로 비교된다.
        assertThat(correction.getSupplyType()).isEqualTo(SupplyType.HAPPY_HOUSE);
        assertThat(correction.getHouseType()).isEqualTo(HouseType.APARTMENT);
        assertThat(correction.getContact()).startsWith("LH 콜센터");
        // 공고 단위 URL은 pcUrl 이 아니라 url 이다. pcUrl 은 houseSn 이 붙어 행마다 다르다.
        assertThat(correction.getDetailUrl()).contains("apply.lh.or.kr").doesNotContain("houseSn");

        List<NoticeHousing> lines = noticeHousingRepository.findByNoticeVersionOrderByDisplayOrder(correction);
        assertThat(lines).extracting(NoticeHousing::getDetailUrl)
                .allSatisfy(url -> assertThat(url).contains("myhome.go.kr"));
        assertThat(lines).extracting(NoticeHousing::getDetailUrl)
                .containsExactly(
                        "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=1",
                        "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=3");
    }

    @Test
    @DisplayName("한 공고의 여러 행은 공급행이 되고 행마다 주택 정보와 임대조건이 따라간다")
    void turnsRowsIntoSupplyLines() {
        service.apply(MyHomeFixtures.noticeItems());

        NoticeVersion correction = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20989").orElseThrow();
        List<NoticeHousing> lines = noticeHousingRepository.findByNoticeVersionOrderByDisplayOrder(correction);

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(NoticeHousing::getSupplyCount).containsExactly(50, 117);
        assertThat(lines).extracting(NoticeHousing::getDisplayOrder).containsExactly(0, 1);
        assertThat(lines).extracting(NoticeHousing::getHouseSn).containsExactly(1, 3);

        NoticeHousing guri = lines.get(0);
        SuppliedHousing housing = guri.getSuppliedHousing();
        assertThat(housing.getComplexName()).isEqualTo("구리수택");
        assertThat(housing.getPnu()).isEqualTo("4131010500108520000");
        assertThat(housing.getRoadName()).isEqualTo("체육관로74번길");
        assertThat(housing.getHeatingTypeName()).isEqualTo("개별난방");
        assertThat(housing.getTotalUnitCount()).isEqualTo(394);
        assertThat(housing.regionName()).isEqualTo("경기도 구리시");

        assertThat(guri.getRentTerms().getDeposit()).isEqualTo(37_224_000L);
        assertThat(guri.getRentTerms().getDownPayment()).isEqualTo(1_862_000L);
        assertThat(guri.getRentTerms().getBalance()).isEqualTo(35_362_000L);
        assertThat(guri.getRentTerms().getMonthlyRent()).isEqualTo(156_000L);
    }

    /**
     * 원래 의도: 이미 적재된 단지가 있으면 PNU로 공급행에 자동으로 붙는다.
     * 이제 {@link NoticeHousing} 은 카탈로그 FK를 아예 갖지 않으므로 그 붙임은 이 서비스에서 일어날 수 없다.
     * PNU 매칭의 실제 의미(Task 8의 파생 matcher)는 여기서 검증하지 않고, 지금 참인 구조적 사실만 검증한다.
     */
    @Test
    @DisplayName("정정: NoticeHousing은 카탈로그 FK를 갖지 않는다 (PNU 매칭은 Task 8의 matcher로 이동)")
    void attachesComplexByPnu() {
        assertThat(Arrays.stream(NoticeHousing.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("complex", "housingComplex");
    }

    @Test
    @DisplayName("전세임대 공고는 적재하지 않는다")
    void skipsJeonseRentalNotice() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithoutComplex());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 1);
        assertThat(noticeVersionRepository.count()).isZero();
        assertThat(noticeHousingRepository.count()).isZero();
    }

    @Test
    @DisplayName("한 공고에 깨진 공급행이 섞여도 정상 행만 순서를 다시 매겨 저장한다")
    void rejectsOnlyInvalidSupplyLines() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithInvalidSupplyLine());

        assertThat(report.created()).isOne();
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(noticeHousingRepository.findAll()).singleElement()
                .satisfies(line -> {
                    assertThat(line.getDisplayOrder()).isZero();
                    assertThat(line.getSuppliedHousing().getComplexName()).isEqualTo("정상단지");
                });
    }

    @Test
    @DisplayName("한 공고 안에서 공급유형이 갈리면 공고 전체를 저장하지 않는다")
    void rejectsNoticeWithInconsistentSupplyTypes() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithMixedSupplyTypes());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(noticeVersionRepository.count()).isZero();
        assertThat(noticeHousingRepository.count()).isZero();
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
                pagedClient, recruitmentNoticeRepository, noticeVersionRepository, noticeHousingRepository,
                new ConstructionRentalPolicy(), transactionManager);

        List<MyHomeNoticeItem> fetched = service.fetchComplete(MyHomeRentalType.HAPPY_HOUSE, 1, 10).orElseThrow();
        IngestReport report = service.apply(fetched);

        NoticeVersion saved = noticeVersionRepository
                .findBySourceSystemAndSourceNoticeId(SourceSystem.MYHOME_PORTAL, "20989").orElseThrow();
        assertThat(report.created()).isOne();
        assertThat(report.unchanged()).isZero();
        assertThat(noticeHousingRepository.findByNoticeVersionOrderByDisplayOrder(saved)).hasSize(2);
    }

    @Test
    @DisplayName("건설형 임대 공고라도 유효한 공급행이 없으면 공고버전도 저장하지 않는다")
    void rejectsNoticeWithoutValidSupplyLine() {
        IngestReport report = service.apply(MyHomeFixtures.constructionNoticeWithoutValidSupplyLine());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(noticeVersionRepository.count()).isZero();
        assertThat(noticeHousingRepository.count()).isZero();
    }

    @Test
    @DisplayName("공고 ID가 없는 행은 조용히 사라지지 않고 제외 사유로 남는다")
    void reportsMissingNoticeIdentity() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemWithoutIdentity());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.MISSING_IDENTITY, 1);
        assertThat(noticeVersionRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 응답을 다시 읽어도 버전이 늘지 않는다")
    void isIdempotent() {
        service.apply(MyHomeFixtures.noticeItems());

        IngestReport second = service.apply(MyHomeFixtures.noticeItems());

        assertThat(second).isEqualTo(new IngestReport(0, 0, 2, 0, Map.of()));
        assertThat(noticeVersionRepository.count()).isEqualTo(2);
        assertThat(noticeHousingRepository.count()).isEqualTo(3);
    }

    /**
     * 원래 의도: 단지를 나중에 적재하면 재매칭으로 공급행에 뒤늦게 붙는다.
     * 그 재매칭 연결 지점(rematchComplexes)이 이 서비스에서 사라졌다는 사실만 지금 확인한다.
     * 실제 재매칭 의미는 Task 8의 파생 matcher가 갖는다.
     */
    @Test
    @DisplayName("정정: 이 서비스에는 더 이상 재매칭 메서드가 없다 (Task 8의 matcher로 이동)")
    void rematchesComplexesIngestedLater() {
        assertThat(Arrays.stream(MyHomeNoticeIngestService.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("rematchComplexes");
    }
}
