package test.domain.notice;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 마이홈 {@code pblancId} 하나가 표현하는 불변 공고 스냅샷. 원천은 15108420 HWSPR02 다.
 *
 * <p><b>루트 테이블 없이 체인을 잇는다.</b> 마이홈은 정정공고를 낼 때 <em>새 pblancId</em> 를 발급하고
 * {@code beforePblancId} 로 이전 공고를 가리킨다. 즉 pblancId 는 "공고"가 아니라 "공고의 한 버전"이다.
 * 예전에는 체인을 묶으려고 {@code recruitment_notice} 루트 행을 따로 뒀는데, 실측 49개 공고 중 40개가
 * 버전 1개·9개가 2개라 테이블 하나를 유지할 깊이가 아니었다. 대신 두 칸으로 대신한다.
 *
 * <ul>
 *   <li>{@link #supersedesNotice} — 직전 버전 self FK. 한 칸 위로 올라간다.</li>
 *   <li>{@link #rootSourceNoticeId} — 체인 뿌리의 pblancId. 원공고는 자기 자신을 가리킨다.
 *       "이 공고의 모든 버전"이 인덱스 한 방으로 끝난다.</li>
 * </ul>
 *
 * <p>정정공고가 원공고보다 먼저 들어오면 스스로를 뿌리로 두고 시작한다. 나중에 원공고가 들어오면
 * {@link #rebaseOnto} 로 뿌리·순번·FK 를 옮긴다. 루트 테이블을 뒀어도 두 루트를 병합해야 했을 문제라
 * 없앤다고 새로 생긴 비용이 아니다.
 *
 * <p>{@link #beforeSourceNoticeId} 는 원천이 준 이전 pblancId 원문을 그대로 남긴다. 체인 FK 를 못 채웠을
 * 때도 원문이 뭐였는지는 남아야 한다.
 *
 * <p>공고상태·주택유형·공급유형은 원천이 준 한글 이름만 담는다. 표준 enum 으로 옮기던 칸은 없앴다.
 */
@Entity
@Table(
        name = "notice",
        uniqueConstraints = @UniqueConstraint(name = "uk_notice_source", columnNames = "source_notice_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 버전 자신의 원천 ID(pblancId). 다음 정정공고가 beforePblancId 로 여기를 가리킨다. */
    @Column(name = "source_notice_id", nullable = false, length = 50)
    private String sourceNoticeId;

    /** 원천 beforePblancId 원문. 체인 FK 를 못 찾았을 때도 이 값은 남는다. */
    @Column(name = "before_source_notice_id", length = 50)
    private String beforeSourceNoticeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_notice_id")
    private Notice supersedesNotice;

    /** 체인 뿌리의 pblancId. 원공고는 자기 자신이다. */
    @Column(name = "root_source_notice_id", nullable = false, length = 50)
    private String rootSourceNoticeId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** 원천 sttusNm 원문 — "일반공고", "정정공고", "취소공고". */
    @Column(name = "notice_change_status_name", length = 30)
    private String noticeChangeStatusName;

    private LocalDateTime publishedAt;

    /** 공고명 없이는 목록도 상세도 못 그린다. 정정공고에서 제목이 바뀌므로 버전 소유가 맞다. */
    @Column(nullable = false)
    private String title;

    /**
     * 원천 url — 청약 사이트 원문. LH건은 여기 panId 가 박혀 있어 LH 원천과 잇는 열쇠다.
     * 기본 255자로는 모자란다. 실제로 278자짜리(경북개발공사 게시판 링크)가 들어와 적재가 터졌다.
     */
    @Column(name = "detail_url", length = 500)
    private String detailUrl;

    @Column(name = "application_begin_on")
    private LocalDate applicationBeginOn;

    @Column(name = "application_end_on")
    private LocalDate applicationEndOn;

    @Column(name = "winner_announced_on")
    private LocalDate winnerAnnouncedOn;

    /** 원천 suplyInsttNm. 단지의 공급기관과 달리 "LH" 처럼 지역본부 구분 없이 온다. */
    @Column(name = "supply_institution_name", length = 50)
    private String supplyInstitutionName;

    /** 원천 houseTyNm 원문. */
    @Column(name = "house_type_name", length = 30)
    private String houseTypeName;

    /** 원천 suplyTyNm 원문. 카탈로그 단지의 {@code supply_type_name} 과 같은 어휘다. */
    @Column(name = "supply_type_name", length = 30)
    private String supplyTypeName;

    /** 원천 refrnc. 문의처(콜센터 번호 등). */
    @Column(name = "contact", length = 200)
    private String contact;

    /** LH 공고 ID. {@link #detailUrl} 의 panId 에서 뽑는다. */
    @Column(name = "source_pan_id", length = 50)
    private String sourcePanId;

    /** LH 호출에 쓴 SPL_INF_TP_CD. 공급유형명에서 정한다. */
    @Column(name = "lh_supply_info_type_code", length = 10)
    private String lhSupplyInfoTypeCode;

    /**
     * LH 원천(15057999 + 15056765)을 받아온 시각. 공고가 불변이라 LH 응답도 불변이므로,
     * 이 값이 차 있으면 다시 부르지 않는다.
     */
    @Column(name = "lh_fetched_at")
    private LocalDateTime lhFetchedAt;

    /** 15057999 dsEtcInfo.CRC_RSN. 정정·취소 사유. */
    @Column(name = "correction_reason", length = 2000)
    private String correctionReason;

    @OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<NoticeSchedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ReceptionPlace> receptionPlaces = new ArrayList<>();

    @OneToMany(mappedBy = "notice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<NoticeAttachment> attachments = new ArrayList<>();

    private Notice(String sourceNoticeId,
                   String beforeSourceNoticeId,
                   Notice supersedesNotice,
                   String rootSourceNoticeId,
                   int versionNumber,
                   NoticeSnapshot snapshot) {
        this.sourceNoticeId = sourceNoticeId;
        this.beforeSourceNoticeId = beforeSourceNoticeId;
        this.supersedesNotice = supersedesNotice;
        this.rootSourceNoticeId = rootSourceNoticeId;
        this.versionNumber = versionNumber;
        applySnapshot(snapshot);
    }

    /** 체인이 처음이거나 이전 버전을 못 찾았을 때. 자기 자신이 뿌리다. */
    public static Notice firstVersion(String sourceNoticeId, String beforeSourceNoticeId, NoticeSnapshot snapshot) {
        return new Notice(sourceNoticeId, beforeSourceNoticeId, null, sourceNoticeId, 1, snapshot);
    }

    /** 이 버전 뒤에 붙는 새 스냅샷. 기존 행은 그대로 남는다. */
    public Notice nextVersion(String sourceNoticeId, String beforeSourceNoticeId, NoticeSnapshot snapshot) {
        return new Notice(sourceNoticeId, beforeSourceNoticeId, this,
                this.rootSourceNoticeId, this.versionNumber + 1, snapshot);
    }

    /**
     * 뒤늦게 나타난 이전 버전 아래로 이 버전을 옮긴다. 정정공고를 먼저 받고 원공고를 나중에 받은 경우다.
     * 뿌리와 순번이 바뀌므로 이 버전을 가리키는 뒷 버전들도 이어서 옮겨야 한다.
     */
    public void rebaseOnto(Notice previous) {
        this.supersedesNotice = previous;
        this.rootSourceNoticeId = previous.rootSourceNoticeId;
        this.versionNumber = previous.versionNumber + 1;
    }

    /**
     * 같은 pblancId 를 다시 읽었을 때 내용이 그대로인지 본다.
     * 마이홈은 정정 때 새 pblancId 를 발급하므로 여기서 다르다고 나오면 원천이 제자리에서 고쳤다는 뜻이라
     * 적재가 경고를 남긴다.
     */
    public boolean hasSameContentAs(NoticeSnapshot snapshot) {
        return Objects.equals(currentSnapshot(), snapshot);
    }

    public NoticeSnapshot currentSnapshot() {
        return new NoticeSnapshot(noticeChangeStatusName, publishedAt, title, detailUrl,
                applicationBeginOn, applicationEndOn, winnerAnnouncedOn,
                supplyInstitutionName, houseTypeName, supplyTypeName, contact);
    }

    /** LH 두 원천을 받은 뒤 한 번만 채운다. 이 값이 차면 재호출하지 않는다. */
    public void markLhFetched(String sourcePanId,
                              String lhSupplyInfoTypeCode,
                              String correctionReason,
                              LocalDateTime fetchedAt) {
        this.sourcePanId = sourcePanId;
        this.lhSupplyInfoTypeCode = lhSupplyInfoTypeCode;
        this.correctionReason = correctionReason;
        this.lhFetchedAt = fetchedAt;
    }

    /** LH 원천을 다시 받을 때 앞서 붙인 일정·접수처·첨부를 비운다. */
    public void clearLhChildren() {
        schedules.clear();
        receptionPlaces.clear();
        attachments.clear();
    }

    public void addSchedule(String complexLabel,
                            String applicationPeriodText,
                            LocalDate documentTargetAnnouncedOn,
                            LocalDate documentSubmissionBeginOn,
                            LocalDate documentSubmissionEndOn,
                            LocalDate contractBeginOn,
                            LocalDate contractEndOn) {
        schedules.add(new NoticeSchedule(this, schedules.size(), complexLabel, applicationPeriodText,
                documentTargetAnnouncedOn, documentSubmissionBeginOn, documentSubmissionEndOn,
                contractBeginOn, contractEndOn));
    }

    public void addReceptionPlace(String address,
                                  String detailAddress,
                                  String operationBeginText,
                                  String operationEndText,
                                  String phone,
                                  String guidance) {
        receptionPlaces.add(new ReceptionPlace(this, receptionPlaces.size(), address, detailAddress,
                operationBeginText, operationEndText, phone, guidance));
    }

    public void addAttachment(String kind, String name, String url, String complexLabel) {
        attachments.add(new NoticeAttachment(this, attachments.size(), kind, name, url, complexLabel));
    }

    public List<NoticeSchedule> getSchedules() {
        return List.copyOf(schedules);
    }

    public List<ReceptionPlace> getReceptionPlaces() {
        return List.copyOf(receptionPlaces);
    }

    public List<NoticeAttachment> getAttachments() {
        return List.copyOf(attachments);
    }

    private void applySnapshot(NoticeSnapshot snapshot) {
        this.noticeChangeStatusName = snapshot.changeStatusName();
        this.publishedAt = snapshot.publishedAt();
        this.title = snapshot.title();
        this.detailUrl = snapshot.detailUrl();
        this.applicationBeginOn = snapshot.applicationBeginOn();
        this.applicationEndOn = snapshot.applicationEndOn();
        this.winnerAnnouncedOn = snapshot.winnerAnnouncedOn();
        this.supplyInstitutionName = snapshot.supplyInstitutionName();
        this.houseTypeName = snapshot.houseTypeName();
        this.supplyTypeName = snapshot.supplyTypeName();
        this.contact = snapshot.contact();
    }
}
