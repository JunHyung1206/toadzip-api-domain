package test.domain.ingest.myhome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.housing.HouseType;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.HousingProviderAgencyRepository;
import test.domain.housing.SupplyType;
import test.domain.housing.UnitTypeRepository;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.ConstructionRentalPolicy;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.SuppliedHousing;
import test.domain.notice.SupplyLine;
import test.domain.notice.SupplyLineRepository;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 15108420 실제 응답 모양 그대로 태운다. HTTP만 빠져 있다. */
@DataJpaTest
class MyHomeNoticeIngestServiceTest {

    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private SupplyLineRepository supplyLineRepository;
    @Autowired
    private HousingComplexRepository complexRepository;
    @Autowired
    private UnitTypeRepository unitTypeRepository;
    @Autowired
    private HousingProviderAgencyRepository agencyRepository;

    private MyHomeNoticeIngestService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeNoticeIngestService(
                null, noticeVersionRepository, supplyLineRepository, complexRepository,
                new ConstructionRentalPolicy());
    }

    @Test
    @DisplayName("정정공고는 새 pblancId로 오지만 원공고와 한 체인으로 묶인다")
    void linksCorrectionToOriginalChain() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItems());

        assertThat(report).isEqualTo(new IngestReport(1, 1, 0, 0));

        NoticeVersion original = noticeVersionRepository.findBySourceNoticeId("20965").orElseThrow();
        NoticeVersion correction = noticeVersionRepository.findBySourceNoticeId("20989").orElseThrow();

        assertThat(original.getVersionNumber()).isEqualTo(1);
        assertThat(original.getNoticeChangeStatus()).isEqualTo(NoticeChangeStatus.ORIGINAL);
        assertThat(original.getSupersedesVersion()).isNull();

        assertThat(correction.getVersionNumber()).isEqualTo(2);
        assertThat(correction.getNoticeChangeStatus()).isEqualTo(NoticeChangeStatus.CORRECTION);
        assertThat(correction.getSupersedesVersion().getId()).isEqualTo(original.getId());
        // 두 버전이 같은 공고라는 사실은 noticeId 가 붙든다.
        assertThat(correction.getNoticeId()).isEqualTo("20965");
    }

    @Test
    @DisplayName("원공고는 지워지지 않고 접수기간이 그대로 남는다")
    void keepsSupersededVersionIntact() {
        service.apply(MyHomeFixtures.noticeItems());

        NoticeVersion original = noticeVersionRepository.findBySourceNoticeId("20965").orElseThrow();
        assertThat(original.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 11));
        assertThat(original.getApplicationEndOn()).isEqualTo(LocalDate.of(2026, 8, 13));

        NoticeVersion correction = noticeVersionRepository.findBySourceNoticeId("20989").orElseThrow();
        assertThat(correction.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(correction.getWinnerAnnouncedOn()).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    @Test
    @DisplayName("공고 단위 값은 공고에, 행 단위 URL은 공급행에 들어간다")
    void putsNoticeLevelAndRowLevelValuesInTheRightPlace() {
        service.apply(MyHomeFixtures.noticeItems());

        NoticeVersion correction = noticeVersionRepository.findBySourceNoticeId("20989").orElseThrow();
        assertThat(correction.getSupplyInstitutionName()).isEqualTo("LH");
        assertThat(correction.getSupplyTypeName()).isEqualTo("행복주택");
        assertThat(correction.getHouseTypeName()).isEqualTo("아파트");
        // 공고 쪽 enum 은 단지 쪽 unit_type.supply_type 과 같은 타입이라 그대로 비교된다.
        assertThat(correction.getSupplyType()).isEqualTo(SupplyType.HAPPY_HOUSE);
        assertThat(correction.getHouseType()).isEqualTo(HouseType.APARTMENT);
        assertThat(correction.getContact()).startsWith("LH 콜센터");
        // 공고 단위 URL은 pcUrl 이 아니라 url 이다. pcUrl 은 houseSn 이 붙어 행마다 다르다.
        assertThat(correction.getDetailUrl()).contains("apply.lh.or.kr").doesNotContain("houseSn");

        List<SupplyLine> lines = supplyLineRepository.findByNoticeVersionOrderByDisplayOrder(correction);
        assertThat(lines).extracting(SupplyLine::getDetailUrl)
                .allSatisfy(url -> assertThat(url).contains("myhome.go.kr"));
        assertThat(lines).extracting(SupplyLine::getDetailUrl)
                .containsExactly(
                        "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=1",
                        "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=20989&houseSn=3");
    }

    @Test
    @DisplayName("한 공고의 여러 행은 공급행이 되고 행마다 주택 정보와 임대조건이 따라간다")
    void turnsRowsIntoSupplyLines() {
        service.apply(MyHomeFixtures.noticeItems());

        NoticeVersion correction = noticeVersionRepository.findBySourceNoticeId("20989").orElseThrow();
        List<SupplyLine> lines = supplyLineRepository.findByNoticeVersionOrderByDisplayOrder(correction);

        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(SupplyLine::getSupplyCount).containsExactly(50, 117);
        assertThat(lines).extracting(SupplyLine::getDisplayOrder).containsExactly(0, 1);
        assertThat(lines).extracting(SupplyLine::getHouseSn).containsExactly(1, 3);

        SupplyLine guri = lines.get(0);
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

    @Test
    @DisplayName("이미 적재된 단지가 있으면 PNU로 공급행에 붙는다")
    void attachesComplexByPnu() {
        new MyHomeComplexIngestService(null, complexRepository, unitTypeRepository, agencyRepository,
                new ConstructionRentalPolicy())
                .apply(MyHomeFixtures.constructedComplexItems());
        long complexCountBefore = complexRepository.count();

        service.apply(MyHomeFixtures.noticeItems());

        // 픽스처의 단지(노원 중계센트럴파크)와 공고의 단지(구리·남양주)는 PNU가 다르니 붙지 않아야 한다.
        assertThat(supplyLineRepository.findAll()).allSatisfy(
                line -> assertThat(line.getComplex()).isNull());
        // 그리고 공고 적재가 단지를 새로 만들어서도 안 된다.
        assertThat(complexRepository.count()).isEqualTo(complexCountBefore);
    }

    @Test
    @DisplayName("전세임대 공고는 적재하지 않는다")
    void skipsJeonseRentalNotice() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithoutComplex());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 1);
        assertThat(noticeVersionRepository.count()).isZero();
        assertThat(supplyLineRepository.count()).isZero();
    }

    @Test
    @DisplayName("한 공고에 깨진 공급행이 섞여도 정상 행만 순서를 다시 매겨 저장한다")
    void rejectsOnlyInvalidSupplyLines() {
        IngestReport report = service.apply(MyHomeFixtures.noticeItemsWithInvalidSupplyLine());

        assertThat(report.created()).isOne();
        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(supplyLineRepository.findAll()).singleElement()
                .satisfies(line -> {
                    assertThat(line.getDisplayOrder()).isZero();
                    assertThat(line.getSuppliedHousing().getComplexName()).isEqualTo("정상단지");
                });
    }

    @Test
    @DisplayName("건설형 임대 공고라도 유효한 공급행이 없으면 공고버전도 저장하지 않는다")
    void rejectsNoticeWithoutValidSupplyLine() {
        IngestReport report = service.apply(MyHomeFixtures.constructionNoticeWithoutValidSupplyLine());

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
        assertThat(noticeVersionRepository.count()).isZero();
        assertThat(supplyLineRepository.count()).isZero();
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

        assertThat(second).isEqualTo(new IngestReport(0, 0, 2, 0));
        assertThat(noticeVersionRepository.count()).isEqualTo(2);
        assertThat(supplyLineRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("단지를 나중에 적재하면 재매칭으로 뒤늦게 붙는다")
    void rematchesComplexesIngestedLater() {
        // 공고가 먼저 들어온다. 이 시점엔 단지가 없어서 아무것도 못 붙는다.
        service.apply(MyHomeFixtures.noticeItems());
        assertThat(supplyLineRepository.findAll()).allSatisfy(
                line -> assertThat(line.getComplex()).isNull());

        // 공고의 PNU 를 가진 단지가 뒤늦게 들어온다.
        new MyHomeComplexIngestService(null, complexRepository, unitTypeRepository, agencyRepository,
                new ConstructionRentalPolicy())
                .apply(MyHomeFixtures.complexItemsMatchingNotice());

        // 공급행 3개(구리 2 + 남양주 1)가 단지 2개에 붙는다.
        assertThat(service.rematchComplexes()).isEqualTo(3);
        assertThat(supplyLineRepository.findAll())
                .filteredOn(line -> line.getComplex() != null)
                .hasSize(3);
        // 두 번 돌려도 더 붙을 게 없다.
        assertThat(service.rematchComplexes()).isZero();
    }
}
