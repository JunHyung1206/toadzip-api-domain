package test.domain.match;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeHousingCatalogMatchRepository extends JpaRepository<NoticeHousingCatalogMatch, Long> {

    void deleteByNoticeHousingNoticeVersionIdAndMatcherVersion(Long noticeVersionId, String matcherVersion);
}
