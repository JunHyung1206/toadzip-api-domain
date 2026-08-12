package test.domain.housing;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 주택형에 붙는 <b>기본</b> 임대조건. 공고와 무관하게 카탈로그가 들고 있는 기준값이다.
 * 이번 공고의 실제 조건은 {@link test.domain.notice.RentTerms} 쪽이고, 둘은 다른 값이다.
 *
 * <p>같은 평면이라도 공급유형이 다르면 여기가 달라진다. 실제로 (단지 31696890, 51A, 전용 51.7377)이
 * 국민임대 보증금 1.25억 / 행복주택 보증금 1.97억으로 갈렸다. 그래서 공급유형이 주택형 자연키에 들어간다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BaseRentTerms {

    /** 원천 bassRentGtn. 기본 임대보증금(원). */
    @Column(name = "base_deposit")
    private Long deposit;

    /** 원천 bassMtRntchrg. 기본 월임대료(원). */
    @Column(name = "base_monthly_rent")
    private Long monthlyRent;

    /** 원천 bassCnvrsGtnLmt. 월세를 보증금으로 전환할 수 있는 한도(원). */
    @Column(name = "base_convertible_deposit_limit")
    private Long convertibleDepositLimit;

    public BaseRentTerms(Long deposit, Long monthlyRent, Long convertibleDepositLimit) {
        this.deposit = deposit;
        this.monthlyRent = monthlyRent;
        this.convertibleDepositLimit = convertibleDepositLimit;
    }

    /** 적재가 "바뀐 게 없으면 쓰지 않기"를 판단할 때만 쓴다. 엔티티가 아니라 값이라 필드로 비교한다. */
    public static boolean sameValues(BaseRentTerms left, BaseRentTerms right) {
        if (left == null || right == null) {
            return left == right;
        }
        return Objects.equals(left.deposit, right.deposit)
                && Objects.equals(left.monthlyRent, right.monthlyRent)
                && Objects.equals(left.convertibleDepositLimit, right.convertibleDepositLimit);
    }
}
