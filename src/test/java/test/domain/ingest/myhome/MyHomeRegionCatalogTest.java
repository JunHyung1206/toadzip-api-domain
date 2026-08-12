package test.domain.ingest.myhome;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyHomeRegionCatalogTest {

    private final MyHomeRegionCatalog catalog = new MyHomeRegionCatalog();

    @Test
    void loadsTheFixedOfficialNationwideRegionList() {
        assertThat(catalog.all()).hasSize(256);
        assertThat(catalog.all()).extracting(MyHomeRegion::fullCode).doesNotHaveDuplicates();
        assertThat(catalog.all()).allSatisfy(region -> {
            assertThat(region.brtcCode()).matches("\\d{2}");
            assertThat(region.signguCode()).matches("\\d{3}");
        });
        assertThat(catalog.all()).contains(new MyHomeRegion("11", "350", "서울특별시", "노원구"));
        assertThat(catalog.all()).filteredOn(region -> region.fullCode().equals("11350"))
                .containsExactly(new MyHomeRegion("11", "350", "서울특별시", "노원구"));
    }

    @Test
    void exposesAnImmutableRegionList() {
        assertThatThrownBy(() -> catalog.all().add(new MyHomeRegion("11", "350", "서울특별시", "노원구")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
