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
import test.domain.housing.LhLeaseInfo;
import test.domain.housing.UnitType;

import java.time.LocalDateTime;

/** 15059475 원천행을 카탈로그 주택형에 연결한 측정 결과. 불확실한 후보도 버리지 않는다. */
@Entity
@Table(
        name = "lh_lease_info_unit_type_match",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lh_lease_info_unit_type_match_version",
                columnNames = {"lh_lease_info_id", "matcher_version"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhLeaseInfoUnitTypeMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lh_lease_info_id", nullable = false)
    private LhLeaseInfo lhLeaseInfo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_type_id")
    private UnitType unitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LhLeaseInfoUnitTypeMatchStatus status;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "matcher_version", nullable = false, length = 50)
    private String matcherVersion;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    @Column(length = 300)
    private String reason;

    public LhLeaseInfoUnitTypeMatch(LhLeaseInfo lhLeaseInfo,
                                    UnitType unitType,
                                    LhLeaseInfoUnitTypeMatchStatus status,
                                    int candidateCount,
                                    String matcherVersion,
                                    LocalDateTime evaluatedAt,
                                    String reason) {
        this.lhLeaseInfo = lhLeaseInfo;
        this.unitType = unitType;
        this.status = status;
        this.candidateCount = candidateCount;
        this.matcherVersion = matcherVersion;
        this.evaluatedAt = evaluatedAt;
        this.reason = reason;
    }
}
