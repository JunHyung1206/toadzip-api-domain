package test.domain.ingest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.notice.Notice;
import test.domain.notice.NoticeRepository;
import test.domain.notice.NoticeSupply;
import test.domain.notice.NoticeSupplyRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 공급행에 카탈로그 단지·주택형 FK 를 채운다. 예전에 세 개였던 match 테이블이 하던 일을
 * <b>결과 두 칸</b>({@code housing_complex_id}, {@code unit_type_id})으로 줄인 자리다.
 *
 * <p><b>왜 적재와 분리돼 있나.</b> 공고와 단지 카탈로그는 서로 다른 원천에서 다른 시점에 들어온다.
 * 공고를 먼저 받으면 붙일 단지가 아직 없고, 카탈로그를 다시 받으면 이미 붙인 결과가 낡는다.
 * 그래서 두 적재가 끝난 뒤에 한 번 도는 단계로 뒀다.
 *
 * <p><b>다시 돌려도 된다.</b> 공급행에 {@code supplied_pnu} 를 남겨 둔 이유가 이것이다. 규칙을 고치면
 * 원천을 다시 부르지 않고 이 서비스만 다시 돌려 FK 를 새로 채운다. 주소로 LH 행과 마이홈 행을 잇는
 * 판단은 여기 없다 — 그건 행을 <b>만들 때</b> 결정되므로 사후 재계산이 되지 않는다.
 */
@Slf4j
@Service
public class NoticeSupplyCatalogLinker {

    /**
     * 전용면적 비교 허용 오차(㎡). 15056765({@code DDO_AR})와 카탈로그(15110581 {@code suplyPrvuseAr})가
     * 같은 값을 다른 소수 자리수로 줄 수 있어 완전 일치 대신 작은 오차를 둔다.
     */
    private static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.05");

    private final NoticeRepository noticeRepository;
    private final NoticeSupplyRepository supplyRepository;
    private final HousingComplexRepository complexRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final TransactionTemplate transactionTemplate;

