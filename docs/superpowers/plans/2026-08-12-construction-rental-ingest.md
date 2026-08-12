# Construction Rental Ingest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 건설형 공공임대만 저장하는 명시적 경계를 만들고, LH 상세의 일정·접수처·공고 단지·첨부를 불변 보충 스냅샷으로 적재한다.

**Architecture:** 마이홈 적재 앞단의 `ConstructionRentalPolicy`가 공급유형과 건설 흔적을 판정하고 `IngestReport`가 제외와 실패를 분리한다. LH 상세는 `NoticeVersion`을 수정하지 않고 `NoticeSupplement` aggregate를 공고별 한 번 저장하며, 자식 엔티티는 cascade로 함께 저장한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, H2, JUnit 5, AssertJ, Gradle

---

### Task 1: 적재 경계와 결과 모델

**Files:**
- Create: `src/main/java/test/domain/ingest/IngestRejectionReason.java`
- Create: `src/main/java/test/domain/ingest/ConstructionRentalPolicy.java`
- Modify: `src/main/java/test/domain/ingest/IngestReport.java`
- Modify: `src/main/java/test/domain/housing/SupplyType.java`
- Test: `src/test/java/test/domain/ingest/ConstructionRentalPolicyTest.java`
- Test: `src/test/java/test/domain/ingest/IngestReportTest.java`

- [ ] **Step 1: 공급유형·건설 흔적 정책의 실패 테스트 작성**

`ConstructionRentalPolicyTest`에서 여덟 허용값, 매입·전세, null·미등록값, 아파트 또는 준공일이 있는 비아파트를 각각 검증한다.

```java
assertThat(policy.rejectSupplyType("행복주택")).isEmpty();
assertThat(policy.rejectSupplyType("매입임대"))
        .contains(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE);
assertThat(policy.rejectSupplyType("청년안심주택"))
        .contains(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE);
assertThat(policy.rejectComplex("10년임대", "다세대주택", ""))
        .contains(IngestRejectionReason.NOT_CONSTRUCTION_HOUSING);
```

- [ ] **Step 2: 정책 테스트가 타입 부재로 실패하는지 확인**

Run: `./gradlew test --tests test.domain.ingest.ConstructionRentalPolicyTest`

Expected: `ConstructionRentalPolicy` 또는 `IngestRejectionReason`을 찾지 못해 컴파일 실패.

- [ ] **Step 3: 허용 목록 정책 최소 구현**

`SupplyType`에는 `isConstructionRental()` 인스턴스 메서드를 두고, 정책은 다음 시그니처로 구현한다.

```java
public Optional<IngestRejectionReason> rejectSupplyType(String sourceLabel)
public Optional<IngestRejectionReason> rejectComplex(
        String supplyTypeLabel, String houseTypeLabel, String completionDate)
public boolean hasValidPnu(String raw)
```

빈 값과 enum에 없는 값은 `UNKNOWN_SUPPLY_TYPE`, enum으로 읽히지만 비허용인 값은
`UNSUPPORTED_SUPPLY_TYPE`, 허용값이나 아파트도 준공일도 아니면 `NOT_CONSTRUCTION_HOUSING`을 반환한다.

- [ ] **Step 4: 사유별 결과 합산 실패 테스트 작성**

```java
IngestReport report = IngestReport.oneCreated()
        .plus(IngestReport.oneRejected(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE))
        .plus(IngestReport.oneFailed());

assertThat(report.created()).isOne();
assertThat(report.failed()).isOne();
assertThat(report.rejected()).isOne();
assertThat(report.rejectedByReason())
        .containsEntry(IngestRejectionReason.UNKNOWN_SUPPLY_TYPE, 1);
```

- [ ] **Step 5: 불변 `IngestReport` 구현 후 테스트 통과 확인**

`created`, `versioned`, `unchanged`, `failed`, `Map<IngestRejectionReason,Integer>`를 보관하고
`empty`, `oneCreated`, `oneVersioned`, `oneUnchanged`, `oneFailed`, `oneRejected`, `plus`, `rejected`를 제공한다.

