package test.domain.match;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeHousingUnitTypeMatchRepository extends JpaRepository<NoticeHousingUnitTypeMatch, Long> {

    void deleteByNoticeVersionIdAndMatcherVersion(Long noticeVersionId, String matcherVersion);
}
