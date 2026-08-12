package test.domain.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoticeHousingRepository extends JpaRepository<NoticeHousing, Long> {

    List<NoticeHousing> findByNoticeVersionOrderByDisplayOrder(NoticeVersion noticeVersion);

    List<NoticeHousing> findByNoticeVersionIdOrderByDisplayOrder(Long noticeVersionId);
}