Run: `./gradlew test --tests 'test.domain.ingest.*PolicyTest' --tests test.domain.ingest.IngestReportTest`

Expected: PASS.

### Task 2: 마이홈 단지·공고 적재에 정책 적용

**Files:**
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java`
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java`
- Modify: `src/main/java/test/domain/notice/SuppliedHousing.java`
- Test: `src/test/java/test/domain/ingest/myhome/MyHomeComplexIngestServiceTest.java`
- Test: `src/test/java/test/domain/ingest/myhome/MyHomeNoticeIngestServiceTest.java`
- Test: `src/test/java/test/domain/ingest/myhome/MyHomeFixtures.java`

- [ ] **Step 1: 모르는 공급유형과 무효 공급행 테스트 추가**

단지는 미등록 공급유형을 `UNKNOWN_SUPPLY_TYPE`으로 제외하고, 공고는 `houseSn <= 0`, 빈 단지명·주소,
19자리 숫자가 아닌 PNU 행을 제외하며 유효 행이 없으면 `NoticeVersion`도 저장하지 않는 테스트를 작성한다.

- [ ] **Step 2: 기존 마이홈 테스트가 새 기대에서 실패하는지 확인**

Run: `./gradlew test --tests 'test.domain.ingest.myhome.*IngestServiceTest'`

Expected: 기존 블랙리스트와 4정수 `IngestReport` 때문에 컴파일 또는 assertion 실패.

- [ ] **Step 3: 두 서비스에 `ConstructionRentalPolicy` 주입 및 적용**

단지의 `applyOne`은 식별자 검사 뒤 `policy.rejectComplex(...)`를 호출한다. 공고의 `applyNotice`는 먼저
공급유형을 판정하고 다음 조건을 만족하는 행만 저장한다.

```java
private boolean validSupplyLine(MyHomeNoticeItem row) {
    return row.houseSn() != null && row.houseSn() > 0
            && SourceValues.trimToNull(row.hsmpNm()) != null
            && SourceValues.trimToNull(row.fullAdres()) != null
            && policy.hasValidPnu(row.pnu());
}
```

공고 그룹 안의 무효 행은 `INVALID_SOURCE_ROW`로 합산하고, 유효 행이 없으면 공고를 저장하지 않는다.
누락된 `pblancId` 행은 `MISSING_IDENTITY`로 보고한다.

- [ ] **Step 4: 마이홈 테스트 통과 확인**

Run: `./gradlew test --tests 'test.domain.ingest.myhome.*IngestServiceTest'`

Expected: PASS.

### Task 3: LH 보충 aggregate 엔티티

**Files:**
- Create: `src/main/java/test/domain/notice/NoticeSupplement.java`
- Create: `src/main/java/test/domain/notice/NoticeSupplementRepository.java`
- Create: `src/main/java/test/domain/notice/NoticeSchedule.java`
- Create: `src/main/java/test/domain/notice/NoticeScheduleRepository.java`
- Create: `src/main/java/test/domain/notice/ReceptionPlace.java`
- Create: `src/main/java/test/domain/notice/ReceptionPlaceRepository.java`
- Create: `src/main/java/test/domain/notice/NoticeComplexSnapshot.java`
- Create: `src/main/java/test/domain/notice/NoticeComplexSnapshotRepository.java`
- Create: `src/main/java/test/domain/notice/YearMonthAttributeConverter.java`
- Modify: `src/main/java/test/domain/notice/NoticeAttachment.java`
- Modify: `src/main/java/test/domain/notice/NoticeAttachmentRepository.java`
- Modify: `src/main/java/test/domain/notice/NoticeVersion.java`
- Test: `src/test/java/test/domain/notice/NoticeSupplementPersistenceTest.java`

- [ ] **Step 1: aggregate 저장 실패 테스트 작성**

