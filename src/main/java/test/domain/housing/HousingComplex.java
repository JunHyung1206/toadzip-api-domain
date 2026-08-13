package test.domain.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import test.domain.source.SourceSystem;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 공고와 무관하게 유지되는 주택 카탈로그의 기준점. 원천은 15110581 HWSPR04/rentalHouseGwList.
 *
 * <p><b>hsmpSn 이 물리 단지 식별자로 성립하는 근거</b> — 23개 시군구 7,317행 / 2,524단지를 확인했을 때
 * 아래 값들이 같은 hsmpSn 안에서 갈리는 단지가 <b>0개</b>였다: 단지명, 도로명주소, PNU, 공급기관, 준공일,
 * 주차수, 난방유형, 복도유형, 승강기, 주택유형, 광역시도코드, 시군구코드.
 *
 * <p>단, 단지명(hsmpNm)은 건설임대일 때만 진짜 단지명("낭월다가온 청년주택")이고
 * 매입임대는 지역명("경기도 수원시")이 온다. 흩어진 매입 주택을 지역 단위로 묶은 것이라 그렇다.
 * 그래서 단지 매칭에 이름을 쓰면 안 된다. 공급유형이 다르면 같은 hsmpSn이어도 별도 카탈로그 행으로 저장한다.
 */
@Entity
@Table(name = "housing_complex", uniqueConstraints = @UniqueConstraint(
        name = "uk_housing_complex_source", columnNames = {"source_system", "source_complex_id", "supply_type"}))
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

    /** 설계 필드. 아래 completionDate 에서 연도만 뽑은 값이라 같이 채워진다. */
    private Integer completionYear;

    /** 설계에 없던 칸. 원천 competDe 는 "20250627" 처럼 날짜를 다 준다. 221/2,005(11%)만 채워진다. */
    @Column(name = "completion_date")
    private LocalDate completionDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    /** 원천의 단지 식별자 hsmpSn. */
    @Column(name = "source_complex_id", nullable = false)
    private String sourceComplexId;

    /** 같은 원천 단지라도 공급유형이 다르면 별도 단지 행으로 저장한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "supply_type", nullable = false, length = 30)
    private SupplyType supplyType;

    /** 원천 suplyTyNm. enum이 모르는 표기도 증거로 남긴다. */
    @Column(name = "supply_type_name", nullable = false, length = 50)
    private String supplyTypeName;

    /** 원천 hshldCo. 단지·공급유형 전체 세대수다. */
    @Column(name = "unit_count")
    private Integer unitCount;

    /** 원천 insttNm. 공급기관 마스터 없이 단지 행에 직접 저장한다. */
    @Column(name = "supply_institution_name", nullable = false, length = 50)
    private String supplyInstitutionName;

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
                          SourceSystem sourceSystem,
                          String sourceComplexId,
                          SupplyType supplyType,
                          String supplyTypeName,
                          Integer unitCount,
                          String supplyInstitutionName) {
        this.name = name;
        this.address = address;
        this.sourceSystem = sourceSystem;
        this.sourceComplexId = sourceComplexId;
        this.supplyType = Objects.requireNonNull(supplyType, "supplyType는 필수입니다.");
        this.supplyTypeName = requireText(supplyTypeName, "supplyTypeName");
        this.unitCount = unitCount;
        this.supplyInstitutionName = requireText(supplyInstitutionName, "supplyInstitutionName");
    }

    /**
     * 원천을 다시 읽었을 때 선택 항목만 덮어쓴다.
     * 이름/주소/PNU는 원천 식별 정보라 여기서 건드리지 않는다. 공급유형별 기관·세대수는
     * {@link #updateSupplyDetails(String, Integer, String)}에서 별도로 갱신한다.
     *
     * <p>원천 한 행이 주택형 하나라서 같은 단지가 수십 번 다시 들어온다. 바뀐 게 없으면 UPDATE 를
     * 보내지 않으려고 바뀌었는지를 돌려준다.
     *
     * @return 실제로 값이 바뀌었으면 true
     */
    public boolean updateCatalogDetails(CatalogDetails details) {
        if (currentCatalogDetails().equals(details)) {
            return false;
        }
        completionDate = details.completionDate();
        completionYear = completionDate == null ? null : completionDate.getYear();
        heatingType = details.heatingType();
        heatingTypeName = details.heatingTypeName();
        parkingSpaces = details.parkingSpaces();
        corridorType = details.corridorType();
        elevatorInstallation = details.elevatorInstallation();
        houseType = details.houseType();
        houseTypeName = details.houseTypeName();
        return true;
    }

    /** 공급유형 행의 원천 수치·문자열을 다시 읽었을 때 갱신한다. */
    public boolean updateSupplyDetails(String incomingSupplyTypeName,
                                       Integer incomingUnitCount,
                                       String incomingSupplyInstitutionName) {
        String normalizedTypeName = requireText(incomingSupplyTypeName, "supplyTypeName");
        String normalizedInstitutionName = requireText(incomingSupplyInstitutionName, "supplyInstitutionName");
        if (Objects.equals(supplyTypeName, normalizedTypeName)
                && Objects.equals(unitCount, incomingUnitCount)
                && Objects.equals(supplyInstitutionName, normalizedInstitutionName)) {
            return false;
        }
        supplyTypeName = normalizedTypeName;
        unitCount = incomingUnitCount;
        supplyInstitutionName = normalizedInstitutionName;
        return true;
    }

    public CatalogDetails currentCatalogDetails() {
        return new CatalogDetails(completionDate, heatingType, heatingTypeName,
                parkingSpaces, corridorType, elevatorInstallation, houseType, houseTypeName);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 필수입니다.");
        }
        return value.strip();
    }
}
