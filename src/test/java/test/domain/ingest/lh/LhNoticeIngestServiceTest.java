package test.domain.ingest.lh;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.IngestReport;
import test.domain.notice.Notice;
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;
import test.domain.notice.RentTerms;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 15057999 + 15056765 두 응답으로 공급행이 단지 단위에서 주택형 단위로 다시 써지는지 본다. HTTP만 빠져 있다.
 *
 * <p>세 갈래를 한 공고에 다 담았다 — 주소가 맞아 쪼개지는 단지, LH 쪽에만 남는 주택형 행,
 * 주택형을 못 받아 단지 단위로 남는 마이홈 공급행.
 */
@DataJpaTest
class LhNoticeIngestServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-14T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final String GURI_ADDRESS = "경기도 구리시 체육관로74번길 67";
    private static final String NAMYANGJU_ADDRESS = "경기도 남양주시 순화궁로 458-58";

    @Autowired private NoticeRepository noticeRepository;
    @Autowired private NoticeSupplyRepository supplyRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    private LhNoticeIngestService service;
    private Long noticeId;

    @BeforeEach
    void setUp() {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            supplyRepository.deleteAll();
            noticeRepository.deleteAll();
        });
        committed.executeWithoutResult(status -> {
            Notice notice = noticeRepository.save(Notice.firstVersion("20989", null, snapshot()));
            supplyRepository.save(NoticeSupply.ofComplex(notice, 0, 1, "구리수택",
                    "4131010500108520000", GURI_ADDRESS, 50, 394,
                    new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L),
                    "https://myhome/1", "https://m.myhome/1"));
            supplyRepository.save(NoticeSupply.ofComplex(notice, 1, 3, "남양주별내 A24BL",
                    "4136011100108220000", NAMYANGJU_ADDRESS, 117, 872,
                    new RentTerms(22_896_000L, 1_115_000L, 21_751_000L, 103_000L),
                    "https://myhome/3", "https://m.myhome/3"));
            noticeId = notice.getId();
        });

        service = new LhNoticeIngestService(null, MAPPER, noticeRepository, supplyRepository,
                new LhSupplyInfoTypeResolver(), transactionManager, FIXED_CLOCK);
    }

    private NoticeSnapshot snapshot() {
        return new NoticeSnapshot("일반공고", LocalDate.of(2026, 8, 5).atStartOfDay(),
                "구리,남양주시 행복주택 예비입주자모집", DETAIL_URL,
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "행복주택", "LH 콜센터 : 1600-1004");
    }

    private IngestReport apply(String detailJson, String supplyJson) {
        Notice notice = noticeRepository.findById(noticeId).orElseThrow();
        IngestReport report = service.apply(notice, read(detailJson), read(supplyJson));
        entityManager.flush();
        entityManager.clear();
        return report;
    }

    private JsonNode read(String json) {
        return MAPPER.readTree(json);
    }

    private List<NoticeSupply> supplies() {
        return supplyRepository.findByNoticeIdOrderByDisplayOrder(noticeId);
    }

    @Test
    @DisplayName("주소로 짝을 찾은 단지는 주택형 수만큼 쪼개지고 임대조건·PNU가 복사된다")
    void splitsMatchedComplexIntoUnitTypeRows() {
        apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        List<NoticeSupply> guri = supplies().stream()
                .filter(supply -> "구리수택 행복주택".equals(supply.getLhComplexLabel()))
                .toList();

        assertThat(guri).hasSize(2);
        assertThat(guri).extracting(NoticeSupply::getTypeName).containsExactly("26", "36");
        assertThat(guri).extracting(NoticeSupply::getUnitSupplyCount).containsExactly(30, 20);
        assertThat(guri).extracting(NoticeSupply::getUnitTotalCount).containsExactly(200, 194);
        // 마이홈은 임대조건을 단지 단위로만 줘서 두 주택형 행에 같은 값이 복사된다.
        assertThat(guri).extracting(supply -> supply.getRentTerms().getDeposit())
                .containsOnly(37_224_000L);
        assertThat(guri).extracting(NoticeSupply::getHouseSn).containsOnly(1);
        assertThat(guri).extracting(NoticeSupply::getSuppliedPnu).containsOnly("4131010500108520000");
        assertThat(guri).extracting(NoticeSupply::getComplexSupplyCount).containsOnly(50);
        assertThat(guri).extracting(NoticeSupply::getMoveInYearMonth).containsOnly(YearMonth.of(2027, 11));
    }

    @Test
    @DisplayName("주소가 안 맞은 LH 주택형 행도 버리지 않되 마이홈 값 없이 남는다")
    void keepsLhOnlyRowsWithoutMyHomeValues() {
        apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        NoticeSupply lhOnly = supplies().stream()
                .filter(supply -> "남양주별내 A24블록".equals(supply.getLhComplexLabel()))
                .findFirst()
                .orElseThrow();

        assertThat(lhOnly.getUnitSupplyCount()).isEqualTo(117);
        assertThat(lhOnly.getHouseSn()).isNull();
        // 임대조건 칸이 전부 비면 @Embedded 자체가 null 로 읽힌다.
        assertThat(lhOnly.getRentTerms()).isNull();
        assertThat(lhOnly.getSuppliedPnu()).isNull();
        // 단지명으로는 dsSbd 를 찾았으므로 입주예정월은 붙는다.
        assertThat(lhOnly.getMoveInYearMonth()).isEqualTo(YearMonth.of(2027, 12));
    }

    @Test
    @DisplayName("주택형 행을 못 만든 마이홈 공급행은 단지 단위 그대로 남는다")
    void keepsUnsplitMyHomeRowAsComplexLevel() {
        apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        NoticeSupply leftover = supplies().stream()
                .filter(supply -> supply.getTypeName() == null)
                .findFirst()
                .orElseThrow();

        assertThat(supplies()).hasSize(4);
        assertThat(leftover.getHouseSn()).isEqualTo(3);
        assertThat(leftover.getComplexName()).isEqualTo("남양주별내 A24BL");
        assertThat(leftover.getComplexSupplyCount()).isEqualTo(117);
        assertThat(leftover.getRentTerms().getDeposit()).isEqualTo(22_896_000L);
        assertThat(supplies()).extracting(NoticeSupply::getDisplayOrder).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("LS_GMY·RFE는 숫자로 파싱하지 않고 원문 그대로 남긴다")
    void keepsLhMoneyAsRawText() {
        apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        assertThat(supplies()).extracting(NoticeSupply::getLhDepositText)
                .containsExactly("공고문 참조", "공고문 참조", "19546000", null);
        assertThat(supplies()).extracting(NoticeSupply::getLhMonthlyRentText)
                .containsExactly("공고문 참조", "공고문 참조", "195460", null);
    }

    @Test
    @DisplayName("주소는 유일한데 세대수가 다르면 짝으로 확정하지 않는다")
    void doesNotConfirmWhenUnitCountsConflict() {
        apply(CONFLICTING_UNIT_COUNT_DETAIL, SUPPLY_RESPONSE);

        NoticeSupply guri = supplies().stream()
                .filter(supply -> "구리수택 행복주택".equals(supply.getLhComplexLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(guri.getHouseSn()).isNull();
        // 마이홈 행 둘 다 쪼개지지 않아 단지 단위로 남는다.
        assertThat(supplies()).filteredOn(supply -> supply.getTypeName() == null).hasSize(2);
    }

    @Test
    @DisplayName("일정·접수처·첨부와 정정사유가 공고에 붙는다")
    void attachesScheduleReceptionAndAttachments() {
        apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        Notice notice = noticeRepository.findById(noticeId).orElseThrow();
        assertThat(notice.getCorrectionReason()).isEqualTo("공급호수 정정");
        assertThat(notice.getSchedules()).singleElement().satisfies(schedule -> {
            assertThat(schedule.getComplexLabel()).isEqualTo("구리수택 행복주택");
            assertThat(schedule.getContractBeginOn()).isEqualTo(LocalDate.of(2026, 9, 14));
        });
        assertThat(notice.getReceptionPlaces()).singleElement()
                .satisfies(place -> assertThat(place.getPhone()).isEqualTo("031-000-0000"));
        // 값 대신 컬럼 이름을 담은 행("첨부파일명")은 URL 이 http 가 아니라 걸러진다.
        assertThat(notice.getAttachments()).extracting(attachment -> attachment.getName())
                .containsExactly("공고문.pdf", "조감도.jpg");
        assertThat(notice.getSourcePanId()).isEqualTo("2015122300020536");
        assertThat(notice.getLhSupplyInfoTypeCode()).isEqualTo("063");
        assertThat(notice.getLhFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 받은 공고는 다시 부르지 않는다")
    void doesNotRefetchAnAlreadyFetchedNotice() {
        apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        IngestReport second = apply(DETAIL_RESPONSE, SUPPLY_RESPONSE);

        assertThat(second.unchanged()).isOne();
        assertThat(supplies()).hasSize(4);
    }

    @Test
    @DisplayName("LH 공급정보코드를 모르는 공급유형은 호출 자체를 건너뛴다")
    void skipsSupplyTypesWithoutAKnownLhCode() {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            Notice notice = noticeRepository.save(Notice.firstVersion("21000", null,
                    new NoticeSnapshot("일반공고", null, "통합공공임대 모집", DETAIL_URL,
                            null, null, null, "LH", "아파트", "통합공공임대", null)));
            noticeId = notice.getId();
        });

        assertThat(apply(DETAIL_RESPONSE, SUPPLY_RESPONSE).rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE, 1);
    }

    private static final String DETAIL_URL =
            "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do"
                    + "?panId=2015122300020536&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10";

    private static final String DETAIL_RESPONSE = """
            [{"dsEtcInfo":[{"CRC_RSN":"공급호수 정정","ETC_CTS":""}]},
             {"dsSplScdl":[{"SBD_LGO_NM":"구리수택 행복주택","ACP_DTTM":"2026.08.18 10:00 ~ 08.20 17:00",
               "PPR_SBM_OPE_ANC_DT":"20260901","PPR_ACP_ST_DT":"20260907","PPR_ACP_CLSG_DT":"20260910",
               "CTRT_ST_DT":"20260914","CTRT_ED_DT":"20260916"}]},
             {"dsCtrtPlc":[{"CTRT_PLC_ADR":"경기도 구리시 안내로 1","CTRT_PLC_DTL_ADR":"2층",
               "TSK_ST_DTTM":"09:00","TSK_ED_DTTM":"18:00","SIL_OFC_TLNO":"031-000-0000",
               "SIL_OFC_GUD_FCTS":"점심시간 12~13시 제외"}]},
             {"dsSbd":[
               {"LCC_NT_NM":"구리수택 행복주택","LGDN_ADR":"경기도 구리시 체육관로74번길 67",
                "LGDN_DTL_ADR":"","HSH_CNT":"394","HTN_FMLA_DESC":"개별난방","DDO_AR":"26.70~36.32",
                "MVIN_XPC_YM":"202711","SPL_INF_GUD_FCTS":"안내문"},
               {"LCC_NT_NM":"남양주별내 A24블록","LGDN_ADR":"경기도 남양주시 순화궁로 999",
                "LGDN_DTL_ADR":"","HSH_CNT":"872","HTN_FMLA_DESC":"지역난방","DDO_AR":"36.32",
                "MVIN_XPC_YM":"202712","SPL_INF_GUD_FCTS":"안내문"}]},
             {"dsAhflInfo":[
               {"SL_PAN_AHFL_DS_CD_NM":"공고문","CMN_AHFL_NM":"첨부파일명","AHFL_URL":"다운로드"},
               {"SL_PAN_AHFL_DS_CD_NM":"공고문","CMN_AHFL_NM":"공고문.pdf",
                "AHFL_URL":"https://apply.lh.or.kr/files/notice.pdf"}]},
             {"dsSbdAhfl":[{"LS_SPL_INF_UPL_FL_DS_CD_NM":"단지조감도","CMN_AHFL_NM":"조감도.jpg",
               "AHFL_URL":"https://apply.lh.or.kr/files/view.jpg","LCC_NT_NM":"구리수택 행복주택"}]},
             {"resHeader":[{"RS_DTTM":"20260814100000","SS_CODE":"Y"}]}]
            """;

    /** 주소는 그대로인데 dsSbd 세대수만 394 → 400 으로 어긋난 응답. */
    private static final String CONFLICTING_UNIT_COUNT_DETAIL =
            DETAIL_RESPONSE.replace("\"HSH_CNT\":\"394\"", "\"HSH_CNT\":\"400\"");

    private static final String SUPPLY_RESPONSE = """
            [{"dsList01":[
               {"SBD_LGO_NM":"구리수택 행복주택","HTY_NNA":"26","DDO_AR":"26.70","SPL_AR":"36.80",
                "HSH_CNT":"200","NOW_HSH_CNT":"30","LS_GMY":"공고문 참조","RFE":"공고문 참조"},
               {"SBD_LGO_NM":"구리수택 행복주택","HTY_NNA":"36","DDO_AR":"36.32","SPL_AR":"49.82",
                "HSH_CNT":"194","NOW_HSH_CNT":"20","LS_GMY":"공고문 참조","RFE":"공고문 참조"},
               {"SBD_LGO_NM":"남양주별내 A24블록","HTY_NNA":"36","DDO_AR":"36.32","SPL_AR":"49.82",
                "HSH_CNT":"872","NOW_HSH_CNT":"117","LS_GMY":"19546000","RFE":"195460"}]},
             {"resHeader":[{"RS_DTTM":"20260814100000","SS_CODE":"Y"}]}]
            """;
}
