package test.domain.ingest;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.housing.Address;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.notice.LhUnitSupplyValues;
import test.domain.notice.Notice;
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;
import test.domain.notice.RentTerms;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공급행에 카탈로그 FK 를 채우는 마지막 단계. 예전 세 match 테이블이 하던 판정이 여기 두 칸으로 줄었다.
 *
 * <p>확정된 행은 FK 로, 못 붙은 행은 {@code unmatched_reason} 으로 각각 무엇이 참인지 검증한다.
 */
@DataJpaTest
class NoticeSupplyCatalogLinkerTest {

    private static final String PNU = "4131010500108520000";
    private static final String OTHER_PNU = "4136011100108220000";
    private static final String ADDRESS = "경기도 구리시 체육관로74번길 67";

    @Autowired private NoticeRepository noticeRepository;
    @Autowired private NoticeSupplyRepository supplyRepository;
    @Autowired private HousingComplexRepository complexRepository;
    @Autowired private UnitTypeRepository unitTypeRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private NoticeSupplyCatalogLinker linker;
    private Notice notice;
    private HousingComplex complex;

    @BeforeEach
    void setUp() {
        // 적재 테스트들이 REQUIRES_NEW 로 커밋한 공고·단지가 @DataJpaTest 롤백을 지나 남는다.
        // 그중 공고 20989 는 아래에서 쓰는 것과 같은 pblancId 라, 먼저 비우지 않으면 자연키가 충돌한다.
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.executeWithoutResult(status -> {
            supplyRepository.deleteAll();
            noticeRepository.deleteAll();
            unitTypeRepository.deleteAll();
            complexRepository.deleteAll();
        });
        linker = new NoticeSupplyCatalogLinker(
                noticeRepository, supplyRepository, complexRepository, unitTypeRepository, transactionManager);
        notice = noticeRepository.save(Notice.firstVersion("20989", null,
                new NoticeSnapshot("일반공고", null, "행복주택 모집", "https://apply.lh.or.kr/?panId=1",
                        null, null, null, "LH", "아파트", "행복주택", null)));
        complex = complexRepository.save(new HousingComplex("구리수택 행복주택",
                address(PNU), "31500001", "행복주택", 394, "LH경기북부"));
    }

    private Address address(String pnu) {
        return new Address(ADDRESS, pnu, "41", "경기도", "310", "구리시");
    }

    private UnitType unitType(String typeName, String exclusiveArea) {
        return unitType(typeName, exclusiveArea, "10.0000");
    }

    private UnitType unitType(String typeName, String exclusiveArea, String residentialCommonArea) {
        return unitTypeRepository.save(new UnitType(complex, typeName,
                new BigDecimal(exclusiveArea), new BigDecimal(residentialCommonArea)));
    }

