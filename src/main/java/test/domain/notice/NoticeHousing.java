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


/**
 * 이번 공고버전에서 어느 주택에 수량이 독립적으로 배정됐는가. 원천 HWSPR02 응답의 한 행이 여기 한 줄이다.
 *
 * <p><b>카탈로그 FK 는 두지 않는다.</b> 공고가 가리키는 주택을 원천 그대로(주소·PNU 등) {@link SuppliedHousing}
 * 에 보존할 뿐, 우리 단지 카탈로그({@code housing_complex})와 잇는 일은 이 aggregate 의 책임이 아니다.
 * 그 매칭은 별도 파생 matcher 가 한다.
 *
 * <p><b>자연키가 (notice_version_id, house_sn) 인 이유.</b> houseSn 은 원천이 공급행마다 매기는
 * 일련번호로, 한 공고버전 안에서 유일하다.
 *
 * <p><b>displayOrder 를 따로 두는 이유.</b> 화면에 보여줄 순서는 원천 응답 순서를 따르는데, 이게
 * houseSn 순서와 반드시 같지는 않다.
 *
 * <p>주택형 FK 는 두지 않았다. 공고 원천이 "이 단지에 몇 호"까지만 말하고 주택형별 배분은 주지 않아서
 * 영영 채울 수 없는 칸이었다. 같은 이유로 예비 번호(원천 없음)와 중도금(원천이 늘 0)도 담지 않는다.
 *
 * <p>공고버전이 불변이므로 공급행도 불변이다. 정정공고가 나오면 이 행을 고치는 게 아니라
 * 새 NoticeVersion 아래에 공급행을 다시 만든다.
 */
@Entity
@Table(
        name = "notice_housing",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_housing_natural",
                columnNames = {"notice_version_id", "house_sn"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeHousing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 원천 houseSn. 공고 안의 주택 일련번호. */
    @Column(name = "house_sn", nullable = false)
    private int houseSn;

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

    public NoticeHousing(NoticeVersion noticeVersion,
                         int displayOrder,
                         int houseSn,
                         Integer supplyCount,
                         SuppliedHousing suppliedHousing,
                         RentTerms rentTerms,
                         String detailUrl,
                         String mobileDetailUrl) {
        this.noticeVersion = noticeVersion;
        this.displayOrder = displayOrder;
        this.houseSn = houseSn;
        this.supplyCount = supplyCount;
        this.suppliedHousing = suppliedHousing;
        this.rentTerms = rentTerms;
        this.detailUrl = detailUrl;
        this.mobileDetailUrl = mobileDetailUrl;
    }
}
