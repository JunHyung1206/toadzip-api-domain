package test.domain.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import test.domain.source.SourceSystem;

/**
 * 공고 하나(원공고 + 정정 체인 전체)를 묶는 루트.
 *
 * <p>{@link NoticeVersion} 은 정정이 나올 때마다 새로 쌓이는 한 시점의 불변 스냅샷이다. 이 루트는
 * 그 버전들이 전부 "같은 공고"라는 사실을 sourceRootNoticeId(체인 맨 앞의 pblancId)로 고정한다.
 */
@Entity
@Table(name = "recruitment_notice", uniqueConstraints = @UniqueConstraint(
        name = "uk_recruitment_notice_source_root",
        columnNames = {"source_system", "source_root_notice_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecruitmentNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    @Column(name = "source_root_notice_id", nullable = false, length = 50)
    private String sourceRootNoticeId;

    public RecruitmentNotice(SourceSystem sourceSystem, String sourceRootNoticeId) {
        this.sourceSystem = sourceSystem;
        this.sourceRootNoticeId = sourceRootNoticeId;
    }
}
