package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoticeVersionRepository extends JpaRepository<NoticeVersion, Long> {

    Optional<NoticeVersion> findTopByNoticeIdOrderByVersionNumberDesc(String noticeId);

    Optional<NoticeVersion> findBySourceNoticeId(String sourceNoticeId);
}
