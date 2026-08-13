package test.domain.match;

import jakarta.persistence.Column;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
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
import test.domain.housing.BaseRentTerms;
import test.domain.notice.RentTerms;
import test.domain.housing.UnitType;
import test.domain.notice.LhUnitSupply;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeVersion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 15056765 공급행({@link LhUnitSupply}) 하나가 카탈로그 주택형({@link UnitType})의 어느 행인지 기록한
 * 파생 결과. <b>공급행 하나가 결과 한 줄</b>이고, 못 맞춘 것도 이유를 달아 남긴다.
 *
 * <p><b>단지명을 카탈로그와 직접 대조하지 않는다.</b> 마이홈은 동네 통칭("하나로아파트"), LH는
 * 사업지구명+블록("군산조촌부향")이라 실측 85쌍 중 16쌍(19%)만 맞았다. 대신 <b>LH 이름끼리</b> 잇는다 —
 * 15056765의 {@code SBD_LGO_NM}과 15057999의 {@code LCC_NT_NM}은 같은 LH 명명이라 실측 290행이 전부 맞았다.
 *
 * <pre>
 *   LhUnitSupply ─LH단지명─&gt; LhComplexDetail ─주소─&gt; NoticeHousing ─PNU─&gt; HousingComplex ─전용면적─&gt; UnitType
 * </pre>
 *
 * <p>가운데 {@link NoticeHousing} 을 거치는 게 우회처럼 보이지만 필연이다 — LH 계열에는 PNU가 없고
 * 카탈로그에는 LH 이름이 없어서, <b>주소와 PNU를 둘 다 가진 유일한 행</b>인 공급행만이 두 세계를 잇는다.
 *
 * <p>앞의 두 구간은 {@link NoticeHousingLhMatch}, {@link NoticeHousingCatalogMatch} 가 이미 계산해 둔 것을
 * 그대로 쓴다. 그래서 이 matcher 는 그 둘의 {@code matcherVersion} 을 입력으로 받는다.
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

    /** 이 결과가 설명하는 15056765 공급행. 이 matcher 의 주어라 항상 있다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lh_unit_supply_id", nullable = false)
    private LhUnitSupply lhUnitSupply;

    /** 체인 중간에서 확정한 공급행. 주소·PNU 구간이 끊기면 없다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notice_housing_id")
    private NoticeHousing noticeHousing;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_type_id")
    private UnitType unitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NoticeHousingUnitTypeMatchStatus status;

    /** {@code lhUnitSupply.suppliedUnitCount} 를 그대로 옮긴 값. 조회 시 조인 없이 바로 쓰려고 복사한다. */
    @Column(name = "supplied_unit_count")
    private Integer suppliedUnitCount;

    /** {@code lhUnitSupply.totalUnitCount} 를 그대로 옮긴 원천 주택형 전체 세대수. */
    @Column(name = "source_total_unit_count")
    private Integer sourceTotalUnitCount;

    /** 부모 NoticeHousing의 공고 공급행 조건. 주택형별로 새로 계산한 값이 아니라 같은 값을 반복 보관한다. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "deposit", column = @Column(name = "notice_deposit")),
            @AttributeOverride(name = "downPayment", column = @Column(name = "notice_down_payment")),
            @AttributeOverride(name = "balance", column = @Column(name = "notice_balance")),
            @AttributeOverride(name = "monthlyRent", column = @Column(name = "notice_monthly_rent"))
    })
    private RentTerms noticeRentTerms;

    /** 15059475 현재 카탈로그 주택형 조건. 공고 당시 조건과 시간 범위가 다르다. */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "deposit", column = @Column(name = "catalog_deposit")),
            @AttributeOverride(name = "monthlyRent", column = @Column(name = "catalog_monthly_rent")),
            @AttributeOverride(name = "convertibleDepositLimit",
                    column = @Column(name = "catalog_convertible_deposit_limit"))
    })
    private BaseRentTerms catalogRentTerms;

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
                                      LhUnitSupply lhUnitSupply,
                                      NoticeHousing noticeHousing,
                                      UnitType unitType,
                                      NoticeHousingUnitTypeMatchStatus status,
                                      Integer suppliedUnitCount,
                                      Integer sourceTotalUnitCount,
                                      RentTerms noticeRentTerms,
                                      BaseRentTerms catalogRentTerms,
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
        this.sourceTotalUnitCount = sourceTotalUnitCount;
        this.noticeRentTerms = noticeRentTerms;
        this.catalogRentTerms = catalogRentTerms;
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
