package test.domain.ingest.myhome;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MyHomeRentalTypeTest {

    @Test
    void exposesOnlyTheApprovedRequestCodesInOfficialOrder() {
        assertThat(MyHomeRentalType.requestCodes())
                .containsExactly("01", "02", "03", "05", "06", "10", "12")
                // 07 장기전세는 건설임대가 아니라 요청 자체를 하지 않는다.
                .doesNotContain("04", "07", "08", "09", "11", "13");
    }

    @Test
    void mapsTrimmedConstructionRentalLabelsOnly() {
        assertThat(MyHomeRentalType.fromResponseLabel("  행복주택  "))
                .contains(MyHomeRentalType.HAPPY_HOUSE);
        assertThat(MyHomeRentalType.fromResponseLabel("행복주택").map(MyHomeRentalType::responseLabel))
                .contains("행복주택");
        assertThat(MyHomeRentalType.fromResponseLabel("매입임대")).isEmpty();
        assertThat(MyHomeRentalType.fromResponseLabel("장기전세")).isEmpty();
        assertThat(MyHomeRentalType.fromResponseLabel("6년임대")).isEmpty();
    }
}
