package test.domain.match;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import test.domain.housing.ComplexRentalProgram;
import test.domain.housing.ComplexRentalProgramRepository;
import test.domain.housing.HousingComplex;
import test.domain.housing.UnitType;
import test.domain.housing.UnitTypeRepository;
import test.domain.notice.LhUnitSupply;
import test.domain.notice.LhUnitSupplyBatch;
import test.domain.notice.LhUnitSupplyBatchRepository;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link NoticeHousing} 을 15056765 공급행({@link LhUnitSupply})을 거쳐 카탈로그 주택형({@link UnitType})과
 * 잇는 파생 matcher. {@link NoticeHousingCatalogMatchService} 가 확정한 단지 위에서만 시도한다.
 */
@Service
public class NoticeHousingUnitTypeMatchService {

    /** 전용면적 비교 허용 오차(㎡). 15056765(DDO_AR)와 카탈로그(15110581, suplyPrvuseAr)는 같은 값을
     * 다른 소수 자리수로 줄 수 있어 완전 일치 대신 작은 오차를 둔다. */
    private static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.05");

    private final NoticeHousingUnitTypeMatchRepository repository;
    private final NoticeVersionRepository noticeVersionRepository;
    private final NoticeHousingRepository housingRepository;
    private final NoticeHousingCatalogMatchRepository catalogMatchRepository;
    private final LhUnitSupplyBatchRepository unitSupplyBatchRepository;
    private final ComplexRentalProgramRepository programRepository;
    private final UnitTypeRepository unitTypeRepository;
    private final Clock clock;

    public NoticeHousingUnitTypeMatchService(NoticeHousingUnitTypeMatchRepository repository,
                                             NoticeVersionRepository noticeVersionRepository,
                                             NoticeHousingRepository housingRepository,
                                             NoticeHousingCatalogMatchRepository catalogMatchRepository,
                                             LhUnitSupplyBatchRepository unitSupplyBatchRepository,
                                             ComplexRentalProgramRepository programRepository,
                                             UnitTypeRepository unitTypeRepository,
                                             Clock clock) {
        this.repository = repository;
        this.noticeVersionRepository = noticeVersionRepository;
        this.housingRepository = housingRepository;
        this.catalogMatchRepository = catalogMatchRepository;
        this.unitSupplyBatchRepository = unitSupplyBatchRepository;
        this.programRepository = programRepository;
        this.unitTypeRepository = unitTypeRepository;
        this.clock = clock;
    }

