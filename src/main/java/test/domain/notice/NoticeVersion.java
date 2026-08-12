package test.domain.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import test.domain.housing.HouseType;
import test.domain.housing.SupplyType;
import test.domain.source.SourceSystem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 최초·정정·취소 시점의 내용을 보존하는 불변 스냅샷.
 *
 * <p><b>PK</b> — 설계 표에는 noticeId 가 PK로 적혀 있었는데, 그러면 공고당 행이 하나뿐이라
 * versionNumber 와 supersedesVersionId 가 존재할 수 없다. PK는 버전 자신의 id 로 두고
 * (recruitmentNoticeId, versionNumber) 에 유니크를 걸었다.
 *
 * <p><b>recruitmentNotice 와 sourceNoticeId 가 따로인 이유</b> — 마이홈은 정정공고를 낼 때 <em>새 pblancId</em>를
 * 발급하고 beforePblancId 로 이전 공고를 가리킨다. 즉 pblancId 는 "공고"가 아니라 "공고의 한 버전"이다.
 * 실데이터에서 21001 ← 20942 ← 20893 ← 20680 처럼 4단계 체인도 나왔다.
 * 그래서 sourceNoticeId = 이 버전의 pblancId, recruitmentNotice = 체인 전체를 묶는 루트({@link RecruitmentNotice})로 나눴다.
 *
 * <p><b>beforeSourceNoticeId</b> 는 원천이 준 이전 pblancId 원문을 그대로 남긴다. 실제 체인 연결은
 * supersedesVersion(FK)이 담당하지만, 원천 순서가 깨져 그 FK 를 못 채웠을 때도 원문이 뭐였는지는 남아야 한다.
 *
 * <p>수정자를 두지 않은 것도 의도다. 내용이 바뀌면 고치는 게 아니라 {@link #nextVersion} 으로 새 행을 쌓는다.
 */
@Entity
@Table(
        name = "notice_version",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_notice_version",
                        columnNames = {"recruitment_notice_id", "version_number"}),
                @UniqueConstraint(name = "uk_notice_version_source",
                        columnNames = {"source_system", "source_notice_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 버전이 속한 공고 루트. 같은 공고의 모든 버전(원공고 + 정정 체인)이 같은 루트를 가리킨다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recruitment_notice_id", nullable = false)
    private RecruitmentNotice recruitmentNotice;

    /** 이 버전 자신의 원천 ID(pblancId). 다음 정정공고가 beforePblancId 로 여기를 가리킨다. */
    @Column(name = "source_notice_id", nullable = false, length = 50)
    private String sourceNoticeId;

    /** 원천 beforePblancId 원문. supersedesVersion 을 못 찾았을 때도(체인이 끊겼을 때) 이 값은 남는다. */
    @Column(name = "before_source_notice_id", length = 50)
    private String beforeSourceNoticeId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoticeChangeStatus noticeChangeStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_version_id")
    private NoticeVersion supersedesVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SourceSystem sourceSystem;

    private LocalDateTime publishedAt;


    /** 설계에 없던 칸. 공고명 없이는 목록도 상세도 못 그린다. 정정공고에서 제목이 바뀌므로 버전 소유가 맞다. */
    @Column(nullable = false)
    private String title;

    /**
     * 설계에 없던 칸. 원천 url — 청약 사이트 원문. LH건은 여기 panId 가 박혀 있어 LH 원천과 잇는 열쇠다.
     * 기본 255자로는 모자란다. 실제로 278자짜리(경북개발공사 게시판 링크)가 들어와 적재가 터졌다.
     */
    @Column(name = "detail_url", length = 500)
    private String detailUrl;

    /** 설계에 없던 칸. 접수 기간이 없으면 공고가 쓸모없다. */
    @Column(name = "application_begin_on")
    private LocalDate applicationBeginOn;

    @Column(name = "application_end_on")
    private LocalDate applicationEndOn;

    @Column(name = "winner_announced_on")
    private LocalDate winnerAnnouncedOn;

    /** 설계에 없던 칸. 원천 suplyInsttNm. 단지의 공급기관과 달리 "LH" 처럼 지역본부 구분 없이 온다. */
    @Column(name = "supply_institution_name", length = 50)
    private String supplyInstitutionName;

    /** 설계에 없던 칸. 원천 houseTyNm 을 정리한 값. 모르는 값이면 null 이고 원문은 아래에 남는다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "house_type", length = 30)
    private HouseType houseType;

    /** 설계에 없던 칸. 원천 houseTyNm 원문. */
    @Column(name = "house_type_name", length = 30)
    private String houseTypeName;

    /** 설계에 없던 칸. 원천 suplyTyNm 을 정리한 값. 단지 쪽 {@code unit_type.supply_type} 과 같은 enum 이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "supply_type", length = 30)
    private SupplyType supplyType;

    /** 설계에 없던 칸. 원천 suplyTyNm 원문. */
    @Column(name = "supply_type_name", length = 30)
    private String supplyTypeName;


    /** 설계에 없던 칸. 원천 refrnc. 문의처(콜센터 번호 등). */
    @Column(name = "contact", length = 200)
    private String contact;

    private NoticeVersion(RecruitmentNotice recruitmentNotice,
                          String sourceNoticeId,
                          String beforeSourceNoticeId,
                          int versionNumber,
                          NoticeVersion supersedesVersion,
                          NoticeSnapshot snapshot) {
        this.recruitmentNotice = recruitmentNotice;
        this.sourceNoticeId = sourceNoticeId;
        this.beforeSourceNoticeId = beforeSourceNoticeId;
        this.versionNumber = versionNumber;
        this.supersedesVersion = supersedesVersion;
        this.sourceSystem = recruitmentNotice.getSourceSystem();
        this.noticeChangeStatus = snapshot.changeStatus();
        this.publishedAt = snapshot.publishedAt();
        this.title = snapshot.title();
        this.detailUrl = snapshot.detailUrl();
        this.applicationBeginOn = snapshot.applicationBeginOn();
        this.applicationEndOn = snapshot.applicationEndOn();
        this.winnerAnnouncedOn = snapshot.winnerAnnouncedOn();
        this.supplyInstitutionName = snapshot.supplyInstitutionName();
        this.houseTypeName = snapshot.houseTypeName();
        this.houseType = HouseType.from(snapshot.houseTypeName());
        this.supplyTypeName = snapshot.supplyTypeName();
        this.supplyType = SupplyType.from(snapshot.supplyTypeName());
        this.contact = snapshot.contact();
    }

    public static NoticeVersion firstVersion(RecruitmentNotice recruitmentNotice,
                                            String sourceNoticeId,
                                            String beforeSourceNoticeId,
                                            NoticeSnapshot snapshot) {
        return new NoticeVersion(recruitmentNotice, sourceNoticeId, beforeSourceNoticeId, 1, null, snapshot);
    }

    /** 이 버전 뒤에 붙는 새 스냅샷. 기존 행은 그대로 남는다. */
    public NoticeVersion nextVersion(String sourceNoticeId, String beforeSourceNoticeId, NoticeSnapshot snapshot) {
        return new NoticeVersion(this.recruitmentNotice, sourceNoticeId, beforeSourceNoticeId,
                this.versionNumber + 1, this, snapshot);
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
        return new NoticeSnapshot(noticeChangeStatus, publishedAt, title, detailUrl,
                applicationBeginOn, applicationEndOn, winnerAnnouncedOn,
                supplyInstitutionName, houseTypeName, supplyTypeName, contact);
    }
}
