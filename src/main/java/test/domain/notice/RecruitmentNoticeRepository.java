package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import test.domain.source.SourceSystem;

import java.util.Optional;

public interface RecruitmentNoticeRepository extends JpaRepository<RecruitmentNotice, Long> {

    Optional<RecruitmentNotice> findBySourceSystemAndSourceRootNoticeId(
            SourceSystem sourceSystem, String sourceRootNoticeId);
}
