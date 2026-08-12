package test.domain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import test.domain.housing.SupplyType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConstructionRentalPolicyTest {

    private final ConstructionRentalPolicy policy = new ConstructionRentalPolicy();

    @Test
    @DisplayName("건설형 공공임대 여덟 유형만 허용한다")
    void allowsOnlyConstructionRentalTypes() {
        List<String> allowed = List.of(
                "국민임대", "영구임대", "행복주택", "통합공공임대",
                "장기전세", "5년임대", "10년임대", "50년임대");

        assertThat(allowed).allSatisfy(label -> assertThat(policy.rejectSupplyType(label)).isEmpty());
        assertThat(SupplyType.values()).filteredOn(SupplyType::isConstructionRental).hasSize(8);
    }

    @Test
    @DisplayName("매입·전세임대는 알려진 비지원 유형으로 제외한다")
    void rejectsKnownUnsupportedTypes() {
        assertThat(policy.rejectSupplyType("매입임대"))
                .contains(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
        assertThat(policy.rejectSupplyType("전세임대"))
                .contains(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
    }

    @Test
    @DisplayName("비어 있거나 처음 보는 공급유형은 자동 허용하지 않는다")
    void rejectsUnknownTypes() {
        assertThat(policy.rejectSupplyType(null))
                .contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
        assertThat(policy.rejectSupplyType("  "))
                .contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
        assertThat(policy.rejectSupplyType("청년안심주택"))
                .contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
    }

    @Test
    @DisplayName("아파트가 아니고 준공일도 없으면 건설 흔적이 없다")
    void hasNoConstructionEvidenceWithoutApartmentOrCompletionDate() {
        assertThat(policy.hasConstructionEvidence("다세대주택", "")).isFalse();
        assertThat(policy.hasConstructionEvidence("다세대주택", null)).isFalse();
    }

    @Test
    @DisplayName("아파트거나 준공일이 있으면 건설 흔적이 있다")
    void hasConstructionEvidenceWhenApartmentOrCompletionDatePresent() {
        assertThat(policy.hasConstructionEvidence("아파트", "")).isTrue();
        assertThat(policy.hasConstructionEvidence("다세대주택", "20201230")).isTrue();
    }

    @Test
    @DisplayName("원천 간 연결에 쓸 수 있는 PNU는 19자리 숫자뿐이다")
    void validatesPnu() {
        assertThat(policy.hasValidPnu("4113111600104160001")).isTrue();
        assertThat(policy.hasValidPnu(" 4113111600104160001 ")).isTrue();
        assertThat(policy.hasValidPnu("411311160010416000")).isFalse();
        assertThat(policy.hasValidPnu("411311160010416000A")).isFalse();
        assertThat(policy.hasValidPnu(null)).isFalse();
    }
}
