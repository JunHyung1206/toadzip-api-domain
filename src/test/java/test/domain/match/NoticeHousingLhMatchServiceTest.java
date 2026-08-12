package test.domain.match;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.notice.LhNoticeSupplementRepository;
import test.domain.notice.NoticeHousingRepository;
import test.domain.notice.NoticeVersionRepository;
import test.domain.notice.RecruitmentNoticeRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static test.domain.match.NoticeHousingLhMatchStatus.AMBIGUOUS;
import static test.domain.match.NoticeHousingLhMatchStatus.CONFLICT_UNIT_COUNT;
import static test.domain.match.NoticeHousingLhMatchStatus.MATCHED_ADDRESS_AND_UNIT_COUNT;
import static test.domain.match.NoticeHousingLhMatchStatus.MATCHED_ADDRESS_ONLY;
import static test.domain.match.NoticeHousingLhMatchStatus.SOURCE_DETAIL_MISSING;
import static test.domain.match.NoticeHousingLhMatchStatus.UNMATCHED;

@DataJpaTest
class NoticeHousingLhMatchServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T04:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String MATCHER_VERSION = "lh-address-unit-v1";

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;
    @Autowired
    private NoticeHousingRepository housingRepository;
    @Autowired
    private LhNoticeSupplementRepository supplementRepository;
    @Autowired
    private NoticeHousingLhMatchRepository repository;

    private MatchingFixtures fixtures;
    private NoticeHousingLhMatchService service;

    @BeforeEach
    void setUp() {
        fixtures = new MatchingFixtures(recruitmentNoticeRepository, noticeVersionRepository,
                housingRepository, supplementRepository);
        service = new NoticeHousingLhMatchService(repository, housingRepository, supplementRepository, FIXED_CLOCK);
    }

    @Test
    void normalizesOnlyTrimAndOrdinarySpaces() {
        assertThat(NoticeHousingLhMatchService.normalize(" 경기도 파주시 교하로 20 "))
                .isEqualTo("경기도파주시교하로20");
        assertThat(NoticeHousingLhMatchService.normalize("교하로-20"))
                .isEqualTo("교하로-20");
    }

    @Test
    void matchesPajuRowsByAddressEvenWhenLhOrderDiffers() {
        MatchingFixtures.PajuData data = fixtures.savePajuWithLastTwoLhRowsReversed();

        service.match(data.noticeVersionId(), MATCHER_VERSION);

        assertThat(matchedPairs()).containsExactlyInAnyOrder(
                tuple("산내마을1단지", "산내마을1단지"),
                tuple("가람마을14단지", "가람마을14단지"),
                tuple("초롱꽃마을3단지", "초롱꽃마을3단지"),
                tuple("물향기마을7단지", "물향기마을7단지"),
                tuple("초롱꽃마을10단지", "초롱꽃마을10단지"),
                tuple("노을빛마을16단지", "노을빛마을16단지"));
    }

    @Test
    void doesNotForceMatchWhenOneNoticeRowHasTwoLhCandidates() {
        Long noticeVersionId = fixtures.saveBucheonOneToTwoCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
                .contains(AMBIGUOUS);
    }

    @Test
    void matchesWhenUnitCountsAreEqual() {
        Long noticeVersionId = fixtures.saveEqualUnitCountCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(MATCHED_ADDRESS_AND_UNIT_COUNT);
    }

    @Test
    void matchesAddressOnlyWhenOneSideUnitCountIsNull() {
        Long noticeVersionId = fixtures.saveOneSideNullUnitCountCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(MATCHED_ADDRESS_ONLY);
    }

    @Test
    void flagsConflictWhenUnitCountsDiffer() {
        Long noticeVersionId = fixtures.saveConflictingUnitCountCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(CONFLICT_UNIT_COUNT);
    }

    @Test
    void marksNoticeRowUnmatchedWhenNoAddressCandidateExists() {
        Long noticeVersionId = fixtures.saveZeroCandidateCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId))
                .filteredOn(row -> row.getNoticeHousing() != null)
                .extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(UNMATCHED);
    }

    @Test
    void flagsAmbiguousWhenTwoNoticeRowsShareTheSameLhDetail() {
        Long noticeVersionId = fixtures.saveReverseTwoCandidatesCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId))
                .filteredOn(row -> row.getNoticeHousing() != null)
                .extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(AMBIGUOUS, AMBIGUOUS);
    }

    @Test
    void marksSourceDetailMissingWhenDsSbdKeyIsAbsent() {
        Long noticeVersionId = fixtures.saveMissingDsSbdCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(SOURCE_DETAIL_MISSING);
    }

    @Test
    void marksUnmatchedWhenDsSbdArrayIsEmpty() {
        Long noticeVersionId = fixtures.saveEmptyDsSbdCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
                .containsExactly(UNMATCHED);
    }

    @Test
    void keepsLhOnlyDetailAsItsOwnUnmatchedRow() {
        Long noticeVersionId = fixtures.saveLhOnlyDetailCase();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).singleElement().satisfies(row -> {
            assertThat(row.getNoticeHousing()).isNull();
            assertThat(row.getLhComplexDetail()).isNotNull();
            assertThat(row.getStatus()).isEqualTo(UNMATCHED);
        });
    }

    @Test
    void producesNoResultsWhenSupplementDoesNotExistYet() {
        Long noticeVersionId = fixtures.saveVersionWithoutSupplement();

        service.match(noticeVersionId, MATCHER_VERSION);

        assertThat(resultsFor(noticeVersionId)).isEmpty();
    }

    @Test
    void replacesOnlyTheSameMatcherVersion() {
        MatchingFixtures.PajuData data = fixtures.savePajuWithLastTwoLhRowsReversed();

        service.match(data.noticeVersionId(), MATCHER_VERSION);
        service.match(data.noticeVersionId(), "lh-address-unit-v2");
        service.match(data.noticeVersionId(), MATCHER_VERSION);

        assertThat(repository.findAll()).hasSize(6 * 2);
    }

    private List<NoticeHousingLhMatch> resultsFor(Long noticeVersionId) {
        return repository.findAll().stream()
                .filter(row -> row.getNoticeVersion().getId().equals(noticeVersionId))
                .sorted(Comparator.comparing(NoticeHousingLhMatch::getResultOrder))
                .toList();
    }

    private List<Tuple> matchedPairs() {
        return repository.findAll().stream()
                .filter(row -> row.getStatus() == MATCHED_ADDRESS_AND_UNIT_COUNT
                        || row.getStatus() == MATCHED_ADDRESS_ONLY)
                .map(row -> tuple(row.getNoticeHousing().getSuppliedHousing().getComplexName(),
                        row.getLhComplexDetail().getComplexLabel()))
                .toList();
    }
}
