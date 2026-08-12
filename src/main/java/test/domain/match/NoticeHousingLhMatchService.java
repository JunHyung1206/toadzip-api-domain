package test.domain.match;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import test.domain.notice.LhComplexDetail;
import test.domain.notice.LhNoticeSupplement;
import test.domain.notice.LhNoticeSupplementRepository;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersion;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link NoticeHousing} 을 그 공고버전에 딸린 LH 단지 상세({@link LhComplexDetail})와 주소·세대수로 잇는
 * 파생 matcher. 카탈로그 PNU 매칭({@link NoticeHousingCatalogMatchService})과 달리 이쪽은 원천에 안정적인
 * 식별자가 없어 주소 접두어로만 후보를 좁힌다.
 */
@Service
public class NoticeHousingLhMatchService {

    private final NoticeHousingLhMatchRepository repository;
    private final NoticeHousingRepository housingRepository;
    private final LhNoticeSupplementRepository supplementRepository;
    private final Clock clock;

    public NoticeHousingLhMatchService(NoticeHousingLhMatchRepository repository,
                                       NoticeHousingRepository housingRepository,
                                       LhNoticeSupplementRepository supplementRepository,
                                       Clock clock) {
        this.repository = repository;
        this.housingRepository = housingRepository;
        this.supplementRepository = supplementRepository;
        this.clock = clock;
    }

    /** 공백만 지운다 — 하이픈 등 다른 구두점은 그대로 남겨 지번·도로명 표기 차이를 지우지 않는다. */
    static String normalize(String value) {
        return value == null ? null : value.strip().replace(" ", "");
    }

    /** LH 상세 주소가 공고 주소로 시작하면 후보다. LH가 공고 주소 뒤에 상세를 더 붙이는 것만 허용한다. */
    private boolean isCandidate(NoticeHousing notice, LhComplexDetail detail) {
        String noticeAddress = normalize(notice.getSuppliedHousing().getFullAddress());
        String lhAddress = normalize(detail.fullLotAddress());
        return noticeAddress != null && lhAddress != null && lhAddress.startsWith(noticeAddress);
    }

    /** 같은 {@code (noticeVersion, matcherVersion)} 을 다시 돌리면 그 조합의 기존 결과만 지우고 다시 만든다. */
    @Transactional
    public void match(Long noticeVersionId, String matcherVersion) {
        repository.deleteByNoticeVersionIdAndMatcherVersion(noticeVersionId, matcherVersion);
        // IDENTITY 채번 때문에 아래 save()는 flush 없이도 즉시 INSERT 되지만, 위 delete()의 물리 DELETE는
        // flush 전까지 미뤄진다. flush 없이 두면 같은 조합을 재실행할 때 옛 행이 남아 유니크 제약을 친다.
        repository.flush();

        Optional<LhNoticeSupplement> supplementOpt = supplementRepository.findByNoticeVersionId(noticeVersionId);
        if (supplementOpt.isEmpty()) {
            return;
        }
        LhNoticeSupplement supplement = supplementOpt.get();
        NoticeVersion noticeVersion = supplement.getNoticeVersion();
        List<NoticeHousing> noticeRows = housingRepository.findByNoticeVersionIdOrderByDisplayOrder(noticeVersionId);
        LocalDateTime now = LocalDateTime.now(clock);
        int order = 0;

        if (!supplement.isComplexDetailDatasetPresent()) {
            for (NoticeHousing notice : noticeRows) {
                repository.save(sourceDetailMissingRow(noticeVersion, notice, matcherVersion, now, order++));
            }
            return;
        }

        List<LhComplexDetail> details = supplement.getComplexDetails();

        Map<NoticeHousing, List<LhComplexDetail>> candidatesByNotice = new LinkedHashMap<>();
        Map<LhComplexDetail, List<NoticeHousing>> noticesByDetail = new LinkedHashMap<>();
        for (LhComplexDetail detail : details) {
            noticesByDetail.put(detail, new ArrayList<>());
        }
        for (NoticeHousing notice : noticeRows) {
            List<LhComplexDetail> candidates = new ArrayList<>();
            for (LhComplexDetail detail : details) {
                if (isCandidate(notice, detail)) {
                    candidates.add(detail);
                    noticesByDetail.get(detail).add(notice);
                }
            }
            candidatesByNotice.put(notice, candidates);
        }

        Set<LhComplexDetail> consumed = new HashSet<>();
        for (NoticeHousing notice : noticeRows) {
            NoticeHousingLhMatch row = evaluateNoticeRow(noticeVersion, notice,
                    candidatesByNotice.get(notice), noticesByDetail, matcherVersion, now, order++, consumed);
            repository.save(row);
        }

        for (LhComplexDetail detail : details) {
            if (!consumed.contains(detail)) {
                int reverseDegree = noticesByDetail.get(detail).size();
                repository.save(leftoverDetailRow(noticeVersion, detail, reverseDegree, matcherVersion, now, order++));
            }
        }
    }

