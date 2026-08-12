package test.domain.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

import java.time.LocalDate;

/**
 * 공고와 무관하게 유지되는 주택 카탈로그의 기준점. 원천은 15110581 HWSPR04/rentalHouseGwList.
 *
 * <p><b>hsmpSn 이 단지 식별자로 성립하는 근거</b> — 23개 시군구 7,317행 / 2,524단지를 확인했을 때
 * 아래 값들이 hsmpSn 안에서 갈리는 단지가 <b>0개</b>였다: 단지명, 도로명주소, PNU, 공급기관, 준공일,
 * 주차수, 난방유형, 복도유형, 승강기, 주택유형, 광역시도코드, 시군구코드.
 *
 * <p>단, 단지명(hsmpNm)은 건설임대일 때만 진짜 단지명("낭월다가온 청년주택")이고
 * 매입임대는 지역명("경기도 수원시")이 온다. 흩어진 매입 주택을 지역 단위로 묶은 것이라 그렇다.
 * 그래서 단지 매칭에 이름을 쓰면 안 된다.
 */
@Entity
@Table(
        name = "housing_complex",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_complex_source_id",
                columnNames = "source_complex_id"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HousingComplex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Embedded
    private Address address;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "housing_provider_agency_id", nullable = false)
    private HousingProviderAgency housingProviderAgency;

    /**
     * <b>단지 총 세대수가 아니다.</b> 원천 hshldCo 는 (단지, 공급유형) 단위 값이라
     * 한 단지에 행복주택 20세대 + 5년임대 2세대가 있으면 20과 2가 따로 온다(604단지 중 18개).
     * 여기에는 그중 <b>가장 큰 값</b>만 남는다. 행 순서대로 덮어쓰면 값이 계속 뒤집혀서
     * 다시 적재할 때마다 바뀐 것처럼 보이기 때문이다.
     *
     * <p>진짜 합계가 필요하면 {@link UnitType#getSupplyTypeUnitCount()} 를 공급유형별로 중복 제거해 더하면 된다.
     */
    @Column(name = "max_supply_type_unit_count")
    private Integer maxSupplyTypeUnitCount;

    /** 설계 필드. 아래 completionDate 에서 연도만 뽑은 값이라 같이 채워진다. */
    private Integer completionYear;

    /** 설계에 없던 칸. 원천 competDe 는 "20250627" 처럼 날짜를 다 준다. 221/2,005(11%)만 채워진다. */
    @Column(name = "completion_date")
    private LocalDate completionDate;

    /** 원천의 단지 식별자 hsmpSn. */
    @Column(name = "source_complex_id")
    private String sourceComplexId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private HeatingType heatingType;

    /** 설계에 없던 칸. 원천 heatMthdDetailNm 원문. heatingType 이 열원(가스/전기/폐열)을 버리기 때문에 남긴다. */
    @Column(name = "heating_type_name", length = 30)
    private String heatingTypeName;

    private Integer parkingSpaces;


    /** 설계에 없던 칸. 원천 buldStleNm. 계단식/복도식/혼합식. */
    @Column(name = "corridor_type", length = 20)
    private String corridorType;

    /** 설계에 없던 칸. 원천 elvtrInstlAtNm. "미설치" / "전체동 설치". */
    @Column(name = "elevator_installation", length = 20)
    private String elevatorInstallation;

    /** 설계에 없던 칸. 원천 houseTyNm 을 정리한 값. 모르는 값이면 null 이고 원문은 아래에 남는다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "house_type", length = 30)
    private HouseType houseType;

    /** 설계에 없던 칸. 원천 houseTyNm 원문. 표본의 7%는 아예 비어 있다. */
    @Column(name = "house_type_name", length = 30)
    private String houseTypeName;

    public HousingComplex(String name,
                          Address address,
                          HousingProviderAgency housingProviderAgency,
                          String sourceComplexId) {
        this.name = name;
        this.address = address;
        this.housingProviderAgency = housingProviderAgency;
        this.sourceComplexId = sourceComplexId;
    }

    /**
     * 원천을 다시 읽었을 때 선택 항목만 덮어쓴다.
     * 이름/주소/공급기관은 단지의 정체성이라 여기서 건드리지 않는다.
     *
     * <p>원천 한 행이 주택형 하나라서 같은 단지가 수십 번 다시 들어온다. 바뀐 게 없으면 UPDATE 를
     * 보내지 않으려고 바뀌었는지를 돌려준다.
     *
     * @return 실제로 값이 바뀌었으면 true
     */
    public boolean updateCatalogDetails(CatalogDetails details) {
        CatalogDetails merged = details.withMaxSupplyTypeUnitCount(
                largerUnitCount(details.maxSupplyTypeUnitCount()));
        if (currentCatalogDetails().equals(merged)) {
            return false;
        }
        this.maxSupplyTypeUnitCount = merged.maxSupplyTypeUnitCount();
        this.completionDate = merged.completionDate();
        this.completionYear = merged.completionDate() == null ? null : merged.completionDate().getYear();
        this.heatingType = merged.heatingType();
        this.heatingTypeName = merged.heatingTypeName();
        this.parkingSpaces = merged.parkingSpaces();
        this.corridorType = merged.corridorType();
        this.elevatorInstallation = merged.elevatorInstallation();
        this.houseType = merged.houseType();
        this.houseTypeName = merged.houseTypeName();
        return true;
    }

    /** 덮어쓰지 않고 큰 값을 남기는 이유는 {@link #maxSupplyTypeUnitCount} 주석에 있다. */
    private Integer largerUnitCount(Integer incoming) {
        if (this.maxSupplyTypeUnitCount == null) {
            return incoming;
        }
        if (incoming == null) {
            return this.maxSupplyTypeUnitCount;
        }
        return Math.max(this.maxSupplyTypeUnitCount, incoming);
    }

    public CatalogDetails currentCatalogDetails() {
        return new CatalogDetails(maxSupplyTypeUnitCount, completionDate, heatingType, heatingTypeName,
                parkingSpaces, corridorType, elevatorInstallation, houseType, houseTypeName);
    }
}
