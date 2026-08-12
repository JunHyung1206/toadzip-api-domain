package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LhNoticeSupplementRepository extends JpaRepository<LhNoticeSupplement, Long> {

    boolean existsByNoticeVersionId(Long noticeVersionId);

    Optional<LhNoticeSupplement> findByNoticeVersionId(Long noticeVersionId);
}
