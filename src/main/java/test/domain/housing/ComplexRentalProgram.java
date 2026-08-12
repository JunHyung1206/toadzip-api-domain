package test.domain.housing;

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

import java.util.Objects;

/**
 * 단지가 공급유형별로 운영하는 임대 프로그램. 원천 hshldCo(세대수)는 단지가 아니라
 * (단지, 공급유형) 단위 값이라 여기서 한 번만 갖는다.
 */
@Entity
@Table(name = "complex_rental_program", uniqueConstraints = @UniqueConstraint(
        name = "uk_complex_rental_program", columnNames = {"housing_complex_id", "supply_type_name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ComplexRentalProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    private HousingComplex housingComplex;

    @Column(name = "supply_type_name", nullable = false, length = 30)
    private String supplyTypeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "supply_type", nullable = false, length = 30)
    private SupplyType supplyType;

    @Column(name = "unit_count")
    private Integer unitCount;

    public ComplexRentalProgram(HousingComplex housingComplex,
                                String supplyTypeName,
                                SupplyType supplyType,
                                Integer unitCount) {
        this.housingComplex = housingComplex;
        this.supplyTypeName = supplyTypeName;
        this.supplyType = supplyType;
        this.unitCount = unitCount;
    }

    public boolean updateUnitCount(Integer incoming) {
        if (Objects.equals(unitCount, incoming)) {
            return false;
        }
        unitCount = incoming;
        return true;
    }
}
