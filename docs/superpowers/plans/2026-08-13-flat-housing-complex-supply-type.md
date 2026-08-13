# 공급유형별 단지 평탄화 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `HousingComplex`를 공급유형별 독립 행으로 저장하고 `UnitType`을 직접 연결하며, 공급기관 공통 엔티티와 `ComplexRentalProgram`을 제거한다.

**Architecture:** 마이홈 원천의 `(hsmpSn, suplyTyNm)`를 `HousingComplex` 자연키로 삼는다. 같은 물리 단지의 주소·PNU·기관명·카탈로그 상세는 공급유형별 행에 중복 저장한다. `UnitType`은 `HousingComplex` FK를 직접 가지며, 15059475 매칭도 단지와 주택형을 직접 조회한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, H2, JUnit 5, AssertJ, Gradle

---

## 사전 기준

구현 전 다음 문서를 기준으로 원천 필드와 기존 적재 순서를 확인한다.

- `docs/원천-API-명세.md`
- `docs/원천-API-데이터-사전.md`
- `docs/원천-매핑.md`
- `docs/테이블-설계-사전.md`
- `docs/도메인-설계.md`

구현에서 보존해야 하는 원천 필드는 다음과 같다.

- 15110581: `hsmpSn`, `insttNm`, 주소·PNU, `hsmpNm`, `hshldCo`, `suplyTyNm`, 주택형·면적·기준 임대조건
- 15059475: `ARA_NM`, `AIS_TP_CD_NM`, `SBD_LGO_NM`, `SUM_HSH_CNT`, `DDO_AR`, `HSH_CNT`
- 15056765: `SBD_LGO_NM`, `DDO_AR`, `HSH_CNT`, `NOW_HSH_CNT`
- 공고 15108420: `suplyInsttNm`, `suplyTyNm`, 공급 대상 PNU

### Task 1: 평탄화된 도메인 모델 테스트 작성

**Files:**
- Modify: `src/test/java/test/domain/housing/ComplexRentalProgramPersistenceTest.java` → `src/test/java/test/domain/housing/HousingComplexSupplyTypePersistenceTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeComplexIngestServiceTest.java`

- [ ] **Step 1: 기존 프로그램 중심 테스트를 단지 중심 테스트로 바꾼다**

  테스트는 `ComplexRentalProgramRepository` 대신 `HousingComplexRepository`와 `UnitTypeRepository`를 주입한다. `HousingComplex`를 같은 `sourceComplexId`와 다른 `SupplyType`으로 두 건 저장하고, 각 단지의 `supplyType`, `supplyTypeName`, `unitCount`, `supplyInstitutionName`이 각각 보존되는지 검증한다.

  ```java
  @Test
  void storesSameSourceComplexAsIndependentRowsPerSupplyType() {
      HousingComplex fifty = complex("123", SupplyType.FIFTY_YEAR_RENTAL, "50년임대", 100, "LH");
      HousingComplex happy = complex("123", SupplyType.HAPPY_HOUSE, "행복주택", 80, "LH");

      complexRepository.saveAll(List.of(fifty, happy));

      assertThat(complexRepository.findAll()).extracting(HousingComplex::getSupplyType)
              .containsExactlyInAnyOrder(SupplyType.FIFTY_YEAR_RENTAL, SupplyType.HAPPY_HOUSE);
      assertThat(complexRepository.findAll()).extracting(HousingComplex::getUnitCount)
              .containsExactlyInAnyOrder(100, 80);
  }
  ```

- [ ] **Step 2: 같은 공급유형의 주택형만 같은 단지에 저장되는 테스트를 추가한다**

  두 `HousingComplex`에 같은 `typeName`·면적을 각각 저장하고 `unitTypeRepository.findByHousingComplex(...)` 결과가 각 단지에 한 건씩만 나오는지 검증한다. 이 테스트는 `UnitType` 자연키가 `housing_complex_id`를 포함해야 함을 고정한다.

- [ ] **Step 3: 테스트를 실행해 새 API가 없어서 실패하는지 확인한다**

  Run: `./gradlew test --tests test.domain.housing.HousingComplexSupplyTypePersistenceTest`

  Expected: FAIL because `HousingComplex` still requires `HousingProviderAgency`, `ComplexRentalProgram` is still the `UnitType` owner, and the new constructors/repository methods do not exist.

### Task 2: HousingComplex·UnitType 모델과 저장소를 변경한다

**Files:**
- Modify: `src/main/java/test/domain/housing/HousingComplex.java`
- Modify: `src/main/java/test/domain/housing/UnitType.java`
- Modify: `src/main/java/test/domain/housing/HousingComplexRepository.java`
- Modify: `src/main/java/test/domain/housing/UnitTypeRepository.java`
- Delete: `src/main/java/test/domain/housing/ComplexRentalProgram.java`
- Delete: `src/main/java/test/domain/housing/ComplexRentalProgramRepository.java`
- Delete: `src/main/java/test/domain/housing/HousingProviderAgency.java`
- Delete: `src/main/java/test/domain/housing/HousingProviderAgencyRepository.java`