    public NoticeSupplyCatalogLinker(NoticeRepository noticeRepository,
                                     NoticeSupplyRepository supplyRepository,
                                     HousingComplexRepository complexRepository,
                                     UnitTypeRepository unitTypeRepository,
                                     PlatformTransactionManager transactionManager) {
        this.noticeRepository = noticeRepository;
        this.supplyRepository = supplyRepository;
        this.complexRepository = complexRepository;
        this.unitTypeRepository = unitTypeRepository;
        // 기본 전파다. 혼자 돌면 공고마다 트랜잭션이 하나씩 열려 한 공고의 실패가 다른 공고를 되돌리지 않고,
        // 이미 트랜잭션 안이면(테스트 등) 그 트랜잭션에 그대로 참여한다.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 모든 공고를 다시 연결한다. 규칙을 고쳤거나 카탈로그를 다시 받았을 때 돌린다. */
    public IngestReport linkAll() {
        IngestReport report = IngestReport.empty();
        for (Long noticeId : noticeRepository.findAll().stream().map(Notice::getId).toList()) {
            try {
                report = report.plus(link(noticeId));
            } catch (RuntimeException e) {
                log.warn("공급행 카탈로그 연결 실패: noticeId={}", noticeId, e);
                report = report.plus(IngestReport.oneFailed());
            }
        }
        return report;
    }

    /** 공고 하나의 공급행을 다시 연결한다. 이미 채워진 FK 도 덮어쓴다. */
    public IngestReport link(Long noticeId) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> linkInTransaction(noticeId)));
    }

    private IngestReport linkInTransaction(Long noticeId) {
        Notice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고입니다: " + noticeId));
        int matched = 0;
        boolean anyChanged = false;
        for (NoticeSupply supply : supplyRepository.findByNoticeIdOrderByDisplayOrder(noticeId)) {
            Long beforeComplexId = idOf(supply.getHousingComplex());
            Long beforeUnitTypeId = idOf(supply.getUnitType());
            resolve(notice, supply);
            anyChanged |= !Objects.equals(beforeComplexId, idOf(supply.getHousingComplex()))
                    || !Objects.equals(beforeUnitTypeId, idOf(supply.getUnitType()));
            if (supply.getUnitType() != null) {
                matched++;
            }
            supplyRepository.save(supply);
        }
        log.debug("공고 {} 공급행 연결: 주택형 확정 {}건", notice.getSourceNoticeId(), matched);
        return anyChanged ? IngestReport.oneVersioned() : IngestReport.oneUnchanged();
    }

    private Long idOf(HousingComplex complex) {
        return complex == null ? null : complex.getId();
    }

    private Long idOf(UnitType unitType) {
        return unitType == null ? null : unitType.getId();
    }

    private void resolve(Notice notice, NoticeSupply supply) {
        HousingComplex complex = resolveComplex(notice, supply);
        if (complex == null) {
            return;
        }
        BigDecimal area = supply.getExclusiveArea();
        if (area == null) {
            supply.linkCatalog(complex, null, notice.getLhFetchedAt() == null
                    ? "LH 주택형별 공급정보(15056765)를 아직 받지 않은 공고"
                    : "LH 응답에 이 단지의 주택형 행이 없음");
            return;
        }
        List<UnitType> candidates = unitTypeRepository.findByHousingComplex(complex).stream()
                .filter(unitType -> unitType.getExclusiveArea() != null
                        && unitType.getExclusiveArea().subtract(area).abs().compareTo(AREA_TOLERANCE) <= 0)
                .toList();
        if (candidates.isEmpty()) {
            supply.linkCatalog(complex, null, "전용면적 %s㎡ 근처의 카탈로그 주택형 없음".formatted(area));
            return;
        }
        if (candidates.size() == 1) {
            supply.linkCatalog(complex, candidates.getFirst(), null);
            return;
        }
        UnitType exact = onlyExactAreaMatch(candidates, area);
        if (exact != null) {
            supply.linkCatalog(complex, exact, null);
            return;
        }
        UnitType bySupplyArea = onlySupplyAreaMatch(candidates, supply.getSupplyArea());
        if (bySupplyArea == null) {
            supply.linkCatalog(complex, null,
                    "전용면적 %s㎡ 근처 카탈로그 주택형 후보 %d건".formatted(area, candidates.size()));
            return;
        }
        supply.linkCatalog(complex, bySupplyArea, null);
    }

    /**
     * 오차 범위 후보가 여럿일 때 <b>면적이 정확히 같은</b> 주택형이 하나뿐이면 그걸 고른다.
     *
     * <p>부산정관 행복주택은 카탈로그에 26.75㎡와 26.78㎡가 둘 다 {@code 26} 이라는 이름으로 있어,
     * 26.75㎡ 공급행이 ±0.05 안에 둘 다 걸린다. 이름으로는 못 가른다 — LH 는 {@code 26형(고령자)},
     * 마이홈은 {@code 26} 이라 표기 체계가 다르다(실측 29건 중 이름으로 풀리는 건 0건).
     * 면적 완전일치로는 21건이 풀린다.
     *
     * @return 완전일치가 정확히 하나일 때 그 주택형, 0건이거나 2건 이상이면 {@code null}
     */
    private UnitType onlyExactAreaMatch(List<UnitType> candidates, BigDecimal area) {
        List<UnitType> exact = candidates.stream()
                .filter(unitType -> unitType.getExclusiveArea().compareTo(area) == 0)
                .toList();
        return exact.size() == 1 ? exact.getFirst() : null;
    }

    /**
     * 전용면적 완전일치도 갈라 주지 못할 때 <b>공급면적</b>으로 가른다. 카탈로그에 전용면적이 같고
     * 주거공용만 다른 주택형이 여럿 있는 단지가 있어서다.
     *
     * <pre>
     *   산남주공2단지  26.37㎡ + 12.17㎡ = 38.54   ← 공고 SPL_AR 38.54
     *   산남주공2단지  26.37㎡ + 13.56㎡ = 39.93
     * </pre>
     *
     * 두 행 모두 이름이 {@code 26} 이고 전용면적이 26.3700 으로 같아 앞의 두 규칙으로는 안 갈린다.
     * 15056765 는 {@code DDO_AR}(전용) 옆에 {@code SPL_AR}(공급)을 같이 주므로 그걸 쓴다.
     * 실측 8건이 전부 이 규칙으로 유일하게 확정됐다.
     *
     * @param supplyArea 공고 공급행의 공급면적. 없으면 이 규칙을 쓰지 않는다
     * @return 공급면적이 일치하는 주택형이 정확히 하나일 때 그 주택형, 아니면 {@code null}
     */
    private UnitType onlySupplyAreaMatch(List<UnitType> candidates, BigDecimal supplyArea) {
        if (supplyArea == null) {
            return null;
        }
        List<UnitType> matched = candidates.stream()
                .filter(unitType -> unitType.getResidentialCommonArea() != null)
                .filter(unitType -> unitType.getExclusiveArea()
                        .add(unitType.getResidentialCommonArea())
                        .subtract(supplyArea).abs()
                        .compareTo(AREA_TOLERANCE) <= 0)
                .toList();
        return matched.size() == 1 ? matched.getFirst() : null;
    }

    /**
     * PNU 로 카탈로그 단지를 찾는다. 이름 fallback 은 두지 않는다 — 매입임대는 단지명 칸에 지역명이
     * 오고, LH 이름과 마이홈 이름은 명명 체계가 아예 다르다(실측 일치율 19%).
     *
     * @return 유일하게 확정된 단지. 못 찾으면 {@code null} 이고 이유는 공급행에 기록된다
     */
    private HousingComplex resolveComplex(Notice notice, NoticeSupply supply) {
        String pnu = supply.getSuppliedPnu();
        String supplyTypeName = notice.getSupplyTypeName();
        if (pnu == null || supplyTypeName == null) {
            supply.linkCatalog(null, null, supply.getHouseSn() == null
                    ? "주소가 안 맞아 공고 공급행에 못 붙인 LH 공급행"
                    : "공고 공급행에 PNU가 없음");
            return null;
        }
        List<HousingComplex> candidates =
                complexRepository.findAllByAddressPnuAndSupplyTypeName(pnu, supplyTypeName);
        if (candidates.isEmpty()) {
            supply.linkCatalog(null, null, "PNU·공급유형으로 찾은 카탈로그 단지 없음");
            return null;
        }
        if (candidates.size() > 1) {
            supply.linkCatalog(null, null,
                    "PNU·공급유형 카탈로그 단지 후보 %d건".formatted(candidates.size()));
            return null;
        }
        return candidates.getFirst();
    }
}
