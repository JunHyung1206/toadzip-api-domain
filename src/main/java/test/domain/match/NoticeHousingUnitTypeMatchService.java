package test.domain.match;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import test.domain.housing.HousingComplex;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.notice.LhComplexDetail;
import test.domain.notice.LhNoticeSupplement;
import test.domain.notice.LhNoticeSupplementRepository;
import test.domain.notice.LhUnitSupply;
import test.domain.notice.LhUnitSupplyBatch;
import test.domain.notice.LhUnitSupplyBatchRepository;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 15056765 공급행({@link LhUnitSupply})을 카탈로그 주택형({@link UnitType})에 잇는 파생 matcher.
 *
 * <p>단지명을 카탈로그와 직접 대조하지 않는다 — 마이홈과 LH의 명명 체계가 달라 실측 19%만 맞았다.
 * 대신 <b>LH 이름끼리</b> 이어 {@link LhComplexDetail} 을 찾고, 거기서부터는 이미 계산된 두 매칭을 타고 간다.
 *
 * <pre>
 *   LhUnitSupply ─LH단지명─&gt; LhComplexDetail ─주소─&gt; NoticeHousing ─PNU─&gt; HousingComplex ─전용면적─&gt; UnitType
 *                 (이 클래스)   NoticeHousingLhMatch   NoticeHousingCatalogMatch      (이 클래스)
 * </pre>
 */
@Service
public class NoticeHousingUnitTypeMatchService {

    /**
     * 전용면적 비교 허용 오차(㎡). 15056765({@code DDO_AR})와 카탈로그(15110581 {@code suplyPrvuseAr})가
     * 같은 값을 다른 소수 자리수로 줄 수 있어 완전 일치 대신 작은 오차를 둔다.
     */
    private static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.05");

    private final NoticeHousingUnitTypeMatchRepository repository;
    private final NoticeVersionRepository noticeVersionRepository;
    private final LhUnitSupplyBatchRepository unitSupplyBatchRepository;
    private final LhNoticeSupplementRepository supplementRepository;
    private final NoticeHousingLhMatchRepository lhMatchRepository;
    private final NoticeHousingCatalogMatchRepository catalogMatchRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final Clock clock;

    public NoticeHousingUnitTypeMatchService(NoticeHousingUnitTypeMatchRepository repository,
                                             NoticeVersionRepository noticeVersionRepository,
                                             LhUnitSupplyBatchRepository unitSupplyBatchRepository,
                                             LhNoticeSupplementRepository supplementRepository,
                                             NoticeHousingLhMatchRepository lhMatchRepository,
                                             NoticeHousingCatalogMatchRepository catalogMatchRepository,
                                             UnitTypeRepository unitTypeRepository,
                                             Clock clock) {
        this.repository = repository;
        this.noticeVersionRepository = noticeVersionRepository;
        this.unitSupplyBatchRepository = unitSupplyBatchRepository;
        this.supplementRepository = supplementRepository;
        this.lhMatchRepository = lhMatchRepository;
        this.catalogMatchRepository = catalogMatchRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.clock = clock;
    }

    /** 공백만 지운다 — {@link NoticeHousingLhMatchService#normalize} 와 같은 규칙이다. */
    static String normalize(String value) {
        return value == null ? null : value.strip().replace(" ", "");
    }

