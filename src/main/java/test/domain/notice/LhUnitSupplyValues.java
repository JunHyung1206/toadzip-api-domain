package test.domain.notice;

import java.math.BigDecimal;

/**
 * LH 15056765 {@code dsList01} 한 행에서 뽑아낸 주택형 공급 값.
 *
 * <p>예전에는 이 값들이 {@code lh_unit_supply} 테이블 한 행이었다. 지금은 저장 대상이 아니라
 * {@link NoticeSupply} 를 만들 때 넘기는 인자 묶음이다 — 마이홈 공급행에 붙느냐 못 붙느냐에 따라
 * 같은 값이 두 가지 경로로 들어가서, 인자 아홉 개를 두 번 나열하지 않으려고 묶었다.
 *
 * @param depositText    원천 LS_GMY 원문. 숫자로 파싱하지 않는다
 * @param monthlyRentText 원천 RFE 원문. 숫자로 파싱하지 않는다
 */
public record LhUnitSupplyValues(
        String complexLabel,
        String typeName,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        Integer totalUnitCount,
        Integer suppliedUnitCount,
        String depositText,
        String monthlyRentText
) {

    /** 원천이 값 대신 컬럼 이름을 담은 행을 섞어 주기도 해서, 전부 빈 행은 버린다. */
    public boolean isEmpty() {
        return complexLabel == null && typeName == null && exclusiveArea == null && supplyArea == null
                && totalUnitCount == null && suppliedUnitCount == null
                && depositText == null && monthlyRentText == null;
    }
}
