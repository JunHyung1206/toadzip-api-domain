package test.domain.ingest.lh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import test.domain.housing.SupplyType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LhSupplyInfoTypeResolverTest {

    private final LhSupplyInfoTypeResolver resolver = new LhSupplyInfoTypeResolver();

    @ParameterizedTest
    @CsvSource({
            "FIVE_YEAR_RENTAL,060",
            "TEN_YEAR_RENTAL,060",
            "FIFTY_YEAR_RENTAL,061",
            "NATIONAL_RENTAL,062",
            "PERMANENT_RENTAL,062",
            "LONG_TERM_JEONSE,062",
            "HAPPY_HOUSE,063"
    })
    void resolvesOfficialSupplyInfoType(SupplyType supplyType, String expected) {
        assertThat(resolver.resolve(supplyType)).contains(expected);
    }

    @Test
    void defersIntegratedPublicRentalSupplement() {
        assertThat(resolver.resolve(SupplyType.INTEGRATED_PUBLIC_RENTAL)).isEmpty();
    }

    @Test
    void rejectsNonConstructionRentalSupplyTypes() {
        assertThatThrownBy(() -> resolver.resolve(SupplyType.PURCHASED_RENTAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(SupplyType.JEONSE_RENTAL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
