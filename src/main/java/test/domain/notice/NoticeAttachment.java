package test.domain.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공고버전에 딸린 파일. 원천은 LH 15057999 {@code lhLeaseNoticeDtlInfo1} 이다.
 *
 * <p>공고문 원문(hwp/PDF)과 단지 이미지(조감도·배치도·평면도)를 한 테이블에 담는다.
 * 원천에서 서로 다른 데이터셋으로 오지만 <b>(구분, 파일명, URL) 로 모양이 같고</b>,
 * 둘 다 "이 공고버전에 딸린 파일"이라는 점도 같다. 이미지에만 붙는 단지명이 {@link #complexLabel} 이다.
 *
 * <p><b>공고 보충 스냅샷 소유다.</b> 조감도는 단지의 성질에 가깝지만 LH 가 그걸 공고 단위로 주고,
 * 우리 단지에 붙이려면 이름으로 매칭해야 한다 — PNU 가 없어서 안전하지 않다.
 * 그래서 원천이 준 그대로 공고의 보충 스냅샷에 달고, 단지명은 {@link #complexLabel} 에 문자열로만 남긴다.
 */
@Entity
@Table(
        name = "notice_attachment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notice_attachment_order",
                columnNames = {"notice_supplement_id", "display_order"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_supplement_id", nullable = false)
    private NoticeSupplement noticeSupplement;

    /** 원천 순서. 원천이 순번을 주지 않아 응답 순서를 쓴다({@link NoticeHousing} 과 같은 이유). */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    /** 파일 구분 원문 — "공고문(PDF)", "단지조감도", "동호배치도" 등. 값이 계속 늘어나 enum 으로 세우지 않았다. */
    @Column(nullable = false, length = 50)
    private String kind;

    /** 원천 CMN_AHFL_NM. 파일명. */
    @Column(nullable = false, length = 300)
    private String name;

    /** URL 은 500자로 잡는다. 공고 detail_url 에서 278자짜리가 나온 전례가 있다. */
    @Column(nullable = false, length = 500)
    private String url;

    /** LH 가 이미지에 붙여 주는 단지명. 우리 단지에 붙인 게 아니라 <b>원천이 말한 이름</b>이다. */
    @Column(name = "complex_label", length = 200)
    private String complexLabel;

    NoticeAttachment(NoticeSupplement noticeSupplement,
                            int displayOrder,
                            String kind,
                            String name,
                            String url,
                            String complexLabel) {
        this.noticeSupplement = noticeSupplement;
        this.displayOrder = displayOrder;
        this.kind = kind;
        this.name = name;
        this.url = url;
        this.complexLabel = complexLabel;
    }
}
