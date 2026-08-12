package test.domain.match;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeHousingLhMatchRepository extends JpaRepository<NoticeHousingLhMatch, Long> {

    void deleteByNoticeVersionIdAndMatcherVersion(Long noticeVersionId, String matcherVersion);
}
