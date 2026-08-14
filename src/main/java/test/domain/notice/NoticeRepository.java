package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    Optional<Notice> findBySourceNoticeId(String sourceNoticeId);

    /** 뒤늦게 원공고가 들어왔을 때 그 아래로 옮겨야 할 정정공고를 찾는다. */
    List<Notice> findByBeforeSourceNoticeId(String beforeSourceNoticeId);

    /** 이 공고의 모든 버전. 루트 테이블을 없앤 자리를 이 컬럼이 대신한다. */
    List<Notice> findByRootSourceNoticeIdOrderByVersionNumber(String rootSourceNoticeId);

    /** LH 공고만 고른다. 공고 링크에 `panId` 가 있으면 LH 청약 사이트 주소다. */
    List<Notice> findByDetailUrlContaining(String fragment);
}
