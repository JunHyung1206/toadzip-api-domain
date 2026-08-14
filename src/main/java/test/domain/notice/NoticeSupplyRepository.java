package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoticeSupplyRepository extends JpaRepository<NoticeSupply, Long> {

    List<NoticeSupply> findByNoticeIdOrderByDisplayOrder(Long noticeId);

    List<NoticeSupply> findByNoticeOrderByDisplayOrder(Notice notice);

    void deleteByNoticeId(Long noticeId);
}
