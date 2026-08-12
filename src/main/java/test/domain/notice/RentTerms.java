package test.domain.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * 이번 공고에서 이 공급행에 걸린 실제 임대조건.
 * 카탈로그가 들고 있는 기준값({@link test.domain.housing.BaseRentTerms})과는 다른 값이다.
 *
 * <p>보증금을 계약금과 잔금으로 나눠 낸다. 원천의 중도금(prtpay)은 표본 전체에서 0이라 담지 않는다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentTerms {

    /** 원천 rentGtn. 임대보증금(원). */
    @Column(name = "deposit")
    private Long deposit;

    /** 원천 enty. 계약금(원). */
    @Column(name = "down_payment")
    private Long downPayment;

    /** 원천 surlus. 잔금(원). */
    @Column(name = "balance")
    private Long balance;

    /** 원천 mtRntchrg. 월임대료(원). */
    @Column(name = "monthly_rent")
    private Long monthlyRent;

    public RentTerms(Long deposit, Long downPayment, Long balance, Long monthlyRent) {
        this.deposit = deposit;
        this.downPayment = downPayment;
        this.balance = balance;
        this.monthlyRent = monthlyRent;
    }

    /** 재수집 시 원천 내용이 그대로인지 비교하는 값 비교. JPA 식별자는 없으므로 비교 대상이 아니다. */
    public static boolean sameValues(RentTerms left, RentTerms right) {
        if (left == null || right == null) {
            return left == right;
        }
        return Objects.equals(left.deposit, right.deposit)
                && Objects.equals(left.downPayment, right.downPayment)
                && Objects.equals(left.balance, right.balance)
                && Objects.equals(left.monthlyRent, right.monthlyRent);
    }
}
