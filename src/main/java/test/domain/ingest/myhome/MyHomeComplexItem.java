package test.domain.ingest.myhome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * 15110581 HWSPR04/rentalHouseGwList 의 한 행 = 단지 × 공급유형 × 주택형.
 * 같은 hsmpSn 이 여러 행에 걸쳐 나오고, 행마다 suplyTyNm·styleNm·면적이 다르다.
 *
 * <p>원천이 주는 필드를 하나도 버리지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MyHomeComplexItem(
        Long hsmpSn,
        String insttNm,
        String brtcCode,
        String brtcNm,
        String signguCode,
        String signguNm,
        String hsmpNm,
        String rnAdres,
        String pnu,
        String competDe,
        Integer hshldCo,
        String suplyTyNm,
        String styleNm,
        BigDecimal suplyPrvuseAr,
        BigDecimal suplyCmnuseAr,
        String houseTyNm,
        String heatMthdDetailNm,
        String buldStleNm,
        String elvtrInstlAtNm,
        Integer parkngCo,
        Long bassRentGtn,
        Long bassMtRntchrg,
        Long bassCnvrsGtnLmt
) {
}
