package test.domain.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.util.Objects;

/**
 * 한 단지 안에서 면적·구조·평면을 공유하는 카탈로그 항목. 원천 HWSPR04 의 한 행이 여기 한 줄이다.
 *
 * <p><b>자연키에 공급유형과 면적이 들어가는 이유.</b> 7,317행으로 조합을 세어 보면
 * <pre>
 *   (단지, 주택형명)                        고유 6,063 / 충돌 768
 *   (단지, 주택형명, 전용, 공용)             고유 7,308 / 충돌   9
 *   (단지, 공급유형, 주택형명, 전용, 공용)    고유 7,317 / 충돌   0
 * </pre>
 * 면적이 필요한 건 매입임대가 호마다 따로 사들인 집이라 같은 "39형" 안에 39.27㎡와 39.57㎡가 같이 있어서고,
 * 공급유형이 필요한 건 같은 평면이라도 국민임대냐 행복주택이냐에 따라 임대조건이 다른 행이 따로 오기 때문이다.
 * 공급유형은 이제 소속 {@link ComplexRentalProgram} 이 들고 있다.
 */
@Entity
@Table(
        name = "unit_type",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_unit_type_natural",
                columnNames = {"complex_rental_program_id", "type_name",
                        "exclusive_area", "residential_common_area"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complex_rental_program_id", nullable = false)
    private ComplexRentalProgram complexRentalProgram;

    /** 원천 styleNm. "36", "84", "51A" 처럼 전용면적을 반올림한 숫자에 알파벳이 붙기도 한다. */
    @Column(name = "type_name", nullable = false, length = 50)
    private String typeName;

    /** 면적은 ㎡ 소수 넷째 자리까지. double 이면 전용+공용 합산에서 오차가 샌다. */
    @Column(name = "exclusive_area", precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    @Column(name = "residential_common_area", precision = 10, scale = 4)
    private BigDecimal residentialCommonArea;

    /** LH 임대주택단지 조회(15059475)의 HSH_CNT. 이 전용면적 주택형의 전체 세대수다. */
    @Column(name = "total_unit_count")
    private Integer totalUnitCount;

    /** 설계에 없던 칸. 공고와 무관한 기본 임대조건. */
    @Embedded
    private BaseRentTerms baseRentTerms;

    public UnitType(ComplexRentalProgram complexRentalProgram,
                    String typeName,
                    BigDecimal exclusiveArea,
                    BigDecimal residentialCommonArea) {
        this.complexRentalProgram = complexRentalProgram;
        this.typeName = typeName;
        this.exclusiveArea = exclusiveArea;
        this.residentialCommonArea = residentialCommonArea;
    }

    /** @return 실제로 값이 바뀌었으면 true. 안 바뀌었으면 UPDATE 를 보내지 않는다. */
    public boolean updateBaseRentTerms(BaseRentTerms incoming) {
        if (BaseRentTerms.sameValues(this.baseRentTerms, incoming)) {
            return false;
        }
        this.baseRentTerms = incoming;
        return true;
    }

    /** @return 실제로 값이 바뀌었으면 true. */
    public boolean updateTotalUnitCount(Integer incoming) {
        if (Objects.equals(totalUnitCount, incoming)) {
            return false;
        }
        totalUnitCount = incoming;
        return true;
    }
}
