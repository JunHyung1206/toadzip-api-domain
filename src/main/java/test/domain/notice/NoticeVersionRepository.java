package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;
import test.domain.source.SourceSystem;

import java.util.List;
import java.util.Optional;

public interface NoticeVersionRepository extends JpaRepository<NoticeVersion, Long> {

    Optional<NoticeVersion> findBySourceSystemAndSourceNoticeId(
            SourceSystem sourceSystem, String sourceNoticeId);

    /** LH 공고만 고른다. 공고 링크에 `panId` 가 있으면 LH 청약 사이트 주소다. */
    List<NoticeVersion> findByDetailUrlContaining(String fragment);
}
