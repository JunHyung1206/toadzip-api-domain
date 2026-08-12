package test.domain.ingest.lh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import jakarta.persistence.EntityManager;
import test.domain.ingest.IngestReport;
import test.domain.ingest.OpenApiClient;
import test.domain.notice.NoticeAttachment;
import test.domain.notice.NoticeAttachmentRepository;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeSupplement;
import test.domain.notice.NoticeSupplementRepository;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.SourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LH 15057999 실제 응답 모양 그대로 태운다. HTTP만 빠져 있다. */
@DataJpaTest
@ExtendWith(OutputCaptureExtension.class)
class LhNoticeDetailIngestServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeAttachmentRepository attachmentRepository;
    @Autowired
    private NoticeSupplementRepository supplementRepository;
    @Autowired
    private EntityManager entityManager;

    private NoticeVersion lhNotice;
    private LhNoticeDetailIngestService service;

    @BeforeEach
    void setUp() {
        lhNotice = noticeVersionRepository.save(NoticeVersion.firstVersion(
                "20942", SourceSystem.MYHOME_PORTAL, snapshot(
                        "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do"
                                + "?panId=2015122300020501&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10&mi=1026")));
        service = new LhNoticeDetailIngestService(
                null, MAPPER, noticeVersionRepository, supplementRepository);
    }

    @Test
    @DisplayName("마이홈 공고 링크에서 LH 호출 파라미터 넷을 뽑아낸다")
    void readsLhCallParametersFromNoticeLink() {
        List<NoticeVersion> lhNotices = noticeVersionRepository.findByDetailUrlContaining("panId");

        assertThat(lhNotices).hasSize(1);
        assertThat(lhNotices.get(0).getDetailUrl())
                .contains("panId=2015122300020501", "ccrCnntSysDsCd=03", "uppAisTpCd=06", "aisTpCd=10");
    }

    @Test
    @DisplayName("실제 LH 상세 응답을 공고 보충 aggregate 하나로 저장한다")
    void storesCompleteSupplementAggregate() {
        JsonNode root = MAPPER.readTree(LH_DETAIL_RESPONSE);
        IngestReport report = service.apply(lhNotice, root);
        entityManager.flush();
        entityManager.clear();

        NoticeSupplement supplement = supplementRepository
                .findByNoticeVersionId(lhNotice.getId()).orElseThrow();

        assertThat(report.created()).isOne();
        assertThat(supplement.getCorrectionReason()).contains("접수기간 요일 오기재");
        assertThat(supplement.getSchedules()).singleElement().satisfies(schedule -> {
            assertThat(schedule.getComplexLabel()).isEqualTo("부산정관 A4블록 행복주택");
            assertThat(schedule.getDocumentTargetAnnouncedOn()).isEqualTo(LocalDate.of(2026, 9, 7));
            assertThat(schedule.getContractEndOn()).isEqualTo(LocalDate.of(2027, 1, 21));
        });
        assertThat(supplement.getReceptionPlaces()).singleElement().satisfies(place -> {
            assertThat(place.getPhone()).isEqualTo("1600-1004");
            assertThat(place.getDetailAddress()).isEqualTo("1층 105호");
        });
        assertThat(supplement.getComplexSnapshots()).singleElement().satisfies(complex -> {
            assertThat(complex.getTotalUnitCount()).isEqualTo(384);
            assertThat(complex.getExpectedMoveInYearMonth()).isEqualTo(YearMonth.of(2027, 11));
        });
        assertThat(supplement.getAttachments()).extracting(NoticeAttachment::getKind)
                .containsExactly("공고문(hwp)", "공고문(PDF)", "단지조감도");
        assertThat(supplement.getAttachments()).extracting(NoticeAttachment::getComplexLabel)
                .containsExactly(null, null, "부산정관 A4블록 행복주택");

        NoticeVersion unchangedNotice = noticeVersionRepository.findById(lhNotice.getId()).orElseThrow();
        assertThat(unchangedNotice.getApplicationBeginOn()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(unchangedNotice.getApplicationEndOn()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(unchangedNotice.getWinnerAnnouncedOn()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(attachmentRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 LH 응답을 다시 적용하면 보충 aggregate를 중복 저장하지 않는다")
    void appliesIdempotently() {
        JsonNode root = MAPPER.readTree(LH_DETAIL_RESPONSE);

        IngestReport first = service.apply(lhNotice, root);
        IngestReport second = service.apply(lhNotice, root);

        assertThat(first.created()).isOne();
        assertThat(second.unchanged()).isOne();
        assertThat(supplementRepository.count()).isOne();
    }

    @Test
    @DisplayName("추가 데이터가 없는 정상 응답도 조회 완료 표시를 남긴다")
    void storesEmptySupplement() {
        IngestReport report = service.apply(lhNotice, MAPPER.readTree("""
                [{"resHeader":[{"RS_DTTM":"20260812123144","SS_CODE":"Y"}]}]
                """));

        NoticeSupplement supplement = supplementRepository
                .findByNoticeVersionId(lhNotice.getId()).orElseThrow();
        assertThat(report.created()).isOne();
        assertThat(supplement.getSchedules()).isEmpty();
        assertThat(supplement.getReceptionPlaces()).isEmpty();
        assertThat(supplement.getComplexSnapshots()).isEmpty();
        assertThat(supplement.getAttachments()).isEmpty();
    }

    @Test
    @DisplayName("값이 있는 날짜를 변환하지 못하면 원천 위치와 원문을 경고한다")
    void logsUnparseableOptionalDates(CapturedOutput output) {
        service.apply(lhNotice, MAPPER.readTree("""
                [
                  {"dsSplScdl":[{"SBD_LGO_NM":"테스트 단지","CTRT_ED_DT":"2027.99.99"}]},
                  {"dsSbd":[{"LCC_NT_NM":"테스트 단지","MVIN_XPC_YM":"2027.13"}]},
                  {"resHeader":[{"SS_CODE":"Y"}]}
                ]
                """));

        assertThat(output).contains(
                "dsSplScdl[0].CTRT_ED_DT", "2027.99.99",
                "dsSbd[0].MVIN_XPC_YM", "2027.13");
    }

    @Test
    @DisplayName("원천이 값 대신 컬럼 이름을 담아 보내는 행이 섞여 온다")
    void sourceMixesLabelRowsIntoTheSameDataset() {
        JsonNode root = MAPPER.readTree(LH_DETAIL_RESPONSE);

        List<LhNoticeDetail.NoticeFile> rows = OpenApiClient.findRows(root, "dsAhflInfoNm").stream()
                .map(row -> MAPPER.convertValue(row, LhNoticeDetail.NoticeFile.class))
                .toList();

        // dsAhflInfoNm 은 데이터가 아니라 화면 라벨이다. URL 자리에 "다운로드" 가 들어 있다.
        assertThat(rows).singleElement()
                .satisfies(row -> assertThat(row.url()).isEqualTo("다운로드"));
    }

    private NoticeSnapshot snapshot(String detailUrl) {
        return new NoticeSnapshot(NoticeChangeStatus.CORRECTION, LocalDateTime.of(2026, 8, 5, 0, 0),
                "부산 정관 행복주택 예비입주자 모집", detailUrl,
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "행복주택", "LH 콜센터 : 1600-1004");
    }

    /** 실제 응답을 잘라 붙였다. 데이터셋이 배열로 여러 개 오고, 값 대신 라벨을 담은 짝(`...Nm`)이 같이 온다. */
    private static final String LH_DETAIL_RESPONSE = """
            [
             {"dsSch":[{"PAN_ID":"2015122300020501","CCR_CNNT_SYS_DS_CD":"03","SPL_INF_TP_CD":"062"}]},
             {"dsEtcInfo":[{"ETC_CTS":"○ 청약신청은 인터넷 PC 또는 모바일로 가능합니다.",
                            "CRC_RSN":"4. 공급일정 및 신청방법 등\\n■ 공급일정 표 - 접수기간 요일 오기재 일부수정"}]},
             {"dsEtcInfoNm":[{"ETC_CTS":"기타사항","CRC_RSN":"정정/취소사유"}]},
             {"dsSplScdl":[{
               "SBD_LGO_NM":"부산정관 A4블록 행복주택",
               "ACP_DTTM":"2026.08.18 10:00 ~ 2026.08.20 16:00",
               "PPR_SBM_OPE_ANC_DT":"2026.09.07","PPR_ACP_ST_DT":"2026.09.08",
               "PPR_ACP_CLSG_DT":"2026.09.14","CTRT_ST_DT":"2027.01.19","CTRT_ED_DT":"2027.01.21"}]},
             {"dsCtrtPlc":[{
               "CTRT_PLC_ADR":"부산광역시 기장군 정관읍 정관중앙로 100",
               "CTRT_PLC_DTL_ADR":"1층 105호","TSK_ST_DTTM":"2026.08.19 10:00",
               "TSK_ED_DTTM":"2026.08.19 16:00","SIL_OFC_TLNO":"1600-1004",
               "SIL_OFC_GUD_FCTS":"고령자 및 장애인 현장접수"}]},
             {"dsSbd":[{
               "LCC_NT_NM":"부산정관 A4블록 행복주택","LGDN_ADR":"부산광역시 기장군 정관읍 용수리",
               "LGDN_DTL_ADR":"123","HSH_CNT":"384","HTN_FMLA_DESC":"지역난방",
               "DDO_AR":"26.78~44.84","MVIN_XPC_YM":"2027.11"}]},
             {"dsAhflInfo":[
               {"AHFL_URL":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68041511",
                "SL_PAN_AHFL_DS_CD_NM":"공고문(hwp)","CMN_AHFL_NM":"부산정관A4 행복주택 모집공고문.hwp"},
               {"AHFL_URL":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68041512",
                "SL_PAN_AHFL_DS_CD_NM":"공고문(PDF)","CMN_AHFL_NM":"부산정관A4 행복주택 모집공고문.pdf"},
               {"AHFL_URL":"http-invalid","SL_PAN_AHFL_DS_CD_NM":"공고문(PDF)",
                "CMN_AHFL_NM":"잘못된 URL.pdf"}]},
             {"dsAhflInfoNm":[{"AHFL_URL":"다운로드","SL_PAN_AHFL_DS_CD_NM":"파일구분명","CMN_AHFL_NM":"첨부파일명"}]},
             {"dsSbdAhfl":[
               {"LCC_NT_NM":"부산정관 A4블록 행복주택",
                "AHFL_URL":"https://apply.lh.or.kr/lhapply/lhImageView2.do?fileid=68039186",
                "LS_SPL_INF_UPL_FL_DS_CD_NM":"단지조감도","CMN_AHFL_NM":"단지조감도.jpg"}]},
             {"dsSbdAhflNm":[{"LCC_NT_NM":"단지명","AHFL_URL":"다운로드",
                              "LS_SPL_INF_UPL_FL_DS_CD_NM":"파일구분명","CMN_AHFL_NM":"첨부파일명"}]},
             {"resHeader":[{"RS_DTTM":"20260812123144","SS_CODE":"Y"}]}
            ]
            """;
}
