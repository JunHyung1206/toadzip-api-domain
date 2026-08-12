package test.domain.match;

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
import test.domain.housing.HousingComplex;
import test.domain.notice.NoticeHousing;

import java.time.LocalDateTime;

/**
 * {@link NoticeHousing} 한 행이 단지 카탈로그({@link HousingComplex})의 어느 후보와 PNU로 맞았는지 기록한
 * 파생 결과. 공고 aggregate 와 카탈로그를 잇는 매칭은 여기서만 한다.
 *
 * <p>재실행 가능한 파생값이라 {@code matcherVersion} 별로 남는다. 같은 버전을 다시 돌리면 그 버전의 행만
 * 지우고 다시 만들고, 버전을 올리면 이전 버전 결과는 그대로 남아 나란히 존재한다.
 */
@Entity
@Table(
        name = "notice_housing_catalog_match",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_housing_catalog_match_version",
                columnNames = {"notice_housing_id", "matcher_version"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeHousingCatalogMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_housing_id", nullable = false)
    private NoticeHousing noticeHousing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "housing_complex_id")
    private HousingComplex housingComplex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoticeHousingCatalogMatchStatus status;

    /** 매칭에 쓴 PNU. {@code NoticeHousing.suppliedHousing.pnu} 를 그대로 남긴다. */
    @Column(name = "compared_pnu", length = 19)
    private String comparedPnu;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "matcher_version", nullable = false, length = 50)
    private String matcherVersion;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    public NoticeHousingCatalogMatch(NoticeHousing noticeHousing,
                                     HousingComplex housingComplex,
                                     NoticeHousingCatalogMatchStatus status,
                                     String comparedPnu,
                                     int candidateCount,
                                     String matcherVersion,
                                     LocalDateTime evaluatedAt) {
        this.noticeHousing = noticeHousing;
        this.housingComplex = housingComplex;
        this.status = status;
        this.comparedPnu = comparedPnu;
        this.candidateCount = candidateCount;
        this.matcherVersion = matcherVersion;
        this.evaluatedAt = evaluatedAt;
    }
}
