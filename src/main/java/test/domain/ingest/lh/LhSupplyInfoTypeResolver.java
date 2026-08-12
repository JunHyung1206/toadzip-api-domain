package test.domain.ingest.lh;

import org.springframework.stereotype.Component;
import test.domain.housing.SupplyType;

import java.util.Optional;

/**
 * 마이홈 {@link SupplyType} 을 LH 15057999 호출에 필요한 공급정보구분코드(SPL_INF_TP_CD)로 옮긴다.
 *
 * <p>통합공공임대는 아직 어느 코드로 응답하는지 실측하지 못해 뒤로 미룬다({@link Optional#empty()}).
 * 매입임대·전세임대는 건설형이 아니라 이 서비스로 들어올 일이 없어야 하므로 예외로 막는다.
 */
@Component
public class LhSupplyInfoTypeResolver {

    public Optional<String> resolve(SupplyType supplyType) {
        if (supplyType == null || supplyType == SupplyType.INTEGRATED_PUBLIC_RENTAL) {
            return Optional.empty();
        }
        return Optional.of(switch (supplyType) {
            case FIVE_YEAR_RENTAL, TEN_YEAR_RENTAL -> "060";
            case FIFTY_YEAR_RENTAL -> "061";
            case NATIONAL_RENTAL, PERMANENT_RENTAL, LONG_TERM_JEONSE -> "062";
            case HAPPY_HOUSE -> "063";
            case PURCHASED_RENTAL, JEONSE_RENTAL -> throw new IllegalArgumentException(
                    "건설임대가 아닌 공급유형입니다: " + supplyType);
            case INTEGRATED_PUBLIC_RENTAL -> throw new IllegalStateException("앞에서 제외되어야 합니다.");
        });
    }
}
