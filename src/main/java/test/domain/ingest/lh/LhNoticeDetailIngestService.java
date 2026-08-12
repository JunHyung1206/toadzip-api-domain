package test.domain.ingest.lh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import test.domain.ingest.IngestReport;
import test.domain.ingest.IngestRejectionReason;
import test.domain.ingest.OpenApiClient;
import test.domain.ingest.SourceValues;
import test.domain.notice.NoticeSupplement;
import test.domain.notice.NoticeSupplementRepository;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.SourceSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 15057999 → 이미 적재된 {@link NoticeVersion} 에 LH 상세를 덧입힌다.
 *
 * <p><b>왜 두 원천을 겹치나.</b> 마이홈(HWSPR02)이 공고의 뼈대를 준다 — 정정 체인, 상태, PNU, 임대조건.
 * 그런데 <b>정정 사유·공고문 원문·단지 이미지</b>는 안 준다. LH 상세에는 그게 있다.
 * 기준 원천을 바꾸는 게 아니라 마이홈이 비운 칸만 채운다.
 *
 * <p><b>어떻게 잇나.</b> 마이홈이 주는 공고 링크({@code notice_version.detail_url})가 LH 청약 사이트 주소라
 * 그 안에 LH 호출에 필요한 값이 통째로 박혀 있다. 별도 매칭이 필요 없다.
 *
 * <pre>
 *   .../selectWrtancInfo.do?panId=2015122300020476&amp;ccrCnntSysDsCd=03&amp;uppAisTpCd=06&amp;aisTpCd=10
 *                                  PAN_ID           CCR_CNNT_SYS_DS_CD   UPP_AIS_TP_CD   AIS_TP_CD
 * </pre>
 *
 * <p>공고버전 68건 중 65건이 LH 건이고, 나머지 3건은 지방공사라 이 원천에 없다.
 */
@Slf4j
@Service
public class LhNoticeDetailIngestService {

    private static final String PATH = "lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1";

    /** LH 링크가 이 파라미터를 갖고 있으면 LH 공고다. */
    private static final String LH_LINK_MARK = "panId";

    /**
     * 공급정보구분코드. 원천은 필수로 요구하는데 공고 링크에는 없다.
     * 임대주택 코드 062 로 고정해도 실측 65건이 전부 응답했다(행복주택·영구임대·국민임대·신혼희망타운 포함).
     */
    private static final String SUPPLY_INFO_TYPE = "062";

    private final OpenApiClient lhApiClient;
    private final ObjectMapper objectMapper;
    private final NoticeVersionRepository noticeVersionRepository;
    private final NoticeSupplementRepository supplementRepository;

    public LhNoticeDetailIngestService(@Qualifier("lhApiClient") OpenApiClient lhApiClient,
                                       ObjectMapper objectMapper,
                                       NoticeVersionRepository noticeVersionRepository,
                                       NoticeSupplementRepository supplementRepository) {
        this.lhApiClient = lhApiClient;
        this.objectMapper = objectMapper;
        this.noticeVersionRepository = noticeVersionRepository;
        this.supplementRepository = supplementRepository;
    }

    /** 공고를 먼저 적재해 둔 뒤에 돌린다. LH 공고가 아닌 버전은 건드리지 않는다. */
    public IngestReport ingest() {
        IngestReport report = IngestReport.empty();
        for (NoticeVersion version : noticeVersionRepository.findByDetailUrlContaining(LH_LINK_MARK)) {
            report = report.plus(applyOne(version));
        }
        return report;
    }

    private IngestReport applyOne(NoticeVersion version) {
        // 공고버전이 불변이라 보충 스냅샷도 불변이다. 이미 받았으면 다시 부르지 않는다.
        if (supplementRepository.existsByNoticeVersionId(version.getId())) {
            return IngestReport.oneUnchanged();
        }

        MultiValueMap<String, String> params = paramsFrom(version);
        if (params == null) {
            log.warn("LH 링크에서 호출 파라미터를 못 뽑아 건너뜁니다: {}", version.getDetailUrl());
            return IngestReport.oneRejected(IngestRejectionReason.INVALID_SOURCE_ROW);
        }

        try {
            return apply(version, lhApiClient.getRaw(PATH, params));
        } catch (RuntimeException e) {
            log.warn("LH 공고 상세 적재에 실패했습니다: sourceNoticeId={}", version.getSourceNoticeId(), e);
            return IngestReport.oneFailed();
        }
    }

