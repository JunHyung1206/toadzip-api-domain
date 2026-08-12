package test.domain.ingest;

/** 원천 호출은 성공했지만 우리 도메인 경계나 최소 품질 조건에 맞지 않아 저장하지 않은 이유. */
public enum IngestRejectionReason {

    UNKNOWN_SUPPLY_TYPE,
    UNSUPPORTED_SUPPLY_TYPE,
    NOT_CONSTRUCTION_HOUSING,
    MISSING_IDENTITY,
    INVALID_SOURCE_ROW,

    /** LH 상세(15057999)가 아직 공급정보코드를 확인하지 못한 공급유형(통합공공임대)이라 호출 자체를 건너뛴 경우. */
    UNSUPPORTED_LH_SUPPLEMENT_TYPE
}
