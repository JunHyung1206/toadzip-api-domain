package test.domain.match;

public enum LhLeaseInfoUnitTypeMatchStatus {

    /** 지역·단지명·공급유형·단지 세대수·전용면적이 모두 유일하게 맞았다. */
    MATCHED,

    /** 같은 조건의 카탈로그 단지·공급유형 또는 주택형이 여러 개다. */
    AMBIGUOUS,

    /** 카탈로그 후보가 없거나 전용면적·HSH_CNT가 비어 확정할 수 없다. */
    UNMATCHED,

    /** 단지명 등은 맞지만 SUM_HSH_CNT가 카탈로그 단지·공급유형 총세대수와 다르다. */
    CONFLICT_PROGRAM_UNIT_COUNT
}
