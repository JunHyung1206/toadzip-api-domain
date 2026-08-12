package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NoticeSupplementRepository extends JpaRepository<NoticeSupplement, Long> {

    boolean existsByNoticeVersionId(Long noticeVersionId);

    Optional<NoticeSupplement> findByNoticeVersionId(Long noticeVersionId);
}
