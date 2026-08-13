package test.domain.match;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import test.domain.housing.HousingComplex;
import test.domain.housing.HousingComplexRepository;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link NoticeHousing} 을 단지 카탈로그({@link HousingComplex})와 PNU로 잇는 파생 matcher.
 * 이름 fallback 은 두지 않는다 — 매입임대는 단지명 칸에 지역명이 오기 때문이다.
 */
@Service
public class NoticeHousingCatalogMatchService {

    private final NoticeHousingCatalogMatchRepository repository;
    private final NoticeHousingRepository housingRepository;
    private final NoticeVersionRepository noticeVersionRepository;
    private final HousingComplexRepository complexRepository;
    private final Clock clock;

    public NoticeHousingCatalogMatchService(NoticeHousingCatalogMatchRepository repository,
                                            NoticeHousingRepository housingRepository,
                                            NoticeVersionRepository noticeVersionRepository,
                                            HousingComplexRepository complexRepository,
                                            Clock clock) {
        this.repository = repository;
        this.housingRepository = housingRepository;
        this.noticeVersionRepository = noticeVersionRepository;
        this.complexRepository = complexRepository;
        this.clock = clock;
    }

    /** 같은 {@code matcherVersion} 을 다시 돌리면 그 버전의 기존 결과만 지우고 다시 만든다. */
    @Transactional
    public void match(Long noticeVersionId, String matcherVersion) {
        repository.deleteByNoticeHousingNoticeVersionIdAndMatcherVersion(noticeVersionId, matcherVersion);
        // 삭제된 행의 물리 DELETE는 flush 전까지 미뤄지는데, 아래 save()는 IDENTITY 채번 때문에 즉시 INSERT를
        // 날린다. flush 없이 두면 같은 matcherVersion을 재실행할 때 옛 행이 아직 남아 있어 유니크 제약을 친다.
        repository.flush();
        NoticeVersion noticeVersion = noticeVersionRepository.findById(noticeVersionId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공고버전입니다: " + noticeVersionId));
        for (NoticeHousing housing : housingRepository.findByNoticeVersionIdOrderByDisplayOrder(noticeVersionId)) {
            String pnu = housing.getSuppliedHousing().getPnu();
            List<HousingComplex> candidates = pnu == null || noticeVersion.getSupplyType() == null
                    ? List.of()
                    : complexRepository.findAllByAddressPnuAndSupplyType(pnu, noticeVersion.getSupplyType());
            NoticeHousingCatalogMatchStatus status = switch (candidates.size()) {
                case 0 -> NoticeHousingCatalogMatchStatus.UNMATCHED;
                case 1 -> NoticeHousingCatalogMatchStatus.MATCHED_PNU;
                default -> NoticeHousingCatalogMatchStatus.AMBIGUOUS;
            };
            HousingComplex matched = candidates.size() == 1 ? candidates.getFirst() : null;
            repository.save(new NoticeHousingCatalogMatch(
                    housing, matched, status, pnu, candidates.size(), matcherVersion, LocalDateTime.now(clock)));
        }
    }
}
