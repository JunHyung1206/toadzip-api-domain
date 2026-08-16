package test.domain.ingest.lh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LhSupplyInfoTypeResolverTest {

    private final LhSupplyInfoTypeResolver resolver = new LhSupplyInfoTypeResolver();

    @ParameterizedTest
    @CsvSource({
            "5년임대,060",
            "10년임대,060",
            "50년임대,061",
            "국민임대,062",
            "영구임대,062",
            "행복주택,063"
    })
    void resolvesOfficialSupplyInfoType(String supplyTypeName, String expected) {
        assertThat(resolver.resolve(supplyTypeName)).contains(expected);
    }

    @Test
    void trimsSourceLabelBeforeLookup() {
        assertThat(resolver.resolve("  행복주택  ")).contains("063");
    }

    @Test
    void defersIntegratedPublicRentalSupplement() {
        assertThat(resolver.resolve("통합공공임대")).isEmpty();
    }

    @Test
    void hasNoCodeForUnknownOrMissingSupplyType() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("청년안심주택")).isEmpty();
    }

    @Test
    void rejectsNonConstructionRentalSupplyTypes() {
        assertThatThrownBy(() -> resolver.resolve("매입임대"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve("전세임대"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skipsLongTermJeonseWithoutThrowing() {
        // 제외하기 전에 적재된 공고가 DB 에 남아 있을 수 있다. 던지면 LH 적재 배치 전체가 죽는다.
        assertThat(resolver.resolve("장기전세")).isEmpty();
    }
}
