package test.domain.notice;

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
import test.domain.housing.HousingComplex;
import test.domain.housing.UnitType;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 이 공고가 공급하는 한 덩어리. 예전에 나뉘어 있던 {@code notice_housing}(마이홈 15108420),
 * {@code lh_unit_supply}(LH 15056765), {@code lh_complex_detail}(LH 15057999 dsSbd), 그리고 세 match
 * 테이블을 한 행으로 합친 자리다.
 *
 * <p><b>행의 알갱이가 두 가지다.</b> LH 가 주택형을 쪼개 주면 {@code 공고 × 단지 × 주택형} 이고,
 * 안 주면(SH·GH 공고, 또는 주소가 안 맞아 못 붙인 단지) {@code 공고 × 단지} 한 줄이다.
 * {@link #typeName} 이 있는지로 구분한다.
 *
 * <p><b>그래서 합계는 한쪽 기준으로만 낸다.</b> LH 주택형 행 290개 중 37개가 마이홈 공급행을 못 만났고,
 * 마이홈 공급행 113개 중 20개가 LH 주택형 행을 못 만났다(2026-08-13 실측). 이 둘 중 일부는 주소가 안 맞아
 * 서로를 못 찾은 <b>같은 단지</b>라 한 테이블에서 두 줄로 보인다. 두 원천이 말하는 총 공급 호수도
 * 애초에 다르다(LH 13,575호 vs 마이홈 12,927호). 섞어 더하면 안 된다.
 *
 * <ul>
 *   <li>LH 기준 — {@code WHERE type_name IS NOT NULL} 에서 {@link #unitSupplyCount} 합</li>
 *   <li>마이홈 기준 — {@code house_sn} 으로 중복을 지운 뒤 {@link #complexSupplyCount} 합</li>
 * </ul>
 *
 * <p><b>돈이 반복된다.</b> 마이홈은 임대조건을 단지 단위(houseSn)로만 준다. 한 단지에 주택형이 5개면
 * 같은 보증금이 5줄에 들어간다. 주택형별 임대료를 진짜로 주는 건 15056765 의 {@code LS_GMY}·{@code RFE}
 * 뿐인데 값이 숫자로 온다는 보장이 없어({@link #lhDepositText} 참고) 문자열로만 받아 둔다.
 *
 * <p>공고가 불변이라 공급행도 불변이다. 정정공고가 나오면 이 행을 고치는 게 아니라 새 {@link Notice}
 * 아래에 공급행을 다시 만든다. 다만 카탈로그 FK 두 칸은 판단 결과라 나중에 다시 채울 수 있다
 * ({@link #linkCatalog}).
 */
@Entity
@Table(
        name = "notice_supply",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_supply_order",
                columnNames = {"notice_id", "display_order"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeSupply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    /**
     * 공고 안 표시 순서. 자연키로 쓰기에는 원천마다 알갱이가 달라(LH 행은 단지명+주택형, 마이홈 행은
     * houseSn) 한쪽으로 정할 수 없어서 순번을 유일키로 둔다. 재적재는 공고 단위 통째 교체다.
     */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 원천 houseSn. 마이홈 공급행 일련번호이자 <b>같은 단지 행을 묶는 키</b>. LH 쪽만 있는 행은 null. */
    @Column(name = "house_sn")
    private Integer houseSn;

    // ── 카탈로그 연결 (적재 시 확정된 것만) ────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "housing_complex_id")
    private HousingComplex housingComplex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_type_id")
    private UnitType unitType;

    /** {@link #unitType} 이 비어 있는 이유. 확정된 행은 null 이라 "붙었나"는 FK 로 판단한다. */
    @Column(name = "unmatched_reason", length = 200)
    private String unmatchedReason;

    // ── 주택형 (LH 15056765) ──────────────────────────────────────────────

    /** 원천 HTY_NNA. LH 주택형명 원문 — "59㎡", "26A(청년)" 처럼 공급대상까지 붙기도 한다. */
    @Column(name = "type_name", length = 100)
    private String typeName;

    /** 원천 DDO_AR. 전용면적(㎡). 카탈로그 주택형과 잇는 키다. */
    @Column(name = "exclusive_area", precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    /** 원천 SPL_AR. 공급면적(㎡). */
    @Column(name = "supply_area", precision = 10, scale = 4)
    private BigDecimal supplyArea;

    /** 원천 NOW_HSH_CNT. <b>금회 공급호수</b> — 이번 공고가 이 주택형을 몇 호 공급하는가. */
    @Column(name = "unit_supply_count")
    private Integer unitSupplyCount;

    /** 원천 HSH_CNT. 이 주택형이 단지 전체에 몇 세대 있는가. 금회 공급호수와 다른 값이다. */
    @Column(name = "unit_total_count")
    private Integer unitTotalCount;

    /**
     * 원천 LS_GMY 원문. 숫자 컬럼이 아닌 이유 — 기록해 둔 실측 fixture 에서는 이 값이
     * {@code "공고문 참조"} 문자열이었다. API 스스로는 같은 응답의 {@code dsList01Nm} 에
     * "임대보증금(원)" 이라고 쓴다. 어느 쪽이 오는지 세어 본 뒤에 숫자 컬럼으로 승격한다.
     */
    @Column(name = "lh_deposit_text", length = 100)
    private String lhDepositText;

    /** 원천 RFE 원문. {@link #lhDepositText} 와 같은 이유로 문자열이다. */
    @Column(name = "lh_monthly_rent_text", length = 100)
    private String lhMonthlyRentText;

    // ── 단지 (마이홈 15108420 + LH 15057999) ─────────────────────────────

    /** 원천 hsmpNm. 마이홈이 부르는 단지명. */
    @Column(name = "complex_name", length = 200)
    private String complexName;

    /** 원천 SBD_LGO_NM / LCC_NT_NM. LH 가 부르는 단지명. 마이홈 이름과 명명 체계가 다르다. */
    @Column(name = "lh_complex_label", length = 200)
    private String lhComplexLabel;

    /** 원천 pnu. 카탈로그 단지 FK 를 다시 계산할 입력이다. */
    @Column(name = "supplied_pnu", length = 19)
    private String suppliedPnu;

    /**
     * 원천 fullAdres. LH 단지 상세({@code dsSbd})의 지번주소와 대조해 <b>어느 LH 주택형 행이 이 단지의
     * 행인지</b> 정하는 키다. 시군구명·도로명·법정동명 같은 나머지 주소 조각은 이 값에 이미 들어 있어
     * 따로 두지 않는다.
     *
     * <p>마이홈 공고 적재와 LH 적재는 서로 다른 패스라, 이 값을 안 남기면 두 번째 패스가 짝을 찾을
     * 방법이 없다. PNU 와 함께 공급행에 남는 단 둘뿐인 매칭 키다.
     */
    @Column(name = "supplied_address", length = 300)
    private String suppliedAddress;

    /** 원천 sumSuplyCo. 이 단지에 이번 공고가 몇 호 공급하는가. 같은 houseSn 행끼리 반복된다. */
    @Column(name = "complex_supply_count")
    private Integer complexSupplyCount;

    /** 원천 totHshldCo. 이 단지 전체 세대수. 같은 houseSn 행끼리 반복된다. */
    @Column(name = "complex_total_unit_count")
    private Integer complexTotalUnitCount;

    /** 원천 dsSbd.MVIN_XPC_YM. 입주 예정 연월. 같은 단지 행끼리 반복된다. */
    @Column(name = "move_in_year_month", length = 7)
    private YearMonth moveInYearMonth;

    /** 이번 공고의 실제 임대조건. 마이홈이 단지 단위로만 줘서 주택형 행마다 반복된다. */
    @Embedded
    private RentTerms rentTerms;

    /** 원천 pcUrl. 공고가 아니라 행마다 다르다(houseSn 이 붙는다). */
    @Column(name = "detail_url", length = 500)
    private String detailUrl;

    /** 원천 mobileUrl. */
    @Column(name = "mobile_detail_url", length = 500)
    private String mobileDetailUrl;

    private NoticeSupply(Notice notice, int displayOrder) {
        this.notice = notice;
        this.displayOrder = displayOrder;
    }

    /**
     * 마이홈 공급행 하나로 만드는 단지 단위 행. LH 주택형 정보를 받기 전의 모습이자,
     * 끝내 못 받은 단지의 최종 모습이다.
     */
    public static NoticeSupply ofComplex(Notice notice,
                                         int displayOrder,
                                         Integer houseSn,
                                         String complexName,
                                         String suppliedPnu,
                                         String suppliedAddress,
                                         Integer complexSupplyCount,
                                         Integer complexTotalUnitCount,
                                         RentTerms rentTerms,
                                         String detailUrl,
                                         String mobileDetailUrl) {
        NoticeSupply supply = new NoticeSupply(notice, displayOrder);
        supply.houseSn = houseSn;
        supply.complexName = complexName;
        supply.suppliedPnu = suppliedPnu;
        supply.suppliedAddress = suppliedAddress;
        supply.complexSupplyCount = complexSupplyCount;
        supply.complexTotalUnitCount = complexTotalUnitCount;
        supply.rentTerms = rentTerms;
        supply.detailUrl = detailUrl;
        supply.mobileDetailUrl = mobileDetailUrl;
        return supply;
    }

    /**
     * 이 단지 행을 LH 주택형 하나로 쪼갠 새 행. 마이홈이 준 단지·임대조건은 그대로 복사된다 —
     * 원천이 주택형별 임대조건을 주지 않아서 같은 값이 주택형 수만큼 반복된다.
     */
    public NoticeSupply splitInto(int displayOrder, LhUnitSupplyValues lh) {
        NoticeSupply supply = copyAt(displayOrder);
        supply.applyLh(lh);
        return supply;
    }

    /**
     * 순번만 바꾼 같은 행. LH 적재가 공고의 공급행을 통째로 다시 쓸 때, 주택형으로 못 쪼갠 단지 행을
     * 그대로 옮겨 담는 데 쓴다.
     */
    public NoticeSupply copyAt(int displayOrder) {
        NoticeSupply supply = new NoticeSupply(notice, displayOrder);
        supply.houseSn = houseSn;
        supply.complexName = complexName;
        supply.suppliedPnu = suppliedPnu;
        supply.suppliedAddress = suppliedAddress;
        supply.complexSupplyCount = complexSupplyCount;
        supply.complexTotalUnitCount = complexTotalUnitCount;
        supply.rentTerms = copyOf(rentTerms);
        supply.detailUrl = detailUrl;
        supply.mobileDetailUrl = mobileDetailUrl;
        supply.moveInYearMonth = moveInYearMonth;
        return supply;
    }

    /**
     * 어떤 마이홈 공급행에도 못 붙은 LH 주택형 행. 주소가 안 맞아 짝을 못 찾은 경우라
     * 임대조건도 PNU 도 없다 — 그래도 금회 공급호수는 원천 사실이므로 버리지 않는다.
     */
    public static NoticeSupply ofLhOnly(Notice notice, int displayOrder, LhUnitSupplyValues lh) {
        NoticeSupply supply = new NoticeSupply(notice, displayOrder);
        supply.applyLh(lh);
        return supply;
    }

    /** 카탈로그 연결 결과를 덮어쓴다. 규칙이 바뀌면 원천 재호출 없이 이 두 칸만 다시 채운다. */
    public void linkCatalog(HousingComplex housingComplex, UnitType unitType, String unmatchedReason) {
        this.housingComplex = housingComplex;
        this.unitType = unitType;
        this.unmatchedReason = unitType == null ? unmatchedReason : null;
    }

    /** 입주 예정 연월은 LH 단지 상세에만 있어 주소로 짝을 찾은 뒤에 붙는다. */
    public void applyMoveInYearMonth(YearMonth moveInYearMonth) {
        this.moveInYearMonth = moveInYearMonth;
    }

    private void applyLh(LhUnitSupplyValues lh) {
        this.lhComplexLabel = lh.complexLabel();
        this.typeName = lh.typeName();
        this.exclusiveArea = lh.exclusiveArea();
        this.supplyArea = lh.supplyArea();
        this.unitSupplyCount = lh.suppliedUnitCount();
        this.unitTotalCount = lh.totalUnitCount();
        this.lhDepositText = lh.depositText();
        this.lhMonthlyRentText = lh.monthlyRentText();
    }

    /** @Embeddable 인스턴스를 두 엔티티가 나눠 갖지 않도록 복사한다. */
    private static RentTerms copyOf(RentTerms source) {
        if (source == null) {
            return null;
        }
        return new RentTerms(source.getDeposit(), source.getDownPayment(),
                source.getBalance(), source.getMonthlyRent());
    }
}
