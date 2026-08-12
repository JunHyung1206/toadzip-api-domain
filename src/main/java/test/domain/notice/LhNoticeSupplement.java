package test.domain.notice;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import test.domain.source.SourceSystem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** 마이홈 공고버전에 LH 상세 원천이 나중에 보태는 불변 aggregate. */
@Entity
@Table(
        name = "lh_notice_supplement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_supplement_version",
                columnNames = "notice_version_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhNoticeSupplement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    /** 원천 panId. 이 요청을 어느 공고에 물었는지 남긴다. */
    @Column(name = "source_pan_id", length = 50)
    private String sourcePanId;

    /** 요청에 쓴 CCR_CNNT_SYS_DS_CD. */
    @Column(name = "requested_connection_system_division_code", length = 10)
    private String requestedConnectionSystemDivisionCode;

    /** 요청에 쓴 UPP_AIS_TP_CD. */
    @Column(name = "requested_upper_announcement_type_code", length = 10)
    private String requestedUpperAnnouncementTypeCode;

    /** 요청에 쓴 AIS_TP_CD. 링크에 없을 수 있어 생략 가능하다. */
    @Column(name = "requested_announcement_type_code", length = 10)
    private String requestedAnnouncementTypeCode;

    /** 요청에 쓴 SPL_INF_TP_CD. {@link test.domain.ingest.lh.LhSupplyInfoTypeResolver} 가 정한다. */
    @Column(name = "requested_supply_info_type_code", length = 10)
    private String requestedSupplyInfoTypeCode;

    /** 원천 응답 시각(resHeader.RS_DTTM). */
    @Column(name = "source_responded_at")
    private LocalDateTime sourceRespondedAt;

    /** 우리가 이 응답을 받아온 시각. */
    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /**
     * 응답에 {@code dsSbd} 키 자체가 있었는지. 행 개수(0건)와는 다른 정보다 —
     * 키가 없으면 원천이 아예 이 데이터셋을 안 준 것이고, 키가 있는데 빈 배열이면 조회는 됐지만
     * 단지 정보가 없다는 뜻이라 구분해서 남긴다.
     */
    @Column(name = "complex_detail_dataset_present", nullable = false)
    private boolean complexDetailDatasetPresent;

    @Column(name = "correction_reason", length = 2000)
    private String correctionReason;

    @OneToMany(mappedBy = "noticeSupplement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<NoticeSchedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "noticeSupplement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ReceptionPlace> receptionPlaces = new ArrayList<>();

    @OneToMany(mappedBy = "noticeSupplement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<LhComplexDetail> complexDetails = new ArrayList<>();

    @OneToMany(mappedBy = "noticeSupplement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<NoticeAttachment> attachments = new ArrayList<>();

    public LhNoticeSupplement(NoticeVersion noticeVersion,
                              SourceSystem sourceSystem,
                              String sourcePanId,
                              String requestedConnectionSystemDivisionCode,
                              String requestedUpperAnnouncementTypeCode,
                              String requestedAnnouncementTypeCode,
                              String requestedSupplyInfoTypeCode,
                              LocalDateTime sourceRespondedAt,
                              LocalDateTime fetchedAt,
                              boolean complexDetailDatasetPresent,
                              String correctionReason) {
        this.noticeVersion = noticeVersion;
        this.sourceSystem = sourceSystem;
        this.sourcePanId = sourcePanId;
        this.requestedConnectionSystemDivisionCode = requestedConnectionSystemDivisionCode;
        this.requestedUpperAnnouncementTypeCode = requestedUpperAnnouncementTypeCode;
        this.requestedAnnouncementTypeCode = requestedAnnouncementTypeCode;
        this.requestedSupplyInfoTypeCode = requestedSupplyInfoTypeCode;
        this.sourceRespondedAt = sourceRespondedAt;
        this.fetchedAt = fetchedAt;
        this.complexDetailDatasetPresent = complexDetailDatasetPresent;
        this.correctionReason = correctionReason;
    }

    public void addSchedule(int displayOrder,
                            String complexLabel,
                            String applicationPeriodText,
                            LocalDate documentTargetAnnouncedOn,
                            LocalDate documentSubmissionBeginOn,
                            LocalDate documentSubmissionEndOn,
                            LocalDate contractBeginOn,
                            LocalDate contractEndOn) {
        requireNew();
        schedules.add(new NoticeSchedule(this, displayOrder, complexLabel, applicationPeriodText,
                documentTargetAnnouncedOn, documentSubmissionBeginOn, documentSubmissionEndOn,
                contractBeginOn, contractEndOn));
    }

    public void addReceptionPlace(int displayOrder,
                                  String address,
                                  String detailAddress,
                                  String operationBeginText,
                                  String operationEndText,
                                  String phone,
                                  String guidance) {
        requireNew();
        receptionPlaces.add(new ReceptionPlace(this, displayOrder, address, detailAddress,
                operationBeginText, operationEndText, phone, guidance));
    }

    public void addComplexDetail(int displayOrder,
                                 String complexLabel,
                                 String lotAddress,
                                 String lotDetailAddress,
                                 Integer totalUnitCount,
                                 String heatingDescription,
                                 String exclusiveAreaRangeText,
                                 YearMonth expectedMoveInYearMonth,
                                 String guidanceText) {
        requireNew();
        complexDetails.add(new LhComplexDetail(this, displayOrder, complexLabel, lotAddress,
                lotDetailAddress, totalUnitCount, heatingDescription, exclusiveAreaRangeText,
                expectedMoveInYearMonth, guidanceText));
    }

    public void addAttachment(int displayOrder,
                              String kind,
                              String name,
                              String url,
                              String complexLabel) {
        requireNew();
        attachments.add(new NoticeAttachment(this, displayOrder, kind, name, url, complexLabel));
    }

    public List<NoticeSchedule> getSchedules() {
        return List.copyOf(schedules);
    }

    public List<ReceptionPlace> getReceptionPlaces() {
        return List.copyOf(receptionPlaces);
    }

    public List<LhComplexDetail> getComplexDetails() {
        return List.copyOf(complexDetails);
    }

    public List<NoticeAttachment> getAttachments() {
        return List.copyOf(attachments);
    }

    private void requireNew() {
        if (id != null) {
            throw new IllegalStateException("저장된 보충 스냅샷은 수정할 수 없습니다.");
        }
    }
}