    /**
     * 같은 {@code (noticeVersion, matcherVersion)} 을 다시 돌리면 그 조합의 기존 결과만 지우고 다시 만든다.
     *
     * @param catalogMatcherVersion 단지 매칭에 쓸 {@link NoticeHousingCatalogMatch#getMatcherVersion()}.
     *                              그 결과가 {@code MATCHED_PNU} 인 공급행 위에서만 주택형 매칭을 시도한다.
     */
    @Transactional
    public void match(Long noticeVersionId, String catalogMatcherVersion, String matcherVersion) {
        repository.deleteByNoticeVersionIdAndMatcherVersion(noticeVersionId, matcherVersion);
        // IDENTITY 채번 때문에 save()는 flush 없이도 즉시 INSERT 되지만, delete()의 물리 DELETE는
        // flush 전까지 미뤄진다. flush 없이 두면 같은 조합을 재실행할 때 옛 행이 남아 유니크 제약을 친다.
        repository.flush();

        NoticeVersion noticeVersion = noticeVersionRepository.findById(noticeVersionId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고버전입니다: " + noticeVersionId));
        List<NoticeHousing> housings = housingRepository.findByNoticeVersionIdOrderByDisplayOrder(noticeVersionId);
        LocalDateTime now = LocalDateTime.now(clock);
        int order = 0;

        Optional<LhUnitSupplyBatch> batch = unitSupplyBatchRepository.findByNoticeVersionId(noticeVersionId);
        if (batch.isEmpty() || !batch.get().isUnitSupplyDatasetPresent()) {
            for (NoticeHousing housing : housings) {
                repository.save(row(noticeVersion, housing, null, null,
                        NoticeHousingUnitTypeMatchStatus.SOURCE_DATA_MISSING, null, 0, matcherVersion, now,
                        null, null, null, "원천이 이번 공고버전에 15056765 데이터셋을 주지 않음", order++));
            }
            return;
        }
        List<LhUnitSupply> unitSupplies = batch.get().getUnitSupplies();

        for (NoticeHousing housing : housings) {
            HousingComplex complex = confirmedComplex(housing, catalogMatcherVersion);
            if (complex == null) {
                repository.save(row(noticeVersion, housing, null, null,
                        NoticeHousingUnitTypeMatchStatus.NO_CATALOG_MATCH, null, 0, matcherVersion, now,
                        null, null, null, "이 공급행이 단지 카탈로그와 아직 확정 매칭되지 않음", order++));
                continue;
            }

            String complexName = normalize(complex.getName());
            List<LhUnitSupply> candidateRows = unitSupplies.stream()
                    .filter(row -> complexName.equals(normalize(row.getComplexLabel())))
                    .toList();
            if (candidateRows.isEmpty()) {
                repository.save(row(noticeVersion, housing, null, null,
                        NoticeHousingUnitTypeMatchStatus.NO_SUPPLY_ROW, null, 0, matcherVersion, now,
                        null, null, null, "단지명 \"" + complex.getName() + "\"의 15056765 공급행 없음", order++));
                continue;
            }

            List<UnitType> catalogUnitTypes = programRepository
                    .findByHousingComplexAndSupplyType(complex, noticeVersion.getSupplyType())
                    .map(unitTypeRepository::findByComplexRentalProgram)
                    .orElse(List.of());

            for (LhUnitSupply supplyRow : candidateRows) {
                repository.save(evaluate(noticeVersion, housing, supplyRow, catalogUnitTypes,
                        matcherVersion, now, order++));
            }
        }
    }

    private HousingComplex confirmedComplex(NoticeHousing housing, String catalogMatcherVersion) {
        return catalogMatchRepository.findByNoticeHousingAndMatcherVersion(housing, catalogMatcherVersion)
                .filter(match -> match.getStatus() == NoticeHousingCatalogMatchStatus.MATCHED_PNU)
                .map(NoticeHousingCatalogMatch::getHousingComplex)
                .orElse(null);
    }

    private NoticeHousingUnitTypeMatch evaluate(NoticeVersion noticeVersion,
                                                NoticeHousing housing,
                                                LhUnitSupply supplyRow,
                                                List<UnitType> catalogUnitTypes,
                                                String matcherVersion,
                                                LocalDateTime now,
                                                int order) {
        BigDecimal area = supplyRow.getExclusiveArea();
        List<UnitType> candidates = area == null
                ? List.of()
                : catalogUnitTypes.stream()
                        .filter(unitType -> unitType.getExclusiveArea() != null
                                && withinTolerance(unitType.getExclusiveArea(), area))
                        .toList();

        NoticeHousingUnitTypeMatchStatus status;
        UnitType matched = null;
        String reason;
        if (area == null) {
            status = NoticeHousingUnitTypeMatchStatus.UNMATCHED;
            reason = "15056765 공급행에 전용면적이 없음";
        } else if (candidates.isEmpty()) {
            status = NoticeHousingUnitTypeMatchStatus.UNMATCHED;
            reason = "전용면적 " + area + "㎡ 근처의 카탈로그 주택형 없음";
        } else if (candidates.size() > 1) {
            status = NoticeHousingUnitTypeMatchStatus.AMBIGUOUS;
            reason = "전용면적 " + area + "㎡ 근처 카탈로그 주택형 후보 " + candidates.size() + "건";
        } else {
            status = NoticeHousingUnitTypeMatchStatus.MATCHED;
            matched = candidates.get(0);
            reason = "전용면적 일치(허용오차 " + AREA_TOLERANCE + "㎡ 이내 유일 후보)";
        }

        return row(noticeVersion, housing, supplyRow, matched, status, supplyRow.getSuppliedUnitCount(),
                candidates.size(), matcherVersion, now,
                supplyRow.getComplexLabel(), supplyRow.getTypeName(), area, reason, order);
    }

    private boolean withinTolerance(BigDecimal catalogArea, BigDecimal sourceArea) {
        return catalogArea.subtract(sourceArea).abs().compareTo(AREA_TOLERANCE) <= 0;
    }

    /** 공백만 지운다 — {@link NoticeHousingLhMatchService#normalize} 와 같은 규칙이다. */
    private static String normalize(String value) {
        return value == null ? null : value.strip().replace(" ", "");
    }

    private NoticeHousingUnitTypeMatch row(NoticeVersion noticeVersion,
                                           NoticeHousing housing,
                                           LhUnitSupply supplyRow,
                                           UnitType unitType,
                                           NoticeHousingUnitTypeMatchStatus status,
                                           Integer suppliedUnitCount,
                                           int candidateCount,
                                           String matcherVersion,
                                           LocalDateTime now,
                                           String sourceComplexLabel,
                                           String sourceTypeName,
                                           BigDecimal sourceExclusiveArea,
                                           String reason,
                                           int order) {
        return new NoticeHousingUnitTypeMatch(noticeVersion, housing, supplyRow, unitType, status,
                suppliedUnitCount, candidateCount, matcherVersion, now,
                sourceComplexLabel, sourceTypeName, sourceExclusiveArea, reason, order);
    }
}
