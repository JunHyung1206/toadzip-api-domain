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

import java.time.YearMonth;

/** LH 공고가 그 시점에 안내한 단지 정보. 카탈로그 단지와 추정 연결하지 않는다. */
@Entity
@Table(
        name = "lh_complex_detail",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_complex_snapshot_order",
                columnNames = {"lh_notice_supplement_id", "display_order"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhComplexDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lh_notice_supplement_id", nullable = false)
    private LhNoticeSupplement noticeSupplement;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "complex_label", length = 200)
    private String complexLabel;

    @Column(name = "lot_address", length = 300)
    private String lotAddress;

    @Column(name = "lot_detail_address", length = 200)
    private String lotDetailAddress;

    @Column(name = "total_unit_count")
    private Integer totalUnitCount;

    @Column(name = "heating_description", length = 100)
    private String heatingDescription;

    @Column(name = "exclusive_area_range_text", length = 100)
    private String exclusiveAreaRangeText;

    @Column(name = "expected_move_in_year_month", length = 7)
    private YearMonth expectedMoveInYearMonth;

    @Column(name = "guidance_text", length = 4000)
    private String guidanceText;

    LhComplexDetail(LhNoticeSupplement noticeSupplement,
                    int displayOrder,
                    String complexLabel,
                    String lotAddress,
                    String lotDetailAddress,
                    Integer totalUnitCount,
                    String heatingDescription,
                    String exclusiveAreaRangeText,
                    YearMonth expectedMoveInYearMonth,
                    String guidanceText) {
        this.noticeSupplement = noticeSupplement;
        this.displayOrder = displayOrder;
        this.complexLabel = complexLabel;
        this.lotAddress = lotAddress;
        this.lotDetailAddress = lotDetailAddress;
        this.totalUnitCount = totalUnitCount;
        this.heatingDescription = heatingDescription;
        this.exclusiveAreaRangeText = exclusiveAreaRangeText;
        this.expectedMoveInYearMonth = expectedMoveInYearMonth;
        this.guidanceText = guidanceText;
    }

    /** 주소 매칭에 쓸 원문. 두 주소 칸을 버리지 않고 조립한다. */
    public String fullLotAddress() {
        if (lotAddress == null) {
            return lotDetailAddress;
        }
        return lotDetailAddress == null ? lotAddress : lotAddress + " " + lotDetailAddress;
    }
}
