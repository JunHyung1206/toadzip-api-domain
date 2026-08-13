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
import test.domain.housing.UnitType;
import test.domain.notice.LhUnitSupply;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeVersion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@link NoticeHousing} 한 행이 15056765 공급행({@link LhUnitSupply})을 거쳐 어느 카탈로그
 * 주택형({@link UnitType})과 맞았는지 기록한 파생 결과.
 *
 * <p>{@link NoticeHousingCatalogMatch}가 먼저 확정한 단지 위에서만 시도한다 — 단지 자체가 안 붙었는데
 * 주택형만 붙이면 근거 없는 연결이 된다. 그 위에서 15056765의 단지명({@code SBD_LGO_NM})이 그 단지 이름과
 * 같은 공급행만 후보로 삼고, 전용면적으로 {@link UnitType}과 대조한다(주택형명은 "59㎡"처럼 표기가
 * 갈려 근거로 안 쓴다 — {@link #sourceTypeName}에 원문만 남긴다).
 *
 * <p>한 {@code NoticeHousing}(단지 하나)에 주택형이 여러 개면 15056765 공급행 수만큼 행이 생긴다 —
 * {@link NoticeHousingLhMatch}와 달리 1:1이 아니라 1:N이다.
 */
@Entity
@Table(
        name = "notice_housing_unit_type_match",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_housing_unit_type_match_version_order",
                columnNames = {"notice_version_id", "matcher_version", "result_order"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeHousingUnitTypeMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_housing_id", nullable = false)
    private NoticeHousing noticeHousing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lh_unit_supply_id")
    private LhUnitSupply lhUnitSupply;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_type_id")
    private UnitType unitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NoticeHousingUnitTypeMatchStatus status;

    /** {@code lhUnitSupply.suppliedUnitCount} 를 그대로 옮긴 값. 조회 시 조인 없이 바로 쓰려고 복사한다. */
    @Column(name = "supplied_unit_count")
    private Integer suppliedUnitCount;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "matcher_version", nullable = false, length = 50)
    private String matcherVersion;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt;

    @Column(name = "source_complex_label", length = 200)
    private String sourceComplexLabel;

    @Column(name = "source_type_name", length = 50)
    private String sourceTypeName;

    @Column(name = "source_exclusive_area", precision = 10, scale = 4)
    private BigDecimal sourceExclusiveArea;

    @Column(length = 300)
    private String reason;

    @Column(name = "result_order", nullable = false)
    private int resultOrder;

    public NoticeHousingUnitTypeMatch(NoticeVersion noticeVersion,
                                      NoticeHousing noticeHousing,
                                      LhUnitSupply lhUnitSupply,
                                      UnitType unitType,
                                      NoticeHousingUnitTypeMatchStatus status,
                                      Integer suppliedUnitCount,
                                      int candidateCount,
                                      String matcherVersion,
                                      LocalDateTime evaluatedAt,
                                      String sourceComplexLabel,
                                      String sourceTypeName,
                                      BigDecimal sourceExclusiveArea,
                                      String reason,
                                      int resultOrder) {
        this.noticeVersion = noticeVersion;
        this.noticeHousing = noticeHousing;
        this.lhUnitSupply = lhUnitSupply;
        this.unitType = unitType;
        this.status = status;
        this.suppliedUnitCount = suppliedUnitCount;
        this.candidateCount = candidateCount;
        this.matcherVersion = matcherVersion;
        this.evaluatedAt = evaluatedAt;
        this.sourceComplexLabel = sourceComplexLabel;
        this.sourceTypeName = sourceTypeName;
        this.sourceExclusiveArea = sourceExclusiveArea;
        this.reason = reason;
        this.resultOrder = resultOrder;
    }
}
