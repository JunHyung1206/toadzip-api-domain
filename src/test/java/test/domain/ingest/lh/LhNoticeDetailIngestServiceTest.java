package test.domain.ingest.lh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LH 15057999 실제 응답 모양 그대로 태운다. HTTP만 빠져 있다. */
@DataJpaTest
class LhNoticeDetailIngestServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeAttachmentRepository attachmentRepository;
    @Autowired
    private NoticeSupplementRepository supplementRepository;

    private NoticeVersion lhNotice;

    @BeforeEach
    void setUp() {
        lhNotice = noticeVersionRepository.save(NoticeVersion.firstVersion(
                "20942", SourceSystem.MYHOME_PORTAL, snapshot(
                        "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do"
                                + "?panId=2015122300020501&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10&mi=1026")));
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
    @DisplayName("마이홈이 안 주는 정정사유가 LH 상세에서 채워진다")
    void fillsCorrectionReasonFromLh() {
        JsonNode root = MAPPER.readTree(LH_DETAIL_RESPONSE);

        String reason = OpenApiClient.findRows(root, "dsEtcInfo").stream()
                .map(row -> MAPPER.convertValue(row, LhNoticeDetail.EtcInfo.class).correctionReason())
                .findFirst()
                .orElse(null);
        NoticeSupplement supplement = supplementRepository.save(
                new NoticeSupplement(lhNotice, SourceSystem.LH_CHEONGYAK_PLUS, reason));

        assertThat(supplement.getCorrectionReason()).contains("접수기간 요일 오기재");
    }

    @Test
    @DisplayName("공고문 파일과 단지 이미지가 한 테이블에 순서대로 담긴다")
    void storesNoticeFilesAndComplexImagesTogether() {
        JsonNode root = MAPPER.readTree(LH_DETAIL_RESPONSE);
        NoticeSupplement supplement = new NoticeSupplement(lhNotice, SourceSystem.LH_CHEONGYAK_PLUS, null);

        int order = 0;
        for (JsonNode row : OpenApiClient.findRows(root, "dsAhflInfo")) {
            LhNoticeDetail.NoticeFile file = MAPPER.convertValue(row, LhNoticeDetail.NoticeFile.class);
            supplement.addAttachment(order++, file.kind(), file.name(), file.url(), null);
        }
        for (JsonNode row : OpenApiClient.findRows(root, "dsSbdAhfl")) {
            LhNoticeDetail.ComplexImage image = MAPPER.convertValue(row, LhNoticeDetail.ComplexImage.class);
            supplement.addAttachment(
                    order++, image.kind(), image.name(), image.url(), image.complexName());
        }
        supplementRepository.saveAndFlush(supplement);

        assertThat(attachmentRepository.findAll()).extracting(NoticeAttachment::getKind)
                .containsExactly("공고문(hwp)", "공고문(PDF)", "단지조감도");
        // 이미지에만 단지명이 붙는다. 우리 단지에 붙인 게 아니라 원천이 말한 이름이다.
        assertThat(attachmentRepository.findAll()).extracting(NoticeAttachment::getComplexLabel)
                .containsExactly(null, null, "부산정관 A4블록 행복주택");
        assertThat(supplementRepository.existsByNoticeVersionId(lhNotice.getId())).isTrue();
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
             {"dsAhflInfo":[
               {"AHFL_URL":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68041511",
                "SL_PAN_AHFL_DS_CD_NM":"공고문(hwp)","CMN_AHFL_NM":"부산정관A4 행복주택 모집공고문.hwp"},
               {"AHFL_URL":"https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68041512",
                "SL_PAN_AHFL_DS_CD_NM":"공고문(PDF)","CMN_AHFL_NM":"부산정관A4 행복주택 모집공고문.pdf"}]},
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
