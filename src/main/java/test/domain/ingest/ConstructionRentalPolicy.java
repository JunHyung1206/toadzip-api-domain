package test.domain.ingest;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 건설형 공공임대만 적재하기 위한 원천 경계.
 *
 * <p>공급유형을 enum 으로 세워 두지 않는다. 이건 <b>저장하는 값이 아니라 적재 시점의 필터</b>라서,
 * 허용 이름 집합 하나면 같은 일을 한다. 원천이 처음 보는 표기를 주면 조용히 통과시키지 않고
 * {@link IngestRejectionReason#UNKNOWN_SUPPLY_TYPE} 으로 남긴다.
 */
@Component
public class ConstructionRentalPolicy {

    /** 이 서비스가 카탈로그와 모집공고로 다루는 건설형 공공임대. */
    private static final Set<String> CONSTRUCTION_RENTAL = Set.of(
            "영구임대", "국민임대", "행복주택", "통합공공임대",
            "장기전세", "5년임대", "10년임대", "50년임대");

    /** 원천에 있는 건 아는데 우리 경계 밖인 공급유형. 단지가 없거나(전세) 대상 주택이 흩어져 있다(매입). */
    private static final Set<String> KNOWN_BUT_EXCLUDED = Set.of("매입임대", "전세임대");

    /** 원천 houseTyNm 이 이 값이면 준공일이 없어도 건설형으로 본다. */
    private static final String APARTMENT = "아파트";

    public Optional<IngestRejectionReason> rejectSupplyType(String sourceLabel) {
        String label = SourceValues.trimToNull(sourceLabel);
        if (label == null || !(CONSTRUCTION_RENTAL.contains(label) || KNOWN_BUT_EXCLUDED.contains(label))) {
            return Optional.of(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
        }
        if (!CONSTRUCTION_RENTAL.contains(label)) {
            return Optional.of(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
        }
        return Optional.empty();
    }

    public boolean hasConstructionEvidence(String houseTypeLabel, String completionDate) {
        return APARTMENT.equals(SourceValues.trimToNull(houseTypeLabel))
                || SourceValues.toDate(completionDate) != null;
    }
}
