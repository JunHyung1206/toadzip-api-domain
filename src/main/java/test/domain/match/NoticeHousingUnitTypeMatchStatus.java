package test.domain.match;

public enum NoticeHousingUnitTypeMatchStatus {

    /** 전용면적이 허용 오차 안에서 유일한 카탈로그 주택형과 맞았다. */
    MATCHED,

    /** 전용면적이 맞는 카탈로그 주택형이 둘 이상이다. */
    AMBIGUOUS,

    /** 15056765 공급행은 있지만 맞는 전용면적의 카탈로그 주택형이 없다. */
    UNMATCHED,

    /** 이 공급행의 단지명과 일치하는 15056765 공급행이 이 공고버전 배치에 없다. */
    NO_SUPPLY_ROW,

    /** 이 공급행이 카탈로그 단지(PNU)와 아직 확정 매칭되지 않았다({@link NoticeHousingCatalogMatchStatus#MATCHED_PNU} 아님). */
    NO_CATALOG_MATCH,

    /** 원천이 이번 공고버전에 15056765 데이터셋을 아직 안 줬거나 호출하지 않았다. */
    SOURCE_DATA_MISSING
}
