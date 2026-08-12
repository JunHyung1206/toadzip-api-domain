package test.domain.notice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import test.domain.source.SourceSystem;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RecruitmentNoticePersistenceTest {

    @Autowired
    private RecruitmentNoticeRepository recruitmentNoticeRepository;
    @Autowired
    private NoticeVersionRepository noticeVersionRepository;

    @Test
    void storesCorrectionVersionsUnderOneRecruitmentNotice() {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "20965"));
        NoticeVersion original = noticeVersionRepository.save(
                NoticeVersion.firstVersion(root, "20965", null, snapshot("행복주택")));
        NoticeVersion correction = noticeVersionRepository.save(
                original.nextVersion("20989", "20965", snapshot("행복주택")));

        assertThat(correction.getRecruitmentNotice().getId()).isEqualTo(root.getId());
        assertThat(correction.getSupersedesVersion().getId()).isEqualTo(original.getId());
        assertThat(correction.getBeforeSourceNoticeId()).isEqualTo("20965");
        assertThat(correction.getVersionNumber()).isEqualTo(2);
    }

    @Test
    void noticeHousingPreservesSourceRowWithoutCatalogForeignKey() {
        RecruitmentNotice root = recruitmentNoticeRepository.save(
                new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "30001"));
        NoticeVersion version = noticeVersionRepository.save(
                NoticeVersion.firstVersion(root, "30001", null, snapshot("행복주택")));

        NoticeHousing housing = new NoticeHousing(version, 0, 3, 117, suppliedHousing(), rentTerms(),
                "https://www.myhome.go.kr/pc", "https://www.myhome.go.kr/mobile");

        assertThat(Arrays.stream(NoticeHousing.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("complex", "housingComplex");
        assertThat(housing.getHouseSn()).isEqualTo(3);
    }

    private NoticeSnapshot snapshot(String supplyTypeName) {
        return new NoticeSnapshot(NoticeChangeStatus.ORIGINAL, LocalDateTime.of(2026, 8, 11, 0, 0),
                "행복주택 입주자 모집", "https://www.myhome.go.kr/detail",
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13), LocalDate.of(2026, 11, 20),
                "LH", "아파트", supplyTypeName, "LH 콜센터 : 1600-1004");
    }

    private SuppliedHousing suppliedHousing() {
        return new SuppliedHousing("구리수택", "경기도 구리시 수택동", "4131010500108520000",
                "체육관로74번길", null, "경기도", "구리시", "개별난방", 394);
    }

    private RentTerms rentTerms() {
        return new RentTerms(37_224_000L, 1_862_000L, 35_362_000L, 156_000L);
    }
}
