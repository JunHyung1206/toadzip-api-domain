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

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** 마이홈 공고버전에 LH 상세 원천이 나중에 보태는 불변 aggregate. */
@Entity
@Table(
        name = "notice_supplement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_supplement_version",
                columnNames = "notice_version_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeSupplement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

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
    private List<NoticeComplexSnapshot> complexSnapshots = new ArrayList<>();

    @OneToMany(mappedBy = "noticeSupplement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<NoticeAttachment> attachments = new ArrayList<>();

    public NoticeSupplement(NoticeVersion noticeVersion,
                            SourceSystem sourceSystem,
                            String correctionReason) {
        this.noticeVersion = noticeVersion;
        this.sourceSystem = sourceSystem;
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

    public void addComplexSnapshot(int displayOrder,
                                   String complexLabel,
                                   String lotAddress,
                                   String lotDetailAddress,
                                   Integer totalUnitCount,
                                   String heatingDescription,
                                   String exclusiveAreaRangeText,
                                   YearMonth expectedMoveInYearMonth) {
        requireNew();
        complexSnapshots.add(new NoticeComplexSnapshot(this, displayOrder, complexLabel, lotAddress,
                lotDetailAddress, totalUnitCount, heatingDescription, exclusiveAreaRangeText,
                expectedMoveInYearMonth));
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

    public List<NoticeComplexSnapshot> getComplexSnapshots() {
        return List.copyOf(complexSnapshots);
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
