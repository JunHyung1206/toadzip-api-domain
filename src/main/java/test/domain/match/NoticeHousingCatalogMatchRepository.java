package test.domain.match;

import org.springframework.data.jpa.repository.JpaRepository;
import test.domain.notice.NoticeHousing;

import java.util.Optional;

public interface NoticeHousingCatalogMatchRepository extends JpaRepository<NoticeHousingCatalogMatch, Long> {

    void deleteByNoticeHousingNoticeVersionIdAndMatcherVersion(Long noticeVersionId, String matcherVersion);

    Optional<NoticeHousingCatalogMatch> findByNoticeHousingAndMatcherVersion(
            NoticeHousing noticeHousing, String matcherVersion);
}
