package test.domain.match;

import test.domain.notice.LhNoticeSupplement;
import test.domain.notice.LhNoticeSupplementRepository;
import test.domain.notice.NoticeChangeStatus;
import test.domain.notice.NoticeHousing;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeSnapshot;
import test.domain.notice.NoticeVersion;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNotice;
import test.domain.notice.RecruitmentNoticeRepository;
import test.domain.notice.RentTerms;
import test.domain.notice.SuppliedHousing;
import test.domain.source.SourceSystem;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@link NoticeHousingLhMatchService} 테스트용 fixture. 파주운정·부천옥길처럼 실제로 있는 단지명을 써서,
 * 후보명 "A", "B" 로는 드러나지 않는 접두어·순서 문제를 잡으려 한다.
 *
 * <p>단지명 여섯 개(산내마을1단지 등)만 설계 문서에 실제로 등장한 값이다. 도로명·번지·세대수는
 * 원천 공개 데이터가 아니라 접두어·모호성 시나리오를 재현하기 위해 임의로 구성한 값이다.
 */
class MatchingFixtures {

    private final RecruitmentNoticeRepository recruitmentNoticeRepository;
    private final NoticeVersionRepository noticeVersionRepository;
    private final NoticeHousingRepository housingRepository;
    private final LhNoticeSupplementRepository supplementRepository;

    MatchingFixtures(RecruitmentNoticeRepository recruitmentNoticeRepository,
                     NoticeVersionRepository noticeVersionRepository,
                     NoticeHousingRepository housingRepository,
                     LhNoticeSupplementRepository supplementRepository) {
        this.recruitmentNoticeRepository = recruitmentNoticeRepository;
        this.noticeVersionRepository = noticeVersionRepository;
        this.housingRepository = housingRepository;
        this.supplementRepository = supplementRepository;
    }

    record PajuData(Long noticeVersionId) {
    }

    /**
     * 파주운정 행복주택 예비입주자모집 — 공급행 6개와 LH 상세 6개가 같은 공고버전에 물린다. LH 응답은
     * 마지막 두 단지(초롱꽃마을10단지 · 노을빛마을16단지)만 순서가 뒤집혀 온다 — 주소 매칭이 응답 순서에
     * 기대지 않는지 보려는 것이다. 세대수도 양쪽이 같아 전부 MATCHED_ADDRESS_AND_UNIT_COUNT 로 떨어진다.
     */
    PajuData savePajuWithLastTwoLhRowsReversed() {
        NoticeVersion version = saveNoticeVersion("21500", "파주운정3 행복주택 예비입주자모집");

        String[] complexNames = {
                "산내마을1단지", "가람마을14단지", "초롱꽃마을3단지",
                "물향기마을7단지", "초롱꽃마을10단지", "노을빛마을16단지"
        };
        String[] addresses = {
                "경기도 파주시 산내로 1", "경기도 파주시 가람로 14", "경기도 파주시 청석로 3",
                "경기도 파주시 물향기로 7", "경기도 파주시 청암로 10", "경기도 파주시 노을빛로 16"
        };
        int[] unitCounts = {620, 845, 512, 734, 968, 1103};

        for (int i = 0; i < complexNames.length; i++) {
            housingRepository.save(new NoticeHousing(version, i, i + 1, 50,
                    suppliedHousing(complexNames[i], addresses[i], unitCounts[i]), rentTerms(), null, null));
        }

        LhNoticeSupplement supplement = newSupplement(version, "2015122300021500", true, null);
        // LH 응답 순서: 앞 넷은 공고와 같고, 마지막 둘(초롱꽃마을10단지 · 노을빛마을16단지)만 뒤집혔다.
        int[] lhOrder = {0, 1, 2, 3, 5, 4};
        for (int order = 0; order < lhOrder.length; order++) {
            int i = lhOrder[order];
            supplement.addComplexDetail(order, complexNames[i], addresses[i], null,
                    unitCounts[i], "지역난방", "39.98~59.92", null, null);
        }
        supplementRepository.saveAndFlush(supplement);

        return new PajuData(version.getId());
    }