공고 하나에 보충 스냅샷, 일정 두 개, 접수처, 단지 스냅샷, 첨부를 추가하고 repository 한 번의
`saveAndFlush`로 모두 저장되는지 검증한다. `YearMonth.of(2027, 11)` 재조회도 검증한다.

- [ ] **Step 2: 엔티티 부재로 실패하는지 확인**

Run: `./gradlew test --tests test.domain.notice.NoticeSupplementPersistenceTest`

Expected: 새 aggregate 타입을 찾지 못해 컴파일 실패.

- [ ] **Step 3: aggregate와 유니크 제약 최소 구현**

`NoticeSupplement`는 `NoticeVersion` 일대일 FK와 `SourceSystem`, `correctionReason`을 갖는다. 자식 컬렉션은
`cascade = CascadeType.ALL`, `orphanRemoval = true`로 두고 `addSchedule`, `addReceptionPlace`,
`addComplexSnapshot`, `addAttachment` 메서드가 양방향 관계를 설정한다. 각 자식은
`(notice_supplement_id, display_order)` 유니크 제약을 갖는다.

- [ ] **Step 4: 정정사유와 첨부 소유권 이동**

`NoticeVersion.correctionReason`과 `applyCorrectionReason`을 제거한다. `NoticeAttachment` FK를
`NoticeSupplement`로 바꾸고 repository의 `existsByNoticeVersion`을 제거한다.

- [ ] **Step 5: persistence 테스트 통과 확인**

Run: `./gradlew test --tests test.domain.notice.NoticeSupplementPersistenceTest`

Expected: PASS.

### Task 4: LH 상세 DTO와 값 변환

**Files:**
- Modify: `src/main/java/test/domain/ingest/lh/LhNoticeDetail.java`
- Modify: `src/main/java/test/domain/ingest/SourceValues.java`
- Test: `src/test/java/test/domain/ingest/SourceValuesTest.java`
- Test: `src/test/java/test/domain/ingest/lh/LhNoticeDetailMappingTest.java`

- [ ] **Step 1: 점 구분 날짜·입주예정월 및 데이터셋 매핑 실패 테스트 작성**

```java
assertThat(SourceValues.toDate("2026.09.07")).isEqualTo(LocalDate.of(2026, 9, 7));
assertThat(SourceValues.toYearMonth("2027.11")).isEqualTo(YearMonth.of(2027, 11));
```

실제 `dsSplScdl`, `dsCtrtPlc`, `dsSbd` JSON을 `Schedule`, `Reception`, `ComplexSnapshot` record로 변환해
계약 종료일, 전화번호, 입주예정월 원문이 매핑되는지 검증한다.

- [ ] **Step 2: 변환 테스트 실패 확인**

Run: `./gradlew test --tests test.domain.ingest.SourceValuesTest --tests test.domain.ingest.lh.LhNoticeDetailMappingTest`

Expected: 점 구분 날짜와 새 record가 없어 실패.

- [ ] **Step 3: 유연한 날짜와 LH record 구현**

`SourceValues.toDate`는 `yyyyMMdd`, `yyyy.MM.dd`를 받고 `toYearMonth`는 `yyyyMM`, `yyyy.MM`을 받는다.
기존 반환 계약을 유지하기 위해 파싱 실패는 null을 반환한다. `LhNoticeDetail`에는 공식 필드명을
`@JsonProperty`로 매핑한 `Schedule`, `Reception`, `ComplexSnapshot` record를 추가한다.

- [ ] **Step 4: 변환·매핑 테스트 통과 확인**

Run: `./gradlew test --tests test.domain.ingest.SourceValuesTest --tests test.domain.ingest.lh.LhNoticeDetailMappingTest`

Expected: PASS.

### Task 5: LH 상세 aggregate 적재

**Files:**
- Modify: `src/main/java/test/domain/ingest/lh/LhNoticeDetailIngestService.java`
- Modify: `src/test/java/test/domain/ingest/lh/LhNoticeDetailIngestServiceTest.java`

