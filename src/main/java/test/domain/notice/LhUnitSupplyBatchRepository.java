package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LhUnitSupplyBatchRepository extends JpaRepository<LhUnitSupplyBatch, Long> {

    boolean existsByNoticeVersionId(Long noticeVersionId);

    Optional<LhUnitSupplyBatch> findByNoticeVersionId(Long noticeVersionId);
}
