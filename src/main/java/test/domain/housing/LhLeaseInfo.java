package test.domain.housing;

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

/** 15059475의 dsList 한 행. 원천에 안정적인 단지 식별자가 없어 매칭 결과는 별도 엔티티로 남긴다. */
@Entity
@Table(
        name = "lh_lease_info",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lh_lease_info_order",
                columnNames = {"lh_lease_info_batch_id", "display_order"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhLeaseInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lh_lease_info_batch_id", nullable = false)
    private LhLeaseInfoBatch batch;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "area_name", length = 50)
    private String areaName;

    @Column(name = "supply_type_name", length = 30)
    private String supplyTypeName;

    @Column(name = "complex_label", length = 200)
    private String complexLabel;

    /** 원천 SUM_HSH_CNT. 단지·공급유형 전체 세대수이며 매칭 검증에 쓴다. */
    @Column(name = "complex_total_unit_count")
    private Integer complexTotalUnitCount;

    /** 원천 DDO_AR. */
    @Column(name = "exclusive_area", precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    /** 원천 HSH_CNT. 이 전용면적 주택형의 전체 세대수다. */
    @Column(name = "total_unit_count")
    private Integer totalUnitCount;

    LhLeaseInfo(LhLeaseInfoBatch batch,
                int displayOrder,
                String areaName,
                String supplyTypeName,
                String complexLabel,
                Integer complexTotalUnitCount,
                BigDecimal exclusiveArea,
                Integer totalUnitCount) {
        this.batch = batch;
        this.displayOrder = displayOrder;
        this.areaName = areaName;
        this.supplyTypeName = supplyTypeName;
        this.complexLabel = complexLabel;
        this.complexTotalUnitCount = complexTotalUnitCount;
        this.exclusiveArea = exclusiveArea;
        this.totalUnitCount = totalUnitCount;
    }
}
