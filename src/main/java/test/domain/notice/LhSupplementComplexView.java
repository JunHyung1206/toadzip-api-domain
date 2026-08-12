package test.domain.notice;

import java.util.List;

/**
 * {@link LhComplexDetail} 하나에 원문 단지명이 정확히 일치하는 일정·첨부를 붙인 조회 전용 조립 결과.
 *
 * <p>DB FK 나 원천 엔티티 변경이 아니다. {@link LhSupplementComplexAssembler#assemble} 이 매번 다시 계산한다.
 */
public record LhSupplementComplexView(LhComplexDetail complexDetail,
                                      List<NoticeSchedule> schedules,
                                      List<NoticeAttachment> attachments) {
}
