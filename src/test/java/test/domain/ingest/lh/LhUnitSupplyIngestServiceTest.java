package test.domain.ingest.lh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.notice.LhUnitSupply;
import test.domain.notice.LhUnitSupplyBatch;
import test.domain.notice.LhUnitSupplyBatchRepository;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNotice;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.source.SourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-08-13에 실제 15056765를 호출해 받은 응답 그대로 태운다. HTTP만 빠져 있다. */
@DataJpaTest
class LhUnitSupplyIngestServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-13T04:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private LhUnitSupplyBatchRepository batchRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    private NoticeVersion lhNotice;
    private LhUnitSupplyIngestService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            batchRepository.deleteAll();
            noticeVersionRepository.deleteAll();
            recruitmentNoticeRepository.deleteAll();
        });
        committed.executeWithoutResult(status -> {
            RecruitmentNotice root = recruitmentNoticeRepository.save(
                    new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "18780"));
            lhNotice = noticeVersionRepository.save(NoticeVersion.firstVersion(
                    root, "18780", null, snapshot(
                            "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do"
                                    + "?panId=2015122300018780&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07&mi=1026")));
        });
        service = new LhUnitSupplyIngestService(null, MAPPER, noticeVersionRepository, batchRepository,
                new LhSupplyInfoTypeResolver(), transactionManager, FIXED_CLOCK);
    }

    @Test
    @DisplayName("한 PAN_ID 응답에 섞여 온 여러 단지·주택형 행을 그대로 저장한다")
    void storesEveryComplexAndUnitTypeRowFromOnePanId() {
        JsonNode root = MAPPER.readTree(UNIT_SUPPLY_RESPONSE);
        IngestReport report = service.apply(lhNotice, root);
        entityManager.flush();
        entityManager.clear();

        LhUnitSupplyBatch batch = batchRepository.findByNoticeVersionId(lhNotice.getId()).orElseThrow();

        assertThat(report.created()).isOne();
        assertThat(batch.getSourcePanId()).isEqualTo("2015122300018780");
        assertThat(batch.getRequestedSupplyInfoTypeCode()).isEqualTo("062");
        assertThat(batch.getSourceRespondedAt()).isEqualTo(LocalDateTime.of(2026, 8, 13, 12, 11, 52));
        assertThat(batch.isUnitSupplyDatasetPresent()).isTrue();

        assertThat(batch.getUnitSupplies()).hasSize(3);
        assertThat(batch.getUnitSupplies()).extracting(LhUnitSupply::getComplexLabel)
                .containsExactly("울산구영1BL 국민임대", "울산구영2BL국민임대", "울산매곡 휴먼시아 국민임대주택");
        assertThat(batch.getUnitSupplies().get(0).getExclusiveArea()).isEqualByComparingTo(new BigDecimal("59.94"));
        assertThat(batch.getUnitSupplies().get(0).getSuppliedUnitCount()).isEqualTo(20);
        assertThat(batch.getUnitSupplies().get(0).getTotalUnitCount()).isEqualTo(235);
    }

    @Test
    @DisplayName("같은 응답을 다시 적용하면 배치를 중복 저장하지 않는다")
    void appliesIdempotently() {
        JsonNode root = MAPPER.readTree(UNIT_SUPPLY_RESPONSE);

        IngestReport first = service.apply(lhNotice, root);
        IngestReport second = service.apply(lhNotice, root);

        assertThat(first.created()).isOne();
        assertThat(second.unchanged()).isOne();
        assertThat(batchRepository.count()).isOne();
    }

    @Test
    @DisplayName("dsList01 키가 없으면 행 0개와 별개로 dataset 부재로 남긴다")
    void storesDatasetAbsenceSeparatelyFromEmptyRows() {
        IngestReport report = service.apply(lhNotice, MAPPER.readTree("""
                [{"resHeader":[{"RS_DTTM":"20260813121152","SS_CODE":"Y"}]}]
                """));

        LhUnitSupplyBatch batch = batchRepository.findByNoticeVersionId(lhNotice.getId()).orElseThrow();
        assertThat(report.created()).isOne();
        assertThat(batch.getUnitSupplies()).isEmpty();
        assertThat(batch.isUnitSupplyDatasetPresent()).isFalse();
    }

    @Test
    @DisplayName("통합공공임대는 공급정보코드를 아직 몰라 HTTP 호출 전에 제외한다")
    void skipsHttpCallForIntegratedPublicRentalBeforeAnyRequest() {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "20999"));
        NoticeVersion integratedNotice = noticeVersionRepository.save(NoticeVersion.firstVersion(
                root, "20999", null, integratedSnapshot()));

        // lhApiClient 를 null 로 넘긴다 — HTTP 를 실제로 시도하면 NPE 로 드러난다.
        IngestReport report = service.applyOne(integratedNotice);

        assertThat(report.rejectedByReason())
                .containsEntry(IngestRejectionReason.UNSUPPORTED_LH_SUPPLEMENT_TYPE, 1);
        assertThat(batchRepository.existsByNoticeVersionId(integratedNotice.getId())).isFalse();
    }

    private NoticeSnapshot snapshot(String detailUrl) {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 1, 0, 0),
                "울산 국민임대 입주자 모집", detailUrl,
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "국민임대", "LH 콜센터 : 1600-1004");
    }

    private NoticeSnapshot integratedSnapshot() {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 5, 0, 0),
                "통합공공임대 입주자 모집",
                "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do"
                        + "?panId=2015122300099999&ccrCnntSysDsCd=03&uppAisTpCd=06",
                LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "통합공공임대", "LH 콜센터 : 1600-1004");
    }

    /** 2026-08-13 실제 호출 결과에서 앞 세 행만 잘라 붙였다(원본은 단지 6곳, 9행). */
    private static final String UNIT_SUPPLY_RESPONSE = """
            [{"dsSch":[{"PAN_ID":"2015122300018780","CCR_CNNT_SYS_DS_CD":"03","SPL_INF_TP_CD":"062",
                        "UPP_AIS_TP_CD":"06","AIS_TP_CD":"07"}]},
             {"dsList01Nm":[{"RFE":"월임대료(원)","NOW_HSH_CNT":"금회공급 세대수","HSH_CNT":"세대수",
                             "HTY_NNA":"주택형","LS_GMY":"임대보증금(원)","SBD_LGO_NM":"단지명",
                             "DDO_AR":"전용면적(㎡)","SPL_AR":"공급면적"}],
              "resHeader":[{"RS_DTTM":"20260813121152","SS_CODE":"Y"}],
              "dsList01":[
                {"RFE":"공고문 참조","NOW_HSH_CNT":"20","HSH_CNT":"235","HTY_NNA":"59㎡",
                 "LS_GMY":"공고문 참조","SBD_LGO_NM":"울산구영1BL 국민임대","DDO_AR":"59.94","SPL_AR":"82.1224"},
                {"RFE":"공고문 참조","NOW_HSH_CNT":"15","HSH_CNT":"407","HTY_NNA":"46㎡",
                 "LS_GMY":"공고문 참조","SBD_LGO_NM":"울산구영2BL국민임대","DDO_AR":"46.9","SPL_AR":"67.7042"},
                {"RFE":"공고문 참조","NOW_HSH_CNT":"20","HSH_CNT":"248","HTY_NNA":"39㎡",
                 "LS_GMY":"공고문 참조","SBD_LGO_NM":"울산매곡 휴먼시아 국민임대주택","DDO_AR":"39.45","SPL_AR":"57.5287"}
              ]}
            ]
            """;
}
