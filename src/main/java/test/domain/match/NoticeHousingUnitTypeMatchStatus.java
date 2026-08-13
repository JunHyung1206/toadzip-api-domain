package test.domain.match;

public enum NoticeHousingUnitTypeMatchStatus {

    /** 전용면적이 허용 오차 안에서 유일한 카탈로그 주택형과 맞았다. */
    MATCHED,

    /** 전용면적이 맞는 카탈로그 주택형이 둘 이상이다. */
    AMBIGUOUS,

    /** 단지까지는 확정했는데 그 안에 전용면적이 맞는 주택형이 없다. */
    UNMATCHED,

    /**
     * 카탈로그 단지까지 가는 길이 끊겼다. 어느 구간에서 끊겼는지는
     * {@link NoticeHousingUnitTypeMatch#getReason()} 에 남는다 — LH 상세 단지명, 주소 매칭, PNU 매칭 셋 중 하나다.
     */
    NO_CATALOG_PATH,

    /** 원천이 이번 공고버전에 15056765 데이터셋을 주지 않았거나 아직 받지 않았다. */
    SOURCE_DATA_MISSING
}
