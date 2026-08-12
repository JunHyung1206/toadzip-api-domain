package test.domain.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 주택 공급기관(LH, SH, GH ...). */
@Entity
@Table(
        name = "housing_provider_agency",
        uniqueConstraints = @UniqueConstraint(name = "uk_agency_code", columnNames = "code")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousingProviderAgency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private String name;

    public HousingProviderAgency(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
