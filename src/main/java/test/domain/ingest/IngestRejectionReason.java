package test.domain.ingest;

/** 원천 호출은 성공했지만 우리 도메인 경계나 최소 품질 조건에 맞지 않아 저장하지 않은 이유. */
public enum IngestRejectionReason {

    UNKNOWN_SUPPLY_TYPE,
    UNSUPPORTED_SUPPLY_TYPE,
    NOT_CONSTRUCTION_HOUSING,
    MISSING_IDENTITY,
    INVALID_SOURCE_ROW
}
