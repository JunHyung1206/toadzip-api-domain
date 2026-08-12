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


/**
 * 이번 공고버전에서 어느 주택에 수량이 독립적으로 배정됐는가. 원천 HWSPR02 응답의 한 행이 여기 한 줄이다.
 *
 * <p><b>complexId 는 선택이다.</b> 건설임대만 담으면서 PNU 없는 행은 사라졌지만(139/139 보유),
 * 그래도 21건은 안 붙는다. 단지가 원천에 없거나(HWSPR04 는 임대만 담아 분양 단지가 없다)
 * 한 PNU 에 단지가 여럿이라 어느 쪽인지 정할 수 없는 경우다.
 *
 * <p><b>displayOrder 를 houseSn 으로 쓰지 않은 이유.</b> houseSn 은 한 공고 안에서 중복될 수 있어
 * 순서로 쓸 수 없다. 응답 순서를 쓴다.
 *
 * <p>주택형 FK 는 두지 않았다. 공고 원천이 "이 단지에 몇 호"까지만 말하고 주택형별 배분은 주지 않아서
 * 영영 채울 수 없는 칸이었다. 같은 이유로 예비 번호(원천 없음)와 중도금(원천이 늘 0)도 담지 않는다.
 *
 * <p>공고버전이 불변이므로 공급행도 불변이다. 정정공고가 나오면 이 행을 고치는 게 아니라
 * 새 NoticeVersion 아래에 공급행을 다시 만든다.
 */
@Entity
@Table(
        name = "supply_line",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_supply_line_order",
                columnNames = {"notice_version_id", "display_order"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplyLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    /** PNU 로 이미 적재된 단지를 찾았을 때만 채워진다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complex_id")
    private HousingComplex complex;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 설계에 없던 칸. 원천 houseSn. 공고 안의 주택 일련번호. */
    @Column(name = "house_sn")
    private Integer houseSn;

    /** 공급 수(선발 수). 원천 sumSuplyCo. */
    @Column(name = "supply_count")
    private Integer supplyCount;

    /** 설계에 없던 칸. 공고가 말하는 대상 주택을 공고 시점 그대로 보존한다. */
    @Embedded
    private SuppliedHousing suppliedHousing;

    /** 설계에 없던 칸. 이번 공고의 실제 임대조건. */
    @Embedded
    private RentTerms rentTerms;

    /** 설계에 없던 칸. 원천 pcUrl. 공고가 아니라 행마다 다르다(houseSn 이 붙는다). */
    @Column(name = "detail_url", length = 500)
    private String detailUrl;

    /** 설계에 없던 칸. 원천 mobileUrl. */
    @Column(name = "mobile_detail_url", length = 500)
    private String mobileDetailUrl;

    public SupplyLine(NoticeVersion noticeVersion,
                      HousingComplex complex,
                      int displayOrder,
                      Integer houseSn,
                      Integer supplyCount,
                      SuppliedHousing suppliedHousing,
                      RentTerms rentTerms,
                      String detailUrl,
                      String mobileDetailUrl) {
        this.noticeVersion = noticeVersion;
        this.complex = complex;
        this.displayOrder = displayOrder;
        this.houseSn = houseSn;
        this.supplyCount = supplyCount;
        this.suppliedHousing = suppliedHousing;
        this.rentTerms = rentTerms;
        this.detailUrl = detailUrl;
        this.mobileDetailUrl = mobileDetailUrl;
    }

    /**
     * 나중에 적재된 단지를 뒤늦게 붙인다.
     *
     * <p>공급행은 불변인데 이 메서드만 예외인 이유는, {@code complexId} 가 <b>원천 내용이 아니라
     * 우리가 만든 연결</b>이기 때문이다. 공고가 뭐라고 했는지는 {@code suppliedHousing} 에 그대로 남고
     * 여기서 건드리지 않는다.
     *
     * <p>필요한 이유는 순서 때문이다. 단지 API 는 시군구 단위로만 열려 있어서 어느 지역을 받아야 할지
     * 공고에서 알아내야 하는데, 그러면 공고가 먼저 들어오고 단지가 나중에 들어온다.
     * 그 시점에 이미 저장된 공급행은 매칭 기회를 놓친다.
     */
    public void attachComplex(HousingComplex complex) {
        this.complex = complex;
    }

}
