package test.domain.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.math.BigDecimal;

/**
 * LH 15056765 응답({@code dsList01}) 한 행. 원천은 {@code PAN_ID} 하나에 단지 여러 곳을 섞어 준다 —
 * {@link #complexLabel}이 어느 단지인지 말해 줄 뿐 PNU가 없다. 단지·주택형 매칭은
 * {@code test.domain.match.NoticeHousingUnitTypeMatchService}가 별도로 계산한다.
 */
@Entity
@Table(
        name = "lh_unit_supply",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lh_unit_supply_order",
                columnNames = {"lh_unit_supply_batch_id", "display_order"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhUnitSupply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lh_unit_supply_batch_id", nullable = false)
    private LhUnitSupplyBatch unitSupplyBatch;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 원천 SBD_LGO_NM. 단지명 원문 — 카탈로그 단지명과 문자열로만 대조한다(PNU 없음). */
    @Column(name = "complex_label", length = 200)
    private String complexLabel;

    /** 원천 HTY_NNA. "59㎡"처럼 단위가 붙기도 해 {@code UnitType.typeName}과 그대로 비교하지 않는다. */
    @Column(name = "type_name", length = 50)
    private String typeName;

    /** 원천 DDO_AR. 매칭의 주 근거 — 카탈로그 주택형과 대조하는 값이다. */
    @Column(name = "exclusive_area", precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    /** 원천 SPL_AR. */
    @Column(name = "supply_area", precision = 10, scale = 4)
    private BigDecimal supplyArea;

    /** 원천 HSH_CNT. 단지·주택형 전체 세대수(카탈로그 값과는 다를 수 있다). */
    @Column(name = "total_unit_count")
    private Integer totalUnitCount;

    /** 원천 NOW_HSH_CNT. 이번 공고 회차의 공급 세대수 — {@link NoticeHousing}에 없던 값이다. */
    @Column(name = "supplied_unit_count")
    private Integer suppliedUnitCount;

    LhUnitSupply(LhUnitSupplyBatch unitSupplyBatch,
                int displayOrder,
                String complexLabel,
                String typeName,
                BigDecimal exclusiveArea,
                BigDecimal supplyArea,
                Integer totalUnitCount,
                Integer suppliedUnitCount) {
        this.unitSupplyBatch = unitSupplyBatch;
        this.displayOrder = displayOrder;
        this.complexLabel = complexLabel;
        this.typeName = typeName;
        this.exclusiveArea = exclusiveArea;
        this.supplyArea = supplyArea;
        this.totalUnitCount = totalUnitCount;
        this.suppliedUnitCount = suppliedUnitCount;
    }
}