    /** HTTP 호출과 분리된 aggregate 저장 경계. 모든 자식을 구성한 뒤 한 번만 저장한다. */
    IngestReport apply(NoticeVersion version, JsonNode root) {
        if (supplementRepository.existsByNoticeVersionId(version.getId())) {
            return IngestReport.oneUnchanged();
        }

        NoticeSupplement supplement = new NoticeSupplement(
                version, SourceSystem.LH_CHEONGYAK_PLUS, correctionReason(root));
        addSchedules(supplement, root);
        addReceptionPlaces(supplement, root);
        addComplexSnapshots(supplement, root);
        addAttachments(supplement, root);
        supplementRepository.save(supplement);
        return IngestReport.oneCreated();
    }

    /**
     * 마이홈이 준 LH 링크에서 호출 파라미터를 뽑는다.
     * 넷 중 하나라도 없으면 부를 수 없으므로 null 을 돌려주고 건너뛴다.
     */
    private MultiValueMap<String, String> paramsFrom(NoticeVersion version) {
        MultiValueMap<String, String> query = UriComponentsBuilder
                .fromUriString(version.getDetailUrl())
                .build()
                .getQueryParams();

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (!copy(query, "panId", params, "PAN_ID")
                || !copy(query, "ccrCnntSysDsCd", params, "CCR_CNNT_SYS_DS_CD")
                || !copy(query, "uppAisTpCd", params, "UPP_AIS_TP_CD")
                || !copy(query, "aisTpCd", params, "AIS_TP_CD")) {
            return null;
        }
        params.add("SPL_INF_TP_CD", SUPPLY_INFO_TYPE);
        params.add("PG_SZ", "100");
        params.add("PAGE", "1");
        return params;
    }

    private boolean copy(MultiValueMap<String, String> from, String fromKey,
                         MultiValueMap<String, String> to, String toKey) {
        String value = SourceValues.trimToNull(from.getFirst(fromKey));
        if (value == null) {
            return false;
        }
        to.add(toKey, value);
        return true;
    }

