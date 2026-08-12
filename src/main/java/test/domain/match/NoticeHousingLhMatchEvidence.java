package test.domain.match;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@link NoticeHousingLhMatch} 한 행이 왜 그 상태로 판정됐는지 보존한다. 주소·세대수를 원문과
 * 정규화값 둘 다 남기는 이유는, 나중에 이 matcher 를 의심할 때 정규화가 뭘 지웠는지 바로 봐야 하기 때문이다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeHousingLhMatchEvidence {

    @Column(name = "notice_address_raw", length = 300)
    private String noticeAddressRaw;

    @Column(name = "notice_address_normalized", length = 300)
    private String noticeAddressNormalized;

    @Column(name = "lh_address_raw", length = 300)
    private String lhAddressRaw;

    @Column(name = "lh_address_normalized", length = 300)
    private String lhAddressNormalized;

    @Column(name = "notice_unit_count")
    private Integer noticeUnitCount;

    @Column(name = "lh_unit_count")
    private Integer lhUnitCount;

    /** 후보로 걸렸던 {@code LhComplexDetail} id를 정렬한 뒤 쉼표로 이어 붙인 문자열. */
    @Column(name = "candidate_lh_complex_detail_ids", length = 500)
    private String candidateLhComplexDetailIds;

    @Column(name = "reason", length = 500)
    private String reason;

    public NoticeHousingLhMatchEvidence(String noticeAddressRaw,
                                        String noticeAddressNormalized,
                                        String lhAddressRaw,
                                        String lhAddressNormalized,
                                        Integer noticeUnitCount,
                                        Integer lhUnitCount,
                                        String candidateLhComplexDetailIds,
                                        String reason) {
        this.noticeAddressRaw = noticeAddressRaw;
        this.noticeAddressNormalized = noticeAddressNormalized;
        this.lhAddressRaw = lhAddressRaw;
        this.lhAddressNormalized = lhAddressNormalized;
        this.noticeUnitCount = noticeUnitCount;
        this.lhUnitCount = lhUnitCount;
        this.candidateLhComplexDetailIds = candidateLhComplexDetailIds;
        this.reason = reason;
    }
}