- [ ] **Step 1: HousingComplex에 공급유형·세대수·기관명 문자열을 직접 둔다**

  `HousingComplex`에 다음 컬럼을 추가한다.

  ```java
  @Enumerated(EnumType.STRING)
  @Column(name = "supply_type", nullable = false, length = 30)
  private SupplyType supplyType;

  @Column(name = "supply_type_name", nullable = false, length = 50)
  private String supplyTypeName;

  @Column(name = "unit_count")
  private Integer unitCount;

  @Column(name = "supply_institution_name", nullable = false, length = 50)
  private String supplyInstitutionName;
  ```

  `@ManyToOne HousingProviderAgency`를 제거하고, 자연키 유니크 제약을 `source_system`, `source_complex_id`, `supply_type`으로 바꾼다. 생성자와 `updateCatalogDetails` 호출에 공급유형·세대수·기관명을 전달할 수 있게 한다. 같은 공급유형의 재수집은 `unitCount`, 기관명, 원천 문자열과 선택 상세값을 갱신한다.

- [ ] **Step 2: UnitType이 HousingComplex를 직접 참조하도록 바꾼다**

  `complexRentalProgram` 필드와 관련 생성자·주석을 제거하고 다음 관계를 추가한다.

  ```java
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "housing_complex_id", nullable = false)
  private HousingComplex housingComplex;
  ```

  `UnitType` 자연키와 `UnitTypeRepository` 메서드를 `HousingComplex` 기준으로 변경한다.

- [ ] **Step 3: 단지와 주택형 repository 메서드를 추가한다**

  `HousingComplexRepository`에 다음 메서드를 둔다.

  ```java
  Optional<HousingComplex> findBySourceSystemAndSourceComplexIdAndSupplyType(
          SourceSystem sourceSystem, String sourceComplexId, SupplyType supplyType);

  List<HousingComplex> findAllByAddressPnuAndSupplyType(String pnu, SupplyType supplyType);
  ```

  `UnitTypeRepository`는 `findByHousingComplexAndTypeNameAndExclusiveAreaAndResidentialCommonArea(...)`와 `findByHousingComplex(...)`를 제공한다.

- [ ] **Step 4: 모델 테스트를 통과시킨다**

  Run: `./gradlew test --tests test.domain.housing.HousingComplexSupplyTypePersistenceTest`

  Expected: PASS.

### Task 3: 마이홈 15110581 적재를 `(hsmpSn, supplyType)` 단위로 변경한다