    /**
     * 부천옥길 A5 · A6 블록 — 공고 한 행의 주소가 LH 상세 두 행 모두의 접두어라, 개수·순서로 강제로
     * 하나를 고르면 안 된다(AMBIGUOUS 로 남아야 한다).
     */
    Long saveBucheonOneToTwoCase() {
        NoticeVersion version = saveNoticeVersion("21600", "부천옥길 행복주택 예비입주자모집");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("부천옥길", "경기도 부천시 옥길로 10", 500), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300021600", true, null);
        supplement.addComplexDetail(0, "부천옥길 A5블록", "경기도 부천시 옥길로 10", "A5",
                480, "지역난방", "39.96~59.87", null, null);
        supplement.addComplexDetail(1, "부천옥길 A6블록", "경기도 부천시 옥길로 10", "A6",
                420, "지역난방", "39.96~59.87", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** 공고·LH 세대수가 같은 1:1 쌍. MATCHED_ADDRESS_AND_UNIT_COUNT 기대. */
    Long saveEqualUnitCountCase() {
        NoticeVersion version = saveNoticeVersion("30001", "세대수 일치 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("동일세대", "경기도 수원시 권선구 세류로 1", 300), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030001", true, null);
        supplement.addComplexDetail(0, "동일세대", "경기도 수원시 권선구 세류로 1", null,
                300, "개별난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** 공고 쪽 세대수가 없는 1:1 쌍. MATCHED_ADDRESS_ONLY 기대. */
    Long saveOneSideNullUnitCountCase() {
        NoticeVersion version = saveNoticeVersion("30002", "세대수 한쪽 null 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("세대수누락", "경기도 안산시 단원구 별망로 1", null), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030002", true, null);
        supplement.addComplexDetail(0, "세대수누락", "경기도 안산시 단원구 별망로 1", null,
                250, "개별난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** 양쪽 세대수가 다 있는데 다른 1:1 쌍. CONFLICT_UNIT_COUNT 기대. */
    Long saveConflictingUnitCountCase() {
        NoticeVersion version = saveNoticeVersion("30003", "세대수 충돌 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("세대수충돌", "경기도 화성시 동탄대로 1", 400), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030003", true, null);
        supplement.addComplexDetail(0, "세대수충돌", "경기도 화성시 동탄대로 1", null,
                420, "개별난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** 공고 행 주소가 어떤 LH 상세와도 안 겹치는 경우. 공고 행은 UNMATCHED, LH 상세도 잔여 UNMATCHED 로 남는다. */
    Long saveZeroCandidateCase() {
        NoticeVersion version = saveNoticeVersion("30004", "후보 0개 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("후보없음", "경기도 평택시 통복시장로 1", 300), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030004", true, null);
        supplement.addComplexDetail(0, "다른단지", "경기도 오산시 오산로 9", null,
                300, "개별난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** 공고 행 둘이 같은 LH 상세 하나를 가리키는 경우. 둘 다 AMBIGUOUS, 그 LH 상세도 소비되지 않고 남는다. */
    Long saveReverseTwoCandidatesCase() {
        NoticeVersion version = saveNoticeVersion("30005", "역방향 후보 2개 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("공유단지A", "경기도 시흥시 정왕대로 5", 300), rentTerms(), null, null));
        housingRepository.save(new NoticeHousing(version, 1, 2, 50,
                suppliedHousing("공유단지B", "경기도 시흥시 정왕대로 5", 300), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030005", true, null);
        supplement.addComplexDetail(0, "정왕대로5", "경기도 시흥시 정왕대로 5", null,
                300, "개별난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** {@code dsSbd} 키 자체가 없던 경우(complexDetailDatasetPresent=false). SOURCE_DETAIL_MISSING 기대. */
    Long saveMissingDsSbdCase() {
        NoticeVersion version = saveNoticeVersion("30006", "dsSbd 부재 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("상세없음", "경기도 광명시 오리로 1", 300), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030006", false, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** {@code dsSbd} 키는 있는데 빈 배열인 경우. 후보가 애초에 없으니 UNMATCHED 기대. */
    Long saveEmptyDsSbdCase() {
        NoticeVersion version = saveNoticeVersion("30007", "dsSbd 빈 배열 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("빈배열", "경기도 김포시 사우중로 1", 300), rentTerms(), null, null));

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030007", true, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** 공고 행이 하나도 없는 채 LH 상세만 있는 경우. 그 상세 혼자 UNMATCHED 잔여 행이 된다. */
    Long saveLhOnlyDetailCase() {
        NoticeVersion version = saveNoticeVersion("30008", "LH 단독 상세 테스트 공고");

        LhNoticeSupplement supplement = newSupplement(version, "2015122300030008", true, null);
        supplement.addComplexDetail(0, "LH단독단지", "경기도 하남시 미사대로 1", null,
                300, "개별난방", "39.00", null, null);
        supplementRepository.saveAndFlush(supplement);

        return version.getId();
    }

    /** LH 상세를 아직 한 번도 수집하지 않은 공고버전. supplement 자체가 없어 결과를 하나도 만들지 않는다. */
    Long saveVersionWithoutSupplement() {
        NoticeVersion version = saveNoticeVersion("30009", "LH 상세 수집 전 테스트 공고");
        housingRepository.save(new NoticeHousing(version, 0, 1, 50,
                suppliedHousing("수집전", "경기도 이천시 중리천로 1", 300), rentTerms(), null, null));

        return version.getId();
    }

    private NoticeVersion saveNoticeVersion(String sourceNoticeId, String title) {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, sourceNoticeId));
        return noticeVersionRepository.save(NoticeVersion.firstVersion(root, sourceNoticeId, null, snapshot(title)));
    }

    private LhNoticeSupplement newSupplement(NoticeVersion version, String sourcePanId,
                                             boolean complexDetailDatasetPresent, String correctionReason) {
        return new LhNoticeSupplement(version, SourceSystem.LH_CHEONGYAK_PLUS, sourcePanId,
                "03", "06", "10", "063",
                LocalDateTime.of(2026, 8, 11, 12, 0), LocalDateTime.of(2026, 8, 11, 13, 0),
                complexDetailDatasetPresent, correctionReason);
    }

    private NoticeSnapshot snapshot(String title) {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                title, "https://www.myhome.go.kr/detail",
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 11, 20),
                "LH", "아파트", "행복주택", "LH 콜센터 : 1600-1004");
    }

    private SuppliedHousing suppliedHousing(String complexName, String fullAddress, Integer totalUnitCount) {
        return new SuppliedHousing(complexName, fullAddress, null, null, null, null, null, null, totalUnitCount);
    }

    private RentTerms rentTerms() {
        return new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L);
    }
}
