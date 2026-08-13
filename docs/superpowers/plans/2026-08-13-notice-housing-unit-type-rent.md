# 공고 주택형별 임대조건 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 15059475의 주택형별 현재 임대조건을 보존하고, 기존 `NOTICE_HOUSING_UNIT_TYPE_MATCH` 조회행에 공고행 조건과 카탈로그 조건을 함께 표시한다.

**Architecture:** `LhLeaseInfo` 원천행에 `LS_GMY`·`RFE`를 추가한다. 유일 매칭된 `UnitType`의 기본 조건에는 보증금·월세를 반영하되 기존 전환한도는 유지한다. 기존 주택형 매칭 결과 테이블을 새 테이블로 복제하지 않고, 매칭행에 공고행 조건·현재 카탈로그 조건·전체 세대수를 스냅샷한다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, H2, JUnit 5, AssertJ, Gradle.

---

### Task 1: 15059475 임대조건 원천 필드 저장

**Files:**
- Modify: `src/main/java/test/domain/ingest/lh/LhLeaseInfoItem.java`
- Modify: `src/main/java/test/domain/housing/LhLeaseInfo.java`
- Modify: `src/main/java/test/domain/housing/LhLeaseInfoBatch.java`
- Modify: `src/main/java/test/domain/ingest/lh/LhLeaseInfoIngestService.java`
- Test: `src/test/java/test/domain/ingest/lh/LhLeaseInfoIngestServiceTest.java`

- [ ] **Step 1: Write the failing test** — 실제 응답 형태에 `LS_GMY`와 `RFE`를 넣고 저장된 `LhLeaseInfo`가 보증금·월세를 갖는지, 유일 매칭된 `UnitType.baseRentTerms`에도 두 값이 반영되는지 검증한다.
- [ ] **Step 2: Run the focused test to verify it fails**

  Run: `./gradlew test --tests test.domain.ingest.lh.LhLeaseInfoIngestServiceTest`

  Expected: FAIL because the response record and entity currently discard `LS_GMY` and `RFE`.

- [ ] **Step 3: Add raw-field mapping and persistence** — `LhLeaseInfoItem`에 `LS_GMY`, `RFE` 문자열 필드를 추가하고 `SourceValues.toInt`로 파싱해 `LhLeaseInfo`와 배치 생성자에 전달한다.
- [ ] **Step 4: Update only confirmed catalog matches** — `LhLeaseInfoIngestService`의 `MATCHED` 분기에서 보증금·월세가 있는 경우만 `UnitType`에 반영하고, 기존 `BaseRentTerms.convertibleDepositLimit`은 유지한다.
- [ ] **Step 5: Run the focused test to verify it passes**

  Run: `./gradlew test --tests test.domain.ingest.lh.LhLeaseInfoIngestServiceTest`

  Expected: PASS.

### Task 2: 주택형 매칭행에 공고·현재 조건 스냅샷 추가

**Files:**
- Modify: `src/main/java/test/domain/match/NoticeHousingUnitTypeMatch.java`
- Modify: `src/main/java/test/domain/match/NoticeHousingUnitTypeMatchService.java`
- Test: `src/test/java/test/domain/match/NoticeHousingUnitTypeMatchServiceTest.java`
- Modify: `docs/테이블-설계-사전.md`
- Modify: `docs/원천-API-데이터-사전.md`

- [ ] **Step 1: Write the failing test** — `NoticeHousingUnitTypeMatch`의 성공행이 부모 `NoticeHousing.rentTerms`, 매칭된 `UnitType.baseRentTerms`, `LhUnitSupply.totalUnitCount`를 각각 보존하는지 검증한다. 매칭 실패행은 조건이 null이어도 원천 주택형명·면적·세대수를 보존하는지 검증한다.
- [ ] **Step 2: Run the focused test to verify it fails**

  Run: `./gradlew test --tests test.domain.match.NoticeHousingUnitTypeMatchServiceTest`

  Expected: FAIL because the match entity has no embedded rent snapshots or copied total count.

- [ ] **Step 3: Add snapshot columns and constructor values** — `notice_deposit`, `notice_down_payment`, `notice_balance`, `notice_monthly_rent`, `catalog_deposit`, `catalog_monthly_rent`, `catalog_convertible_deposit_limit`, `source_total_unit_count` columns을 추가하고 matcher의 `row` 생성 시 각각 부모·카탈로그·공급행에서 복사한다.
- [ ] **Step 4: Document the two scopes** — 공고 조건은 동일 공고 공급행 값의 반복이며 과거 버전 보존용이고, 카탈로그 조건은 15059475 현재 스냅샷임을 테이블·원천 문서에 명시한다. 15056765의 `공고문 참조`는 숫자로 추정하지 않는다는 규칙도 유지한다.
- [ ] **Step 5: Run the focused test to verify it passes**

  Run: `./gradlew test --tests test.domain.match.NoticeHousingUnitTypeMatchServiceTest`

  Expected: PASS.

### Task 3: 전체 회귀 검증 및 커밋

**Files:**
- Verify: all files changed in Tasks 1–2

- [ ] **Step 1: Run the full test suite**

  Run: `./gradlew test`

  Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 2: Inspect the schema and diff** — `git diff --check`, `git status --short`, and the H2-generated columns confirm no unrelated files are staged.
- [ ] **Step 3: Commit the implementation**

  Run: `git add src/main/java src/test/java docs/테이블-설계-사전.md docs/원천-API-데이터-사전.md && git commit -m "feat: preserve housing type rental terms"`
