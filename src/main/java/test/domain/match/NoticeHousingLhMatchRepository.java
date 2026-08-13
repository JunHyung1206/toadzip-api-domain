package test.domain.match;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoticeHousingLhMatchRepository extends JpaRepository<NoticeHousingLhMatch, Long> {

    void deleteByNoticeVersionIdAndMatcherVersion(Long noticeVersionId, String matcherVersion);

    List<NoticeHousingLhMatch> findByNoticeVersionIdAndMatcherVersion(Long noticeVersionId, String matcherVersion);
}