    /**
     * 같은 {@code (noticeVersion, matcherVersion)} 을 다시 돌리면 그 조합의 기존 결과만 지우고 다시 만든다.
     *
     * @param lhMatcherVersion      주소 구간에 쓸 {@link NoticeHousingLhMatch} 의 matcherVersion
     * @param catalogMatcherVersion PNU 구간에 쓸 {@link NoticeHousingCatalogMatch} 의 matcherVersion
     */
    @Transactional
    public void match(Long noticeVersionId,
                      String lhMatcherVersion,
                      String catalogMatcherVersion,
                      String matcherVersion) {
        repository.deleteByNoticeVersionIdAndMatcherVersion(noticeVersionId, matcherVersion);
        // IDENTITY 채번 때문에 save()는 flush 없이도 즉시 INSERT 되지만, delete()의 물리 DELETE는
        // flush 전까지 미뤄진다. flush 없이 두면 같은 조합을 재실행할 때 옛 행이 남아 유니크 제약을 친다.
        repository.flush();

        NoticeVersion noticeVersion = noticeVersionRepository.findById(noticeVersionId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고버전입니다: " + noticeVersionId));
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<LhUnitSupplyBatch> batch = unitSupplyBatchRepository.findByNoticeVersionId(noticeVersionId);
        if (batch.isEmpty() || !batch.get().isUnitSupplyDatasetPresent()) {
            return;
        }

        List<LhComplexDetail> details = supplementRepository.findByNoticeVersionId(noticeVersionId)
                .map(LhNoticeSupplement::getComplexDetails)
                .orElse(List.of());
        Map<Long, NoticeHousing> housingByDetailId = confirmedHousingByDetail(noticeVersionId, lhMatcherVersion);

        int order = 0;
        for (LhUnitSupply supply : batch.get().getUnitSupplies()) {
            repository.save(evaluate(noticeVersion, supply, details, housingByDetailId,
                    catalogMatcherVersion, matcherVersion, now, order++));
        }
    }

    /**
     * 주소로 확정된 {@code LhComplexDetail → NoticeHousing} 을 미리 모은다.
     * 확정({@code MATCHED_ADDRESS_*})이 아닌 판정은 담지 않는다 — 세대수가 충돌한 연결은 신뢰하지 않는다.
     */
    private Map<Long, NoticeHousing> confirmedHousingByDetail(Long noticeVersionId, String lhMatcherVersion) {
        return lhMatchRepository.findByNoticeVersionIdAndMatcherVersion(noticeVersionId, lhMatcherVersion).stream()
                .filter(match -> match.getStatus() == NoticeHousingLhMatchStatus.MATCHED_ADDRESS_AND_UNIT_COUNT
                        || match.getStatus() == NoticeHousingLhMatchStatus.MATCHED_ADDRESS_ONLY)
                .filter(match -> match.getLhComplexDetail() != null && match.getNoticeHousing() != null)
                .collect(Collectors.toMap(match -> match.getLhComplexDetail().getId(),
                        NoticeHousingLhMatch::getNoticeHousing, (first, second) -> first));
    }

    private NoticeHousingUnitTypeMatch evaluate(NoticeVersion noticeVersion,
                                                LhUnitSupply supply,
                                                List<LhComplexDetail> details,
                                                Map<Long, NoticeHousing> housingByDetailId,
                                                String catalogMatcherVersion,
                                                String matcherVersion,
                                                LocalDateTime now,
                                                int order) {
        String label = normalize(supply.getComplexLabel());

        // ① LH 단지명 — 같은 공고 안에서 정확히 한 번 겹칠 때만 잇는다.
        List<LhComplexDetail> labelled = details.stream()
                .filter(detail -> label != null && label.equals(normalize(detail.getComplexLabel())))
                .toList();
        if (labelled.size() != 1) {
            return row(noticeVersion, supply, null, null, NoticeHousingUnitTypeMatchStatus.NO_CATALOG_PATH,
                    0, matcherVersion, now,
                    labelled.isEmpty() ? "같은 공고의 LH 단지상세에 이 단지명이 없음"
                            : "같은 공고에 같은 단지명의 LH 단지상세 " + labelled.size() + "건",
                    order);
        }

        // ② 주소 — NoticeHousingLhMatch 가 확정해 둔 공급행.
        NoticeHousing housing = housingByDetailId.get(labelled.get(0).getId());
        if (housing == null) {
            return row(noticeVersion, supply, null, null, NoticeHousingUnitTypeMatchStatus.NO_CATALOG_PATH,
                    0, matcherVersion, now, "이 LH 단지상세가 주소로 확정된 공급행이 없음", order);
        }

        // ③ PNU — NoticeHousingCatalogMatch 가 확정해 둔 단지.
        HousingComplex complex = catalogMatchRepository
                .findByNoticeHousingAndMatcherVersion(housing, catalogMatcherVersion)
                .filter(match -> match.getStatus() == NoticeHousingCatalogMatchStatus.MATCHED_PNU)
                .map(NoticeHousingCatalogMatch::getHousingComplex)
                .orElse(null);
        if (complex == null) {
            return row(noticeVersion, supply, housing, null, NoticeHousingUnitTypeMatchStatus.NO_CATALOG_PATH,
                    0, matcherVersion, now, "이 공급행이 PNU로 확정된 카탈로그 단지가 없음", order);
        }

        // ④ 전용면적 — 공급유형별 HousingComplex에 직접 속한 주택형에서 대조한다.
        BigDecimal area = supply.getExclusiveArea();
        List<UnitType> candidates = area == null ? List.of() : unitTypeRepository.findByHousingComplex(complex)
                .stream()
                .filter(unitType -> unitType.getExclusiveArea() != null
                        && unitType.getExclusiveArea().subtract(area).abs().compareTo(AREA_TOLERANCE) <= 0)
                .toList();

        if (area == null) {
            return row(noticeVersion, supply, housing, null, NoticeHousingUnitTypeMatchStatus.UNMATCHED,
                    0, matcherVersion, now, "15056765 공급행에 전용면적이 없음", order);
        }
        if (candidates.isEmpty()) {
            return row(noticeVersion, supply, housing, null, NoticeHousingUnitTypeMatchStatus.UNMATCHED,
                    0, matcherVersion, now,
                    "전용면적 " + area + "㎡ 근처의 카탈로그 주택형 없음", order);
        }
        if (candidates.size() > 1) {
            return row(noticeVersion, supply, housing, null, NoticeHousingUnitTypeMatchStatus.AMBIGUOUS,
                    candidates.size(), matcherVersion, now,
                    "전용면적 " + area + "㎡ 근처 카탈로그 주택형 후보 " + candidates.size() + "건", order);
        }
        return row(noticeVersion, supply, housing, candidates.get(0), NoticeHousingUnitTypeMatchStatus.MATCHED,
                1, matcherVersion, now,
                "전용면적 일치(허용오차 " + AREA_TOLERANCE + "㎡ 이내 유일 후보)", order);
    }

    private NoticeHousingUnitTypeMatch row(NoticeVersion noticeVersion,
                                           LhUnitSupply supply,
                                           NoticeHousing housing,
                                           UnitType unitType,
                                           NoticeHousingUnitTypeMatchStatus status,
                                           int candidateCount,
                                           String matcherVersion,
                                           LocalDateTime now,
                                           String reason,
                                           int order) {
        return new NoticeHousingUnitTypeMatch(noticeVersion, supply, housing, unitType, status,
                supply.getSuppliedUnitCount(), supply.getTotalUnitCount(),
                housing == null ? null : housing.getRentTerms(),
                unitType == null ? null : unitType.getBaseRentTerms(),
                candidateCount, matcherVersion, now,
                supply.getComplexLabel(), supply.getTypeName(), supply.getExclusiveArea(), reason, order);
    }
}