**Files:**
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeComplexIngestServiceTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeComplexTransactionTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeComplexNationwideIngestTest.java`

- [ ] **Step 1: 서로 다른 공급유형을 독립 단지로 저장하는 실패 테스트를 고정한다**

  `complexItemsWithTwoSupplyTypes()` fixture를 적용한 뒤 `housingComplexRepository.findAll()`이 2건이고, 각 행의 `supplyType`, `unitCount`, `supplyInstitutionName`이 원천값과 일치하는지 검증한다. 각 행의 `UnitType`도 해당 공급유형 단지에만 연결되는지 확인한다.

- [ ] **Step 2: 입력 그룹 키를 복합키로 바꾼다**

  `apply`의 그룹을 `Map<Long, List<...>>`에서 `Map<ComplexSupplyKey, List<...>>`로 바꾼다. `ComplexSupplyKey`는 `hsmpSn`과 `SupplyType`을 가진 private record다. `ConstructionRentalPolicy`를 먼저 적용해 알 수 없거나 지원하지 않는 공급유형은 기존처럼 거절한다.

- [ ] **Step 3: applyComplex가 공급유형별 단지를 만들도록 바꾼다**

  주소 일관성·건설형 증거·동일 공급유형 내 `hshldCo` 일관성 검증은 유지한다. `newComplex`는 `insttNm`을 trim해 `supplyInstitutionName`으로 직접 저장한다. `complexRepository.findBySourceSystemAndSourceComplexIdAndSupplyType(...)`로 기존 단지를 찾는다. 더 이상 기관 repository나 프로그램 repository를 호출하지 않는다.

- [ ] **Step 4: 주택형을 단지에 직접 upsert한다**

  `upsertProgram`을 제거하고 `upsertUnitType(HousingComplex complex, MyHomeComplexItem row)`로 바꾼다. 자연키 조회·기준 임대조건 갱신·중복 제거는 기존 규칙을 유지하되 `UnitType`의 소유자를 `complex`로 바꾼다. `hshldCo`는 `HousingComplex.unitCount`에 저장한다.

- [ ] **Step 5: 마이홈 적재 테스트를 통과시킨다**

  Run: `./gradlew test --tests 'test.domain.ingest.myhome.*'`

  Expected: PASS, including two supply types under one `hsmpSn`, idempotent re-ingest, rejection counts, and transaction isolation.

### Task 4: 15059475 LH 임대단지 카탈로그 매칭을 직접 단지·주택형 조회로 변경한다

**Files:**
- Modify: `src/main/java/test/domain/ingest/lh/LhLeaseInfoIngestService.java`
- Modify: `src/test/java/test/domain/ingest/lh/LhLeaseInfoIngestServiceTest.java`

- [ ] **Step 1: 기존 프로그램 repository 기반 테스트를 단지 repository 기반으로 바꾼다**

  fixture에서 동일 지역·단지명에 `행복주택`과 `50년임대` 단지를 각각 만들고, `SUM_HSH_CNT`가 맞는 공급유형만 매칭되는 테스트를 추가한다. 면적이 정확히 같은 `UnitType`의 `HSH_CNT`만 `totalUnitCount`로 갱신되는지 확인한다.

- [ ] **Step 2: 서비스 의존성을 교체한다**

  `ComplexRentalProgramRepository`를 제거하고 `HousingComplexRepository`를 주입한다. `programsByKey()`를 `complexesByKey()`로 바꾸며 키는 정규화한 `ARA_NM`, `SBD_LGO_NM`, `AIS_TP_CD_NM`, `SUM_HSH_CNT`다. 후보가 하나일 때 `unitTypeRepository.findByHousingComplex(complex)`로 주택형을 조회한다.

- [ ] **Step 3: 매칭 검증을 유지한다**

  `SUM_HSH_CNT == HousingComplex.unitCount` 검증, `DDO_AR`의 `BigDecimal.compareTo` 정확 비교, 동일 UnitType을 가리키는 원천행 역방향 중복 보류, 빈·누락 `dsList` 스냅샷 보존 규칙을 그대로 유지한다.

- [ ] **Step 4: LH 매칭 테스트를 통과시킨다**

  Run: `./gradlew test --tests test.domain.ingest.lh.LhLeaseInfoIngestServiceTest`

  Expected: PASS, including supply-type separation, exact area matching, duplicate-source ambiguity, and empty snapshot preservation.

### Task 5: 공고 PNU·주택형 매칭에서 공급유형을 사용한다

**Files:**
- Modify: `src/main/java/test/domain/match/NoticeHousingCatalogMatchService.java`
- Modify: `src/main/java/test/domain/match/NoticeHousingUnitTypeMatchService.java`
- Modify: `src/test/java/test/domain/match/NoticeHousingCatalogMatchServiceTest.java`
- Modify: `src/test/java/test/domain/match/NoticeHousingUnitTypeMatchServiceTest.java`

- [ ] **Step 1: 동일 PNU·다른 공급유형의 카탈로그 후보 테스트를 작성한다**

  같은 PNU와 같은 단지명을 가지는 `HousingComplex`를 공급유형별로 두 건 만들고, `NoticeVersion.supplyType`이 행복주택이면 행복주택 단지만 `MATCHED_PNU`가 되는지 검증한다. 공급유형이 null이거나 후보가 여러 건이면 기존 매칭 결과를 `AMBIGUOUS` 또는 `UNMATCHED`로 남긴다.

- [ ] **Step 2: NoticeHousingCatalogMatchService가 공고 공급유형을 함께 조회하게 한다**

  `findAllByAddressPnu(pnu)`를 `findAllByAddressPnuAndSupplyType(pnu, noticeVersion.getSupplyType())`로 바꾼다. 공고 공급유형이 없으면 후보를 확정하지 않도록 명시한다.

- [ ] **Step 3: NoticeHousingUnitTypeMatchService가 단지에서 UnitType을 직접 조회하게 한다**

  `ComplexRentalProgramRepository`와 `SupplyType` 기반 프로그램 조회를 제거하고 `unitTypeRepository.findByHousingComplex(complex)`를 사용한다. 전용면적 허용오차 0.05㎡, 후보 0/1/다건 상태와 이유 문자열은 기존 동작을 유지한다.

- [ ] **Step 4: 공고 매칭 테스트를 통과시킨다**

  Run: `./gradlew test --tests 'test.domain.match.*'`

  Expected: PASS, including PNU + supply type disambiguation and direct UnitType lookup.

### Task 6: 기관 엔티티와 구 프로그램 참조를 제거한다

**Files:**
- Modify: all remaining Java tests under `src/test/java/test/domain` that import `HousingProviderAgency` or `ComplexRentalProgram`
- Delete: `src/main/java/test/domain/housing/HousingProviderAgency.java`
- Delete: `src/main/java/test/domain/housing/HousingProviderAgencyRepository.java`
- Delete: `src/main/java/test/domain/housing/ComplexRentalProgram.java`
- Delete: `src/main/java/test/domain/housing/ComplexRentalProgramRepository.java`

- [ ] **Step 1: 테스트 fixture와 생성자를 문자열 속성으로 바꾼다**

  모든 `new HousingProviderAgency(...)`를 제거하고 `HousingComplex` 생성 시 기관명 문자열을 전달한다. 모든 `new ComplexRentalProgram(...)`를 제거하고 `HousingComplex` 생성 후 `UnitType`을 직접 만든다.

- [ ] **Step 2: 전체 소스에서 구 참조를 검색한다**

  Run: `rg -n 'ComplexRentalProgram|complexRentalProgram|complex_rental_program|HousingProviderAgency|housingProviderAgency|housing_provider_agency' src/main/java src/test/java`

  Expected: no matches. `SupplyType`와 `NoticeVersion.supplyType` 참조는 남아야 한다.

- [ ] **Step 3: 도메인·저장 테스트를 통과시킨다**

  Run: `./gradlew test --tests 'test.domain.housing.*' --tests 'test.domain.notice.*'`

  Expected: PASS.

### Task 7: H2 설정과 재적재 절차를 안전하게 정리한다

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `docs/원천-API-명세.md`
- Modify: `docs/원천-API-데이터-사전.md`
- Modify: `docs/원천-매핑.md`
- Modify: `docs/테이블-설계-사전.md`
- Modify: `docs/도메인-설계.md`

- [ ] **Step 1: 새 스키마용 H2 파일 경로를 분리한다**

  기존 `data/domain-construction-rental-v2` 파일을 건드리지 않도록 개발 기본 URL을 `jdbc:h2:file:./data/domain-construction-rental-v3;MODE=MySQL;AUTO_SERVER=TRUE`로 변경한다. 기존 DB는 삭제하지 않는다.

- [ ] **Step 2: 설계·테이블 문서의 관계를 갱신한다**

  ERD와 테이블 목록에서 `HousingProviderAgency`·`ComplexRentalProgram`을 제거하고 `HousingComplex → UnitType`을 기록한다. `housing_complex` 유니크 키, 공급유형 enum/원문, 세대수·기관명 컬럼과 `unit_type.housing_complex_id`를 반영한다.

- [ ] **Step 3: 원천 API 문서의 저장 위치를 갱신한다**

  15110581의 `suplyTyNm`, `hshldCo`, `insttNm`을 `housing_complex`로, 주택형 필드를 `unit_type`으로 직접 매핑한다. 15059475의 `SUM_HSH_CNT` 검증 대상을 `HousingComplex.unitCount`로, `HSH_CNT` 반영 대상을 `UnitType.totalUnitCount`로 바꾼다. 15056765·15108420 공고 기관/공급유형은 `NoticeVersion` 문자열/enum을 사용한다고 기록한다.

- [ ] **Step 4: 재적재 순서와 H2 접속 문서를 갱신한다**

  `15110581 → 15059475 → 15108420 → LH 상세·공급정보·매칭` 순서를 남기고, v3 파일용 JDBC URL을 기록한다. 인증키는 환경변수로만 사용하고 문서나 코드에 넣지 않는다.

### Task 8: 전체 검증과 결과 정리

**Files:**
- Verify: all modified Java and documentation files

- [ ] **Step 1: 포맷·정적 검색을 실행한다**

  Run: `git diff --check && rg -n 'ComplexRentalProgram|complexRentalProgram|complex_rental_program|HousingProviderAgency|housingProviderAgency|housing_provider_agency' src/main/java src/test/java docs/원천-API-명세.md docs/원천-API-데이터-사전.md docs/원천-매핑.md docs/테이블-설계-사전.md docs/도메인-설계.md`

  Expected: diff check passes; the search returns no obsolete model references.

- [ ] **Step 2: 전체 테스트를 실행한다**

  Run: `./gradlew test`

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: 새 H2에서 스키마를 확인한다**

  Run the application against `data/domain-construction-rental-v3` and inspect with H2 Shell:

  ```sql
  SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME;

  SELECT NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_NAME IN ('HOUSING_COMPLEX', 'UNIT_TYPE')
  ORDER BY TABLE_NAME, ORDINAL_POSITION;
  ```

  Expected: no `COMPLEX_RENTAL_PROGRAM` or `HOUSING_PROVIDER_AGENCY`; `HOUSING_COMPLEX` has supply type, count, institution columns; `UNIT_TYPE` references `HOUSING_COMPLEX`.

- [ ] **Step 4: 최종 diff와 상태를 확인한다**

  Run: `git status --short && git diff --stat HEAD~1..HEAD`

  Expected: only the approved model, ingestion, tests, configuration, and documentation changes are present; unrelated untracked user files remain untouched.