    private NoticeHousingLhMatch evaluateNoticeRow(NoticeVersion noticeVersion,
                                                    NoticeHousing notice,
                                                    List<LhComplexDetail> candidates,
                                                    Map<LhComplexDetail, List<NoticeHousing>> noticesByDetail,
                                                    String matcherVersion,
                                                    LocalDateTime now,
                                                    int order,
                                                    Set<LhComplexDetail> consumed) {
        String noticeAddressRaw = notice.getSuppliedHousing().getFullAddress();
        String noticeAddressNormalized = normalize(noticeAddressRaw);
        Integer noticeUnitCount = notice.getSuppliedHousing().getTotalUnitCount();

        if (candidates.isEmpty()) {
            return new NoticeHousingLhMatch(noticeVersion, notice, null, NoticeHousingLhMatchStatus.UNMATCHED,
                    0, 0, matcherVersion, now,
                    new NoticeHousingLhMatchEvidence(noticeAddressRaw, noticeAddressNormalized, null, null,
                            noticeUnitCount, null, null, "주소로 매칭되는 LH 상세 후보 없음"),
                    order);
        }
        if (candidates.size() >= 2) {
            return new NoticeHousingLhMatch(noticeVersion, notice, null, NoticeHousingLhMatchStatus.AMBIGUOUS,
                    0, candidates.size(), matcherVersion, now,
                    new NoticeHousingLhMatchEvidence(noticeAddressRaw, noticeAddressNormalized, null, null,
                            noticeUnitCount, null, candidateIds(candidates),
                            "공고 행 하나에 LH 상세 후보 " + candidates.size() + "건"),
                    order);
        }

        LhComplexDetail sole = candidates.get(0);
        int reverseDegree = noticesByDetail.get(sole).size();
        String lhAddressRaw = sole.fullLotAddress();
        String lhAddressNormalized = normalize(lhAddressRaw);
        Integer lhUnitCount = sole.getTotalUnitCount();

        if (reverseDegree >= 2) {
            return new NoticeHousingLhMatch(noticeVersion, notice, null, NoticeHousingLhMatchStatus.AMBIGUOUS,
                    reverseDegree, 1, matcherVersion, now,
                    new NoticeHousingLhMatchEvidence(noticeAddressRaw, noticeAddressNormalized,
                            lhAddressRaw, lhAddressNormalized, noticeUnitCount, lhUnitCount,
                            candidateIds(candidates), "같은 LH 상세를 후보로 삼은 공고 행 " + reverseDegree + "건"),
                    order);
        }

        consumed.add(sole);
        NoticeHousingLhMatchStatus status;
        String reason;
        if (noticeUnitCount != null && lhUnitCount != null && noticeUnitCount.equals(lhUnitCount)) {
            status = NoticeHousingLhMatchStatus.MATCHED_ADDRESS_AND_UNIT_COUNT;
            reason = "주소·세대수 모두 일치";
        } else if (noticeUnitCount == null || lhUnitCount == null) {
            status = NoticeHousingLhMatchStatus.MATCHED_ADDRESS_ONLY;
            reason = "주소만 일치, 세대수는 한쪽이 없음";
        } else {
            status = NoticeHousingLhMatchStatus.CONFLICT_UNIT_COUNT;
            reason = "주소는 일치하나 세대수 불일치";
        }
        return new NoticeHousingLhMatch(noticeVersion, notice, sole, status, 1, 1, matcherVersion, now,
                new NoticeHousingLhMatchEvidence(noticeAddressRaw, noticeAddressNormalized,
                        lhAddressRaw, lhAddressNormalized, noticeUnitCount, lhUnitCount,
                        candidateIds(candidates), reason),
                order);
    }

    private NoticeHousingLhMatch leftoverDetailRow(NoticeVersion noticeVersion,
                                                    LhComplexDetail detail,
                                                    int reverseDegree,
                                                    String matcherVersion,
                                                    LocalDateTime now,
                                                    int order) {
        String lhAddressRaw = detail.fullLotAddress();
        return new NoticeHousingLhMatch(noticeVersion, null, detail, NoticeHousingLhMatchStatus.UNMATCHED,
                reverseDegree, 0, matcherVersion, now,
                new NoticeHousingLhMatchEvidence(null, null, lhAddressRaw, normalize(lhAddressRaw),
                        null, detail.getTotalUnitCount(), String.valueOf(detail.getId()),
                        "어떤 공고 행의 단독 후보도 아니었던 LH 상세"),
                order);
    }

    private NoticeHousingLhMatch sourceDetailMissingRow(NoticeVersion noticeVersion,
                                                        NoticeHousing notice,
                                                        String matcherVersion,
                                                        LocalDateTime now,
                                                        int order) {
        String noticeAddressRaw = notice.getSuppliedHousing().getFullAddress();
        return new NoticeHousingLhMatch(noticeVersion, notice, null, NoticeHousingLhMatchStatus.SOURCE_DETAIL_MISSING,
                0, 0, matcherVersion, now,
                new NoticeHousingLhMatchEvidence(noticeAddressRaw, normalize(noticeAddressRaw), null, null,
                        notice.getSuppliedHousing().getTotalUnitCount(), null, null,
                        "원천이 이번 공고버전에 LH 상세 데이터셋을 주지 않음"),
                order);
    }

    private String candidateIds(List<LhComplexDetail> candidates) {
        return candidates.stream()
                .map(LhComplexDetail::getId)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
