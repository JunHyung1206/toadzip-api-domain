package test.domain.match;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import test.domain.notice.LhComplexDetail;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeVersion;

import java.time.LocalDateTime;

/**
 * {@link NoticeHousing} 한 행과 LH 단지 상세({@link LhComplexDetail}) 한 행이 주소·세대수로 맺어졌는지
 * 기록한 파생 결과. 양쪽 다 nullable 인 이유가 다르다 — {@code noticeHousing} 이 null 인 행은 어떤 공고
 * 행의 단독 후보도 아니었던 LH 상세를 남겨 두려는 것이고(버리지 않는다), {@code lhComplexDetail} 이 null 인
 * 행은 후보가 없거나(UNMATCHED) 후보가 여럿이라(AMBIGUOUS) 하나로 못 정했다는 뜻이다.
 *
 * <p>조회 조립은 {@link NoticeHousingLhMatchStatus#MATCHED_ADDRESS_AND_UNIT_COUNT} 와
 * {@link NoticeHousingLhMatchStatus#MATCHED_ADDRESS_ONLY} 두 상태만 실제 연결로 인정한다.
 * {@link NoticeHousingLhMatchStatus#CONFLICT_UNIT_COUNT} 도 {@code lhComplexDetail} 을 채워 어떤
 * 상세와 충돌했는지 남기지만, 세대수가 어긋난 이상 화면 조립에서 신뢰할 연결로 쓰지 않는다.
 *
 * <p>재실행 가능한 파생값이라 {@code matcherVersion} 별로 남는다. 같은 버전을 다시 돌리면 그 버전의 행만
 * 지우고 다시 만든다. {@code resultOrder} 는 재실행마다 같은 순서로 재현되는 판정 순번이다.
 */
@Entity
@Table(
        name = "notice_housing_lh_match",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_housing_lh_match_version_order",
                columnNames = {"notice_version_id", "matcher_version", "result_order"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeHousingLhMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notice_housing_id")
    private NoticeHousing noticeHousing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lh_complex_detail_id")
    private LhComplexDetail lhComplexDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NoticeHousingLhMatchStatus status;

    /** 이 행이 가리키는 LH 상세를 후보로 삼은 공고 행 수. 단독 후보를 못 정했으면 0이다. */
    @Column(name = "notice_candidate_count", nullable = false)
    private int noticeCandidateCount;

    /** 이 공고 행이 찾아낸 LH 상세 후보 수. LH 단독 잔여 행이면 0이다. */
    @Column(name = "lh_candidate_count", nullable = false)
    private int lhCandidateCount;

    @Column(name = "matcher_version", nullable = false, length = 50)
    private String matcherVersion;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    @Embedded
    private NoticeHousingLhMatchEvidence evidence;

    @Column(name = "result_order", nullable = false)
    private int resultOrder;

    public NoticeHousingLhMatch(NoticeVersion noticeVersion,
                                NoticeHousing noticeHousing,
                                LhComplexDetail lhComplexDetail,
                                NoticeHousingLhMatchStatus status,
                                int noticeCandidateCount,
                                int lhCandidateCount,
                                String matcherVersion,
                                LocalDateTime evaluatedAt,
                                NoticeHousingLhMatchEvidence evidence,
                                int resultOrder) {
        this.noticeVersion = noticeVersion;
        this.noticeHousing = noticeHousing;
        this.lhComplexDetail = lhComplexDetail;
        this.status = status;
        this.noticeCandidateCount = noticeCandidateCount;
        this.lhCandidateCount = lhCandidateCount;
        this.matcherVersion = matcherVersion;
        this.evaluatedAt = evaluatedAt;
        this.evidence = evidence;
        this.resultOrder = resultOrder;
    }
}