    private String correctionReason(JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, "dsEtcInfo")) {
            String reason = SourceValues.trimToNull(
                    objectMapper.convertValue(row, LhNoticeDetail.EtcInfo.class).correctionReason());
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private void addSchedules(NoticeSupplement supplement, JsonNode root) {
        int sourceOrder = 0;
        for (JsonNode row : OpenApiClient.findRows(root, "dsSplScdl")) {
            int rowOrder = sourceOrder++;
            LhNoticeDetail.Schedule schedule = objectMapper.convertValue(row, LhNoticeDetail.Schedule.class);
            String complexName = SourceValues.trimToNull(schedule.complexName());
            String applicationPeriod = SourceValues.trimToNull(schedule.applicationPeriod());
            LocalDate documentTargetAnnouncementDate = date(
                    "dsSplScdl[%d].PPR_SBM_OPE_ANC_DT".formatted(rowOrder),
                    schedule.documentTargetAnnouncementDate());
            LocalDate documentSubmissionBeginDate = date(
                    "dsSplScdl[%d].PPR_ACP_ST_DT".formatted(rowOrder),
                    schedule.documentSubmissionBeginDate());
            LocalDate documentSubmissionEndDate = date(
                    "dsSplScdl[%d].PPR_ACP_CLSG_DT".formatted(rowOrder),
                    schedule.documentSubmissionEndDate());
            LocalDate contractBeginDate = date(
                    "dsSplScdl[%d].CTRT_ST_DT".formatted(rowOrder), schedule.contractBeginDate());
            LocalDate contractEndDate = date(
                    "dsSplScdl[%d].CTRT_ED_DT".formatted(rowOrder), schedule.contractEndDate());
            if (complexName == null && applicationPeriod == null
                    && documentTargetAnnouncementDate == null
                    && documentSubmissionBeginDate == null
                    && documentSubmissionEndDate == null
                    && contractBeginDate == null
                    && contractEndDate == null) {
                continue;
            }
            supplement.addSchedule(
                    supplement.getSchedules().size(),
                    complexName,
                    applicationPeriod,
                    documentTargetAnnouncementDate,
                    documentSubmissionBeginDate,
                    documentSubmissionEndDate,
                    contractBeginDate,
                    contractEndDate);
        }
    }

    private void addReceptionPlaces(NoticeSupplement supplement, JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, "dsCtrtPlc")) {
            LhNoticeDetail.Reception reception = objectMapper.convertValue(row, LhNoticeDetail.Reception.class);
            String address = SourceValues.trimToNull(reception.address());
            String detailAddress = SourceValues.trimToNull(reception.detailAddress());
            String operationBegin = SourceValues.trimToNull(reception.operationBegin());
            String operationEnd = SourceValues.trimToNull(reception.operationEnd());
            String phone = SourceValues.trimToNull(reception.phone());
            String guidance = SourceValues.trimToNull(reception.guidance());
            if (address == null && detailAddress == null && operationBegin == null
                    && operationEnd == null && phone == null && guidance == null) {
                continue;
            }
            supplement.addReceptionPlace(supplement.getReceptionPlaces().size(), address, detailAddress,
                    operationBegin, operationEnd, phone, guidance);
        }
    }

    private void addComplexSnapshots(NoticeSupplement supplement, JsonNode root) {
        int sourceOrder = 0;
        for (JsonNode row : OpenApiClient.findRows(root, "dsSbd")) {
            int rowOrder = sourceOrder++;
            LhNoticeDetail.ComplexSnapshot complex =
                    objectMapper.convertValue(row, LhNoticeDetail.ComplexSnapshot.class);
            String complexName = SourceValues.trimToNull(complex.complexName());
            String lotAddress = SourceValues.trimToNull(complex.lotAddress());
            String lotDetailAddress = SourceValues.trimToNull(complex.lotDetailAddress());
            Integer totalUnitCount = SourceValues.toInt(complex.totalUnitCount());
            String heatingDescription = SourceValues.trimToNull(complex.heatingDescription());
            String exclusiveAreaRange = SourceValues.trimToNull(complex.exclusiveAreaRange());
            YearMonth expectedMoveInYearMonth = yearMonth(
                    "dsSbd[%d].MVIN_XPC_YM".formatted(rowOrder), complex.expectedMoveInYearMonth());
            if (complexName == null && lotAddress == null && lotDetailAddress == null
                    && totalUnitCount == null && heatingDescription == null && exclusiveAreaRange == null
                    && expectedMoveInYearMonth == null) {
                continue;
            }
            supplement.addComplexSnapshot(
                    supplement.getComplexSnapshots().size(),
                    complexName,
                    lotAddress,
                    lotDetailAddress,
                    totalUnitCount,
                    heatingDescription,
                    exclusiveAreaRange,
                    expectedMoveInYearMonth);
        }
    }

    private LocalDate date(String sourcePath, String raw) {
        String value = SourceValues.trimToNull(raw);
        if (value == null) {
            return null;
        }
        LocalDate parsed = SourceValues.toDate(value);
        if (parsed == null) {
            log.warn("LH 상세 날짜 변환 실패: field={}, raw={}", sourcePath, value);
        }
        return parsed;
    }

    private YearMonth yearMonth(String sourcePath, String raw) {
        String value = SourceValues.trimToNull(raw);
        if (value == null) {
            return null;
        }
        YearMonth parsed = SourceValues.toYearMonth(value);
        if (parsed == null) {
            log.warn("LH 상세 입주예정월 변환 실패: field={}, raw={}", sourcePath, value);
        }
        return parsed;
    }

    /** 공고문 파일과 단지 이미지를 응답 순서대로 한 줄로 잇는다. 둘 다 "공고에 딸린 파일"이라 같은 테이블이다. */
    private void addAttachments(NoticeSupplement supplement, JsonNode root) {
        for (JsonNode row : OpenApiClient.findRows(root, "dsAhflInfo")) {
            LhNoticeDetail.NoticeFile file = objectMapper.convertValue(row, LhNoticeDetail.NoticeFile.class);
            add(supplement, file.kind(), file.name(), file.url(), null);
        }
        for (JsonNode row : OpenApiClient.findRows(root, "dsSbdAhfl")) {
            LhNoticeDetail.ComplexImage image = objectMapper.convertValue(row, LhNoticeDetail.ComplexImage.class);
            add(supplement, image.kind(), image.name(), image.url(), image.complexName());
        }
    }

    /**
     * 원천이 값 대신 <b>컬럼 이름</b>을 담은 행을 같이 준다("첨부파일명", "다운로드" 같은 것).
     * URL 이 http 로 시작하는지로 그런 행을 걸러낸다.
     */
    private void add(NoticeSupplement supplement,
                     String kind, String name, String url, String complexLabel) {
        String trimmedUrl = SourceValues.trimToNull(url);
        String trimmedKind = SourceValues.trimToNull(kind);
        String trimmedName = SourceValues.trimToNull(name);
        if (!isHttpUrl(trimmedUrl) || trimmedKind == null || trimmedName == null) {
            return;
        }
        supplement.addAttachment(supplement.getAttachments().size(), trimmedKind, trimmedName,
                trimmedUrl, SourceValues.trimToNull(complexLabel));
    }

    private boolean isHttpUrl(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            URI uri = URI.create(raw);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