- [ ] **Step 1: 실제 최신 응답 기반 실패 테스트 확장**

픽스처에 `dsSplScdl`, `dsCtrtPlc`, `dsSbd`를 추가하고 다음을 검증한다.

- 정정사유가 supplement에 저장된다.
- 일정·접수처·단지·첨부 복수 행과 순서가 보존된다.
- 마이홈의 접수일·당첨일은 바뀌지 않는다.
- 같은 응답을 두 번 적용해도 aggregate가 하나다.
- 자식 없는 정상 응답도 빈 supplement가 저장된다.

- [ ] **Step 2: 기존 서비스로 실패 확인**

Run: `./gradlew test --tests test.domain.ingest.lh.LhNoticeDetailIngestServiceTest`

Expected: supplement repository와 새 데이터셋을 사용하지 않아 실패.

- [ ] **Step 3: 전체 응답을 먼저 변환한 뒤 한 번 저장**

서비스에 package-private `IngestReport apply(NoticeVersion version, JsonNode root)`를 두어 HTTP 없이 실제
응답 적재를 테스트한다. `apply`는 이미 supplement가 있으면 `oneUnchanged`, 아니면 모든 자식을 구성한 뒤
`supplementRepository.save(...)` 한 번으로 저장한다. `ingest()`는 공고별 예외를 잡아 로그와
`oneFailed()`를 남기고 다음 공고를 계속한다.

- [ ] **Step 4: LH 상세 테스트 통과 확인**

Run: `./gradlew test --tests test.domain.ingest.lh.LhNoticeDetailIngestServiceTest`

Expected: PASS.

### Task 6: API·문서 정합성 수정

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/test/domain/notice/SourceSystem.java`
- Modify: `src/main/java/test/domain/ingest/IngestController.java`
- Modify: `docs/도메인-설계.md`
- Modify: `docs/원천-매핑.md`

- [ ] **Step 1: 문서와 주석의 잘못된 원천 번호 검색**

Run: `rg -n '15021183|15057999|15056765|isPurchasedOrJeonse|correction_reason|NoticeSupplement' src docs`

Expected: 기존 번호·블랙리스트·정정사유 소유권 설명이 남아 있음.

- [ ] **Step 2: 공식 번호와 새 모델로 문서 수정**

LH 상세을 `15057999`로 바로잡고, 허용 목록 정책과 supplement 자식 테이블, 제외 사유, 재적재 순서를
`도메인-설계.md`와 `원천-매핑.md`에 반영한다. `15056765`는 권한·실측 전 보류라고 명시한다.

- [ ] **Step 3: 문서 정합성 확인**

Run: `rg -n '15021183|매입임대·전세임대인가|correction_reason.*notice_version' src/main docs/도메인-설계.md docs/원천-매핑.md`

Expected: 결과 없음.

### Task 7: 전체 회귀 검증과 리뷰

**Files:**
- Modify only files implicated by failures found below.

- [ ] **Step 1: 전체 테스트 실행**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: diff와 비밀정보 검사**

Run: `git diff --check && rg -n 'V\+5v6F|service-key=.+' src docs --glob '!application.properties'`

Expected: 공백 오류와 실제 인증키 노출 없음.

- [ ] **Step 3: 설계 요구사항 대조**

Run: `git diff --stat && git status --short`

Expected: 변경 파일이 정책·마이홈 적재·LH supplement·테스트·문서 범위에 한정되고 사용자 기존 변경은 보존됨.

- [ ] **Step 4: 완료 전 코드 리뷰 수행**

`requesting-code-review` 절차로 필터 누락, aggregate 원자성, 불변 스냅샷 침범, 날짜 손실, 사용자 변경 덮어쓰기
여부를 검토한다. 발견 사항은 재현 테스트를 먼저 추가한 뒤 수정한다.

- [ ] **Step 5: 최종 테스트 재실행**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`이며 이전 실행 이후 실패·경고성 테스트 변경 없음.