    /** 마이홈 공급행 하나. LH 주택형 정보를 받기 전의 단지 단위 행이다. */
    private NoticeSupply complexRow(String pnu) {
        return NoticeSupply.ofComplex(notice, 0, 1, "구리수택", pnu, ADDRESS, 50, 394,
                new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L), null, null);
    }

    /** LH 15056765 를 받아 주택형까지 쪼갠 행. */
    private NoticeSupply saveUnitTypeRow(String pnu, String exclusiveArea) {
        return saveUnitTypeRow(pnu, exclusiveArea, "36.80");
    }

    private NoticeSupply saveUnitTypeRow(String pnu, String exclusiveArea, String supplyArea) {
        return supplyRepository.save(complexRow(pnu).splitInto(0, new LhUnitSupplyValues(
                "구리수택 행복주택", "26", new BigDecimal(exclusiveArea), new BigDecimal(supplyArea),
                394, 30, "공고문 참조", "공고문 참조")));
    }

    private List<NoticeSupply> linkAndReload() {
        linker.link(notice.getId());
        entityManager.flush();
        entityManager.clear();
        return supplyRepository.findByNoticeIdOrderByDisplayOrder(notice.getId());
    }

    @Test
    @DisplayName("PNU·공급유형으로 단지를, 전용면적으로 주택형을 확정하면 두 FK 가 다 찬다")
    void linksBothComplexAndUnitType() {
        UnitType expected = unitType("26", "26.7000");
        saveUnitTypeRow(PNU, "26.7000");

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getHousingComplex().getId()).isEqualTo(complex.getId());
            assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId());
            assertThat(supply.getUnmatchedReason()).isNull();
        });
    }

    @Test
    @DisplayName("전용면적이 ±0.05㎡ 안이면 소수 표기가 달라도 붙는다")
    void toleratesSmallAreaDifference() {
        UnitType expected = unitType("26", "26.7000");
        saveUnitTypeRow(PNU, "26.7400");

        assertThat(linkAndReload()).singleElement().satisfies(supply ->
                assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId()));
    }

    @Test
    @DisplayName("오차 범위 후보가 둘인데 정확히 같은 면적이 없으면 아무것도 고르지 않고 이유만 남긴다")
    void leavesUnitTypeEmptyWhenAreaIsAmbiguous() {
        unitType("26A", "26.6900");
        unitType("26B", "26.7100");
        saveUnitTypeRow(PNU, "26.7000");

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getHousingComplex().getId()).isEqualTo(complex.getId());
            assertThat(supply.getUnitType()).isNull();
            assertThat(supply.getUnmatchedReason()).contains("후보 2건");
        });
    }

    @Test
    @DisplayName("오차 범위 후보가 둘이어도 면적이 정확히 같은 게 하나면 그걸 고른다")
    void picksTheOnlyExactAreaMatch() {
        // 부산정관 행복주택 — 카탈로그에 이름이 둘 다 `26` 인 26.75㎡와 26.78㎡가 같이 있다.
        UnitType expected = unitType("26", "26.7500");
        unitType("26", "26.7800");
        saveUnitTypeRow(PNU, "26.7500");

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId());
            assertThat(supply.getUnmatchedReason()).isNull();
        });
    }

    @Test
    @DisplayName("전용면적이 같고 주거공용만 다르면 공급면적으로 가른다")
    void picksTheOnlySupplyAreaMatch() {
        // 산남주공2단지 — 26.37㎡ 짜리가 둘인데 주거공용이 12.17 과 13.56 으로 갈린다.
        UnitType expected = unitType("26", "26.3700", "12.1700");
        unitType("26", "26.3700", "13.5600");
        saveUnitTypeRow(PNU, "26.3700", "38.5400");

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId());
            assertThat(supply.getUnmatchedReason()).isNull();
        });
    }

    @Test
    @DisplayName("전용면적도 공급면적도 안 갈라 주면 못 고른다")
    void leavesUnitTypeEmptyWhenExactMatchesTie() {
        unitType("26A", "26.5300");
        unitType("26B", "26.5300");
        saveUnitTypeRow(PNU, "26.5300", "36.5300");

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getUnitType()).isNull();
            assertThat(supply.getUnmatchedReason()).contains("후보 2건");
        });
    }

    @Test
    @DisplayName("주택형을 아직 못 받은 단지 단위 행은 그 사실이 이유로 남는다")
    void explainsComplexLevelRowsWithoutUnitType() {
        unitType("26", "26.7000");
        supplyRepository.save(complexRow(PNU));

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getHousingComplex().getId()).isEqualTo(complex.getId());
            assertThat(supply.getUnitType()).isNull();
            assertThat(supply.getUnmatchedReason()).contains("15056765");
        });
    }

    @Test
    @DisplayName("PNU 로 찾은 단지가 없으면 두 FK 가 다 비고 이유가 남는다")
    void explainsMissingCatalogComplex() {
        supplyRepository.save(complexRow(OTHER_PNU));

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getHousingComplex()).isNull();
            assertThat(supply.getUnitType()).isNull();
            assertThat(supply.getUnmatchedReason()).isEqualTo("PNU·공급유형으로 찾은 카탈로그 단지 없음");
        });
    }

    @Test
    @DisplayName("주소가 안 맞아 마이홈 행에 못 붙은 LH 공급행은 그 사실이 이유로 남는다")
    void explainsLhOnlyRows() {
        supplyRepository.save(NoticeSupply.ofLhOnly(notice, 0, new LhUnitSupplyValues(
                "남양주별내", "36", new BigDecimal("36.3200"), null, 872, 117, null, null)));

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getHousingComplex()).isNull();
            assertThat(supply.getUnmatchedReason()).contains("주소가 안 맞아");
        });
    }

    @Test
    @DisplayName("같은 PNU 라도 공급유형이 다른 단지는 후보가 아니다")
    void doesNotCrossSupplyTypes() {
        complexRepository.save(new HousingComplex("구리수택 국민임대",
                address(PNU), "31500002", "국민임대", 200, "LH경기북부"));
        UnitType expected = unitType("26", "26.7000");
        saveUnitTypeRow(PNU, "26.7000");

        assertThat(linkAndReload()).singleElement().satisfies(supply -> {
            assertThat(supply.getHousingComplex().getId()).isEqualTo(complex.getId());
            assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId());
        });
    }

    @Test
    @DisplayName("카탈로그가 늦게 들어와도 다시 돌리면 뒤늦게 붙는다 — 그러려고 PNU 를 남겼다")
    void relinksAfterCatalogArrivesLater() {
        saveUnitTypeRow(PNU, "26.7000");
        assertThat(linkAndReload()).singleElement()
                .satisfies(supply -> assertThat(supply.getUnitType()).isNull());

        UnitType expected = unitType("26", "26.7000");

        assertThat(linkAndReload()).singleElement()
                .satisfies(supply -> assertThat(supply.getUnitType().getId()).isEqualTo(expected.getId()));
    }
}
