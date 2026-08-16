package test.domain.ingest.lh;

import org.springframework.stereotype.Component;
import test.domain.ingest.SourceValues;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 마이홈이 준 공급유형 이름을 LH 호출에 필요한 공급정보구분코드(SPL_INF_TP_CD)로 옮긴다.
 *
 * <p>enum 이 아니라 표 하나다 — 이 값은 저장되지 않고 요청 파라미터로만 쓰인다.
 *
 * <p>통합공공임대는 아직 어느 코드로 응답하는지 실측하지 못해 뒤로 미룬다({@link Optional#empty()}).
 * 매입임대·전세임대는 건설형이 아니라 이 서비스로 들어올 일이 없어야 하므로 예외로 막는다.
 *
 * <p>장기전세는 표에서 빼기만 하고 예외로는 막지 않는다. 매입·전세와 달리 <b>이 유형을 제외하기 전에
 * 적재된 공고가 DB에 남아 있을 수 있어서</b>다. {@link LhNoticeIngestService#ingest()} 의 루프에는
 * try/catch 가 없으므로 여기서 던지면 그 공고 하나가 아니라 배치 전체가 죽는다. {@link Optional#empty()}
 * 를 주면 통합공공임대와 같은 경로로 {@code UNSUPPORTED_LH_SUPPLEMENT_TYPE} 으로 기록되고 넘어간다.
 */
@Component
public class LhSupplyInfoTypeResolver {

    private static final Map<String, String> CODE_BY_SUPPLY_TYPE_NAME = Map.of(
            "5년임대", "060",
            "10년임대", "060",
            "50년임대", "061",
            "국민임대", "062",
            "영구임대", "062",
            "행복주택", "063");

    private static final Set<String> NOT_CONSTRUCTION_RENTAL = Set.of("매입임대", "전세임대");

    public Optional<String> resolve(String supplyTypeName) {
        String name = SourceValues.trimToNull(supplyTypeName);
        if (name != null && NOT_CONSTRUCTION_RENTAL.contains(name)) {
            throw new IllegalArgumentException("건설임대가 아닌 공급유형입니다: " + name);
        }
        return Optional.ofNullable(name).map(CODE_BY_SUPPLY_TYPE_NAME::get);
    }
}
