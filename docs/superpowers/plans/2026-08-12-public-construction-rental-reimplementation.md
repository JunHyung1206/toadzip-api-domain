# Public Construction Rental Reimplementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 마이홈 원천에서 전국의 여덟 건설임대 유형만 수집하고, 단지·공고·LH 보충정보를 승인된 aggregate와 파생 매칭 모델로 다시 구현한다.

**Architecture:** 원천별 요청 코드와 응답 DTO는 ingest 경계에 둔다. HTTP 응답은 지역 또는 공급유형의 마지막 페이지까지 모두 받은 뒤 도메인으로 넘기고, `HousingComplex`·`RecruitmentNotice`·`LhNoticeSupplement` 단위로 트랜잭션을 연다. 원천 스냅샷에는 다른 원천 FK를 쓰지 않고, PNU 및 주소 기반 연결은 matcher 버전과 근거를 가진 별도 엔티티에 저장한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, H2, Jackson, JUnit 5, AssertJ, Mockito, Gradle

---

## 구현 전제

- 포함 공급유형은 `01, 02, 03, 05, 06, 07, 10, 12`뿐이다. `13`은 이번 구현에서 호출하지 않는다.
- 단지 응답의 허용 이름은 `영구임대, 국민임대, 50년임대, 10년임대, 5년임대, 장기전세, 행복주택, 통합공공임대`다.
- 보고서의 `created/versioned/unchanged`는 단지에서는 `hsmpSn` aggregate, 공고에서는 `pblancId` aggregate 기준이다. 비허용 공급유형은 행 기준, 주소 충돌은 단지 기준, 세대수 충돌은 프로그램 기준으로 `rejected`를 센다.
- 동일 `hsmpSn`에서 주소 외 단지 공통 속성은 현재 원천 불변 조건을 유지한다. 첫 허용 행의 값을 사용하고 주소만 비어 있지 않은 값들의 일치 여부를 검사한다. 이 규칙을 확대하지 않는다.
- 개발용 admin API는 외부 호환 계약이 아니다. 지역 직접 지정 및 mutable 재매칭 엔드포인트를 없애고 전국 수집과 파생 매칭 엔드포인트로 교체한다.
- 기존 `data/domain.mv.db`는 읽거나 삭제하거나 덮어쓰지 않는다. 새 기본 경로는 `data/domain-construction-rental-v2`다.
- `SupplyAllocation`, `SelectionTier`, 6년임대, 공공분양, 매입임대, 전세임대, 공공지원민간임대, 이름 유사도·지오코딩 fallback은 구현하지 않는다.

## Task 1: 원천 공급유형과 공식 전국 지역 목록 고정

**Files:**

- Create: `src/main/java/test/domain/ingest/myhome/MyHomeRentalType.java`
- Create: `src/main/java/test/domain/ingest/myhome/MyHomeRegion.java`
- Create: `src/main/java/test/domain/ingest/myhome/MyHomeRegionCatalog.java`
- Create: `src/main/resources/myhome-region-codes.csv`
- Create: `src/test/java/test/domain/ingest/myhome/MyHomeRentalTypeTest.java`
- Create: `src/test/java/test/domain/ingest/myhome/MyHomeRegionCatalogTest.java`

- [x] **Step 1: 허용 요청 코드가 정확히 여덟 개인 실패 테스트 작성**

```java
@Test
void exposesOnlyApprovedConstructionRentalCodes() {
    assertThat(MyHomeRentalType.requestCodes())
            .containsExactly("01", "02", "03", "05", "06", "07", "10", "12")
            .doesNotContain("04", "08", "09", "11", "13");
}

@Test
void mapsResponseLabelsToDomainTypes() {
    assertThat(MyHomeRentalType.fromResponseLabel(" 행복주택 "))
            .contains(MyHomeRentalType.HAPPY_HOUSE);
    assertThat(MyHomeRentalType.fromResponseLabel("매입임대")).isEmpty();
    assertThat(MyHomeRentalType.fromResponseLabel("6년임대")).isEmpty();
}
```

- [x] **Step 2: 테스트를 실행해 새 타입 부재로 실패하는지 확인**

Run: `./gradlew test --tests test.domain.ingest.myhome.MyHomeRentalTypeTest`

Expected: `MyHomeRentalType` 심볼을 찾지 못해 컴파일 실패.

- [x] **Step 3: API 코드 체계를 도메인 enum과 분리해 구현**

```java
public enum MyHomeRentalType {
    PERMANENT_RENTAL("01", "영구임대", SupplyType.PERMANENT_RENTAL),
    NATIONAL_RENTAL("02", "국민임대", SupplyType.NATIONAL_RENTAL),
    FIFTY_YEAR_RENTAL("03", "50년임대", SupplyType.FIFTY_YEAR_RENTAL),
    TEN_YEAR_RENTAL("05", "10년임대", SupplyType.TEN_YEAR_RENTAL),
    FIVE_YEAR_RENTAL("06", "5년임대", SupplyType.FIVE_YEAR_RENTAL),
    LONG_TERM_JEONSE("07", "장기전세", SupplyType.LONG_TERM_JEONSE),
    HAPPY_HOUSE("10", "행복주택", SupplyType.HAPPY_HOUSE),
    INTEGRATED_PUBLIC_RENTAL("12", "통합공공임대", SupplyType.INTEGRATED_PUBLIC_RENTAL);

    private final String requestCode;
    private final String responseLabel;
    private final SupplyType supplyType;

    MyHomeRentalType(String requestCode, String responseLabel, SupplyType supplyType) {
        this.requestCode = requestCode;
        this.responseLabel = responseLabel;
        this.supplyType = supplyType;
    }

    public String requestCode() {
        return requestCode;
    }

    public String responseLabel() {
        return responseLabel;
    }

    public SupplyType supplyType() {
        return supplyType;
    }

    public static List<String> requestCodes() {
        return Arrays.stream(values()).map(MyHomeRentalType::requestCode).toList();
    }

    public static Optional<MyHomeRentalType> fromResponseLabel(String raw) {
        String label = SourceValues.trimToNull(raw);
        return Arrays.stream(values()).filter(type -> type.responseLabel.equals(label)).findFirst();
    }
}
```

- [x] **Step 4: 공식 XLSX를 버전 관리 CSV로 옮기고 256개 검증 테스트 작성**

공식 파일은 `https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003675527&fileDetailSn=1`에서 받은 2026-07-01 코드표다. `/private/tmp/public_rental_complex_codes_260701.xlsx`로 내려받고 `shasum -a 256` 결과가 `3c60e6cb75c55ca8a4e601a47f37b4dff6d797c96a57780f759b97f512c6d761`인지 확인한다. Codex workspace dependency runtime의 spreadsheet Python과 `openpyxl.load_workbook("/private/tmp/public_rental_complex_codes_260701.xlsx", read_only=True, data_only=True)`를 사용해 광역 코드, 시군구 코드, 광역명, 시군구명 네 열을 다음 형식으로 내보낸다. 변환 스크립트는 일회성이므로 `/private/tmp/extract_myhome_regions.py`에 두고 저장소에는 CSV만 추가한다.

```csv
brtcCode,signguCode,brtcName,signguName
11,350,서울특별시,노원구
```

```java
@Test
void loadsAll256OfficialRegionsWithoutDuplicates() {
    List<MyHomeRegion> regions = catalog.all();

    assertThat(regions).hasSize(256);
    assertThat(regions).extracting(MyHomeRegion::fullCode).doesNotHaveDuplicates();
    assertThat(regions).anySatisfy(region -> {
        assertThat(region.brtcCode()).isEqualTo("11");
        assertThat(region.signguCode()).isEqualTo("350");
        assertThat(region.fullCode()).isEqualTo("11350");
        assertThat(region.signguName()).isEqualTo("노원구");
    });
}

@Test
void preservesFixedWidthCodesAndReturnsAnImmutableList() {
    assertThat(catalog.all()).allSatisfy(region -> {
        assertThat(region.brtcCode()).matches("\\d{2}");
        assertThat(region.signguCode()).matches("\\d{3}");
    });
    assertThatThrownBy(() -> catalog.all().clear())
            .isInstanceOf(UnsupportedOperationException.class);
}
```

- [x] **Step 5: 지역 값 객체와 classpath loader 구현**

```java
public record MyHomeRegion(String brtcCode, String signguCode, String brtcName, String signguName) {
    public MyHomeRegion {
        if (brtcCode == null || !brtcCode.matches("\\d{2}")) {
            throw new IllegalArgumentException("광역 코드는 두 자리 숫자여야 합니다: " + brtcCode);
        }
        if (signguCode == null || !signguCode.matches("\\d{3}")) {
            throw new IllegalArgumentException("시군구 코드는 세 자리 숫자여야 합니다: " + signguCode);
        }
    }

    public String fullCode() {
        return brtcCode + signguCode;
    }
}
```

`MyHomeRegionCatalog`은 애플리케이션 시작 시 CSV를 UTF-8로 한 번 읽고, 헤더를 제외한 256행을 `List.copyOf`로 보존한다. 열 수가 4가 아니거나 `fullCode`가 중복되거나 행 수가 256이 아니면 `IllegalStateException`으로 시작을 중단한다.

- [x] **Step 6: 두 테스트를 통과시키고 커밋**

Run: `./gradlew test --tests 'test.domain.ingest.myhome.MyHomeRentalTypeTest' --tests 'test.domain.ingest.myhome.MyHomeRegionCatalogTest'`

Expected: `BUILD SUCCESSFUL`, 256개 지역과 8개 공급유형 assertion 통과.

```bash
git add src/main/java/test/domain/ingest/myhome/MyHomeRentalType.java src/main/java/test/domain/ingest/myhome/MyHomeRegion.java src/main/java/test/domain/ingest/myhome/MyHomeRegionCatalog.java src/main/resources/myhome-region-codes.csv src/test/java/test/domain/ingest/myhome/MyHomeRentalTypeTest.java src/test/java/test/domain/ingest/myhome/MyHomeRegionCatalogTest.java
git commit -m "feat: define myhome construction rental scope"
```

## Task 2: 단지 모델을 프로그램 소유 구조로 변경

**Files:**

- Move: `src/main/java/test/domain/notice/SourceSystem.java` → `src/main/java/test/domain/source/SourceSystem.java`
- Modify: `src/main/java/test/domain/housing/HousingComplex.java`
- Modify: `src/main/java/test/domain/housing/HousingComplexRepository.java`
- Modify: `src/main/java/test/domain/housing/CatalogDetails.java`
- Create: `src/main/java/test/domain/housing/ComplexRentalProgram.java`
- Create: `src/main/java/test/domain/housing/ComplexRentalProgramRepository.java`
- Modify: `src/main/java/test/domain/housing/UnitType.java`
- Modify: `src/main/java/test/domain/housing/UnitTypeRepository.java`
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java`
- Modify: `src/main/java/test/domain/ingest/lh/LhNoticeDetailIngestService.java`
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java`
- Modify: `src/main/java/test/domain/notice/NoticeSupplement.java`
- Modify: `src/main/java/test/domain/notice/NoticeVersion.java`
- Modify: `src/test/java/test/domain/ingest/lh/LhNoticeDetailIngestServiceTest.java`
- Modify: `src/test/java/test/domain/notice/NoticeSupplementPersistenceTest.java`
- Create: `src/test/java/test/domain/housing/ComplexRentalProgramPersistenceTest.java`

- [ ] **Step 1: 프로그램 소유권을 고정하는 persistence 실패 테스트 작성**

```java
@Test
void storesUnitCountOnceOnProgramAndKeepsSameShapeSeparate() {
    HousingComplex complex = complexRepository.save(new HousingComplex(
            "중계센트럴파크", address(), agency, SourceSystem.MYHOME_PORTAL, "30582290"));
    ComplexRentalProgram national = programRepository.save(
            new ComplexRentalProgram(complex, "국민임대", SupplyType.NATIONAL_RENTAL, 115));
    ComplexRentalProgram jeonse = programRepository.save(
            new ComplexRentalProgram(complex, "장기전세", SupplyType.LONG_TERM_JEONSE, 114));

    unitTypeRepository.save(new UnitType(national, "49A", area("49.9000"), area("20.1000")));
    unitTypeRepository.save(new UnitType(jeonse, "49A", area("49.9000"), area("20.1000")));
    entityManager.flush();
    entityManager.clear();

    assertThat(programRepository.findByHousingComplexOrderBySupplyTypeName(complex))
            .extracting(ComplexRentalProgram::getUnitCount)
            .containsExactlyInAnyOrder(115, 114);
    assertThat(unitTypeRepository.findAll()).extracting(UnitType::getComplexRentalProgram)
            .extracting(ComplexRentalProgram::getSupplyType)
            .containsExactlyInAnyOrder(SupplyType.NATIONAL_RENTAL, SupplyType.LONG_TERM_JEONSE);
}
```

- [ ] **Step 2: 테스트가 새 엔티티 부재로 실패하는지 확인**

Run: `./gradlew test --tests test.domain.housing.ComplexRentalProgramPersistenceTest`

Expected: `ComplexRentalProgram` 관련 컴파일 실패.

- [ ] **Step 3: 공통 원천 enum을 notice 패키지 밖으로 이동**

`git mv`로 파일을 옮기고 package를 `test.domain.source`로 바꾼 뒤 모든 import를 수정한다. 값은 `MYHOME_PORTAL`, `LH_CHEONGYAK_PLUS` 그대로 유지한다.

- [ ] **Step 4: `HousingComplex` 자연키와 카탈로그 속성 수정**

```java
@Table(name = "housing_complex", uniqueConstraints = @UniqueConstraint(
        name = "uk_housing_complex_source", columnNames = {"source_system", "source_complex_id"}))
public class HousingComplex {
    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    @Column(name = "source_complex_id", nullable = false)
    private String sourceComplexId;

    public boolean updateCatalogDetails(CatalogDetails details) {
        if (currentCatalogDetails().equals(details)) {
            return false;
        }
        completionDate = details.completionDate();
        completionYear = completionDate == null ? null : completionDate.getYear();
        heatingType = details.heatingType();
        heatingTypeName = details.heatingTypeName();
        parkingSpaces = details.parkingSpaces();
        corridorType = details.corridorType();
        elevatorInstallation = details.elevatorInstallation();
        houseType = details.houseType();
        houseTypeName = details.houseTypeName();
        return true;
    }
}
```

`maxSupplyTypeUnitCount`, `CatalogDetails.maxSupplyTypeUnitCount`, `withMaxSupplyTypeUnitCount`, `largerUnitCount`를 제거한다. repository 조회는 다음 시그니처로 바꾼다.

```java
Optional<HousingComplex> findBySourceSystemAndSourceComplexId(
        SourceSystem sourceSystem, String sourceComplexId);
```

- [ ] **Step 5: 프로그램과 주택형 구현**

```java
@Entity
@Table(name = "complex_rental_program", uniqueConstraints = @UniqueConstraint(
        name = "uk_complex_rental_program", columnNames = {"housing_complex_id", "supply_type_name"}))
public class ComplexRentalProgram {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    private HousingComplex housingComplex;

    @Column(name = "supply_type_name", nullable = false, length = 30)
    private String supplyTypeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "supply_type", nullable = false, length = 30)
    private SupplyType supplyType;

    @Column(name = "unit_count")
    private Integer unitCount;

    public boolean updateUnitCount(Integer incoming) {
        if (Objects.equals(unitCount, incoming)) {
            return false;
        }
        unitCount = incoming;
        return true;
    }
}
```

`UnitType`의 FK를 `ComplexRentalProgram complexRentalProgram`으로 바꾸고 공급유형 원문·enum·세대수 필드를 제거한다. 자연키 컬럼은 `complex_rental_program_id, type_name, exclusive_area, residential_common_area`로 바꾼다. `updateSupplyDetails`는 `boolean updateBaseRentTerms(BaseRentTerms incoming)`으로 축소한다.

이 단계가 끝날 때 production source가 컴파일되도록 `MyHomeComplexIngestService`의 행 단위 upsert도 새 repository와 생성자를 사용하게 옮긴다. 행마다 `ComplexRentalProgram`을 먼저 upsert한 뒤 그 프로그램으로 `UnitType`을 조회·생성한다. `hsmpSn` 그룹 검증과 트랜잭션 경계 변경은 Task 3에서 테스트와 함께 적용한다.

- [ ] **Step 6: 모델 테스트 통과 및 관련 회귀 테스트 컴파일 복구**

Run: `./gradlew test --tests 'test.domain.housing.*' --tests test.domain.notice.NoticeSupplementPersistenceTest`

Expected: `BUILD SUCCESSFUL`.

```bash
git add src/main/java/test/domain/source src/main/java/test/domain/housing src/main/java/test/domain/notice src/main/java/test/domain/ingest src/test/java/test/domain/housing src/test/java/test/domain/notice src/test/java/test/domain/ingest
git commit -m "refactor: model rental programs under complexes"
```

## Task 3: 단지 응답을 `hsmpSn` aggregate로 검증·저장

**Files:**

- Modify: `src/main/java/test/domain/ingest/ConstructionRentalPolicy.java`
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java`
- Modify: `src/test/java/test/domain/ingest/ConstructionRentalPolicyTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeComplexIngestServiceTest.java`
- Create: `src/test/java/test/domain/ingest/myhome/MyHomeComplexTransactionTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeFixtures.java`

- [ ] **Step 1: 그룹 판정 실패 테스트 추가**

다음 테스트를 실제 `MyHomeComplexItem` fixture로 먼저 작성한다.

```java
@Test
void filtersUnsupportedRowsBeforeCreatingPrograms() {
    IngestReport report = service.apply(itemsForOneComplex("국민임대", "매입임대"));

    assertThat(report.rejectedByReason())
            .containsEntry(IngestRejectionReason.UNSUPPORTED_SUPPLY_TYPE, 1);
    assertThat(programRepository.findAll()).extracting(ComplexRentalProgram::getSupplyTypeName)
            .containsExactly("국민임대");
}

@Test
void acceptsWholeComplexWhenAnyAllowedRowHasConstructionEvidence() {
    IngestReport report = service.apply(rowsWithApartmentEvidenceOnlyOnSecondRow());

    assertThat(report.created()).isOne();
    assertThat(complexRepository.count()).isOne();
    assertThat(unitTypeRepository.count()).isEqualTo(2);
}

@Test
void rejectsWholeComplexWhenNonBlankAddressesConflict() {
    IngestReport report = service.apply(rowsWithConflictingRoadAddresses());

    assertThat(report.rejectedByReason())
            .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
    assertThat(complexRepository.count()).isZero();
}

@Test
void rejectsOnlyProgramWhoseNonNullUnitCountsConflict() {
    IngestReport report = service.apply(rowsWithOneConflictingAndOneValidProgram());

    assertThat(report.rejectedByReason())
            .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
    assertThat(programRepository.findAll()).extracting(ComplexRentalProgram::getSupplyTypeName)
            .containsExactly("행복주택");
}
```

idempotency 기대값은 5개 행 기준 `unchanged=5`에서 단지 기준 `unchanged=1`로 바꾼다. 기존 공급유형별 세대수 테스트는 단지의 최댓값이 아니라 두 `ComplexRentalProgram.unitCount`가 `115`, `114`인지 검사한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests test.domain.ingest.myhome.MyHomeComplexIngestServiceTest`

Expected: 행 단위 판정과 저장 때문에 그룹 evidence, 프로그램 소유권, 보고 단위 assertion 실패.

- [ ] **Step 3: 정책을 공급유형 판정과 건설 흔적으로 분리**

```java
public boolean hasConstructionEvidence(String houseTypeLabel, String completionDate) {
    return HouseType.from(houseTypeLabel) == HouseType.APARTMENT
            || SourceValues.toDate(completionDate) != null;
}
```

`rejectComplex`는 제거하고, 허용 이름 검사는 `rejectSupplyType`, 그룹 흔적 검사는 위 메서드로 호출한다.

- [ ] **Step 4: 필터 후 그룹화하고 단지별 트랜잭션으로 적용**

```java
public IngestReport apply(List<MyHomeComplexItem> sourceRows) {
    IngestReport report = IngestReport.empty();
    Map<Long, List<MyHomeComplexItem>> accepted = new LinkedHashMap<>();
    for (MyHomeComplexItem row : sourceRows) {
        Optional<IngestRejectionReason> rejection = rentalPolicy.rejectSupplyType(row.suplyTyNm());
        if (rejection.isPresent()) {
            report = report.plus(IngestReport.oneRejected(rejection.orElseThrow()));
        } else if (row.hsmpSn() == null) {
            report = report.plus(IngestReport.oneRejected(IngestRejectionReason.MISSING_IDENTITY));
        } else {
            accepted.computeIfAbsent(row.hsmpSn(), key -> new ArrayList<>()).add(row);
        }
    }
    for (Map.Entry<Long, List<MyHomeComplexItem>> entry : accepted.entrySet()) {
        try {
            IngestReport applied = transactionTemplate.execute(
                    status -> applyComplex(entry.getKey(), entry.getValue()));
            report = report.plus(Objects.requireNonNull(applied));
        } catch (RuntimeException exception) {
            log.warn("마이홈 단지 aggregate 저장 실패: hsmpSn={}", entry.getKey(), exception);
            report = report.plus(IngestReport.oneFailed());
        }
    }
    return report;
}
```

`applyComplex`는 다음 순서로만 동작한다.

1. 파싱 가능한 준공일 또는 아파트 행이 하나라도 없으면 단지 하나를 `NOT_CONSTRUCTION_HOUSING`으로 제외한다.
2. 비어 있지 않은 `rnAdres` distinct 값이 1개가 아니면 `MISSING_IDENTITY` 또는 `INVALID_SOURCE_ROW`로 단지 전체를 제외한다.
3. `suplyTyNm`으로 프로그램을 묶고 비어 있지 않은 `hshldCo` distinct 값이 둘 이상인 프로그램만 제외한다.
4. 유효 프로그램이 없으면 엔티티를 만들지 않는다.
5. `HousingComplex`를 `(MYHOME_PORTAL, hsmpSn)`으로 upsert한다.
6. 프로그램을 `(complex, supplyTypeName)`으로 upsert하고 주택형을 `(program, typeName, exclusiveArea, residentialCommonArea)`으로 upsert한다.
7. nullable 면적이 DB unique 제약을 우회하지 않도록 저장 전 같은 자연키 행을 한 번 더 중복 제거한다.

생성자에서 `transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW)`를 설정해 호출자가 가진 트랜잭션과 무관하게 `hsmpSn` 하나가 독립 rollback 경계가 되게 한다.

- [ ] **Step 5: 단지 하나 롤백·다음 단지 계속 테스트 작성 및 통과**

`@DataJpaTest`와 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`를 사용한다. 첫 단지의 `UnitTypeRepository.save`에서 예외를 던지는 delegate를 넣고 두 번째 단지는 정상 저장한다.

```java
assertThat(report.failed()).isOne();
assertThat(report.created()).isOne();
assertThat(complexRepository.findBySourceSystemAndSourceComplexId(
        SourceSystem.MYHOME_PORTAL, "failed-hsmp")).isEmpty();
assertThat(complexRepository.findBySourceSystemAndSourceComplexId(
        SourceSystem.MYHOME_PORTAL, "saved-hsmp")).isPresent();
```

Run: `./gradlew test --tests 'test.domain.ingest.ConstructionRentalPolicyTest' --tests 'test.domain.ingest.myhome.MyHomeComplexIngestServiceTest' --tests 'test.domain.ingest.myhome.MyHomeComplexTransactionTest'`

Expected: `BUILD SUCCESSFUL`, 실패 단지의 complex/program/unit 행이 모두 0이고 다음 단지는 완전 저장.

```bash
git add src/main/java/test/domain/ingest/ConstructionRentalPolicy.java src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java src/test/java/test/domain/ingest/ConstructionRentalPolicyTest.java src/test/java/test/domain/ingest/myhome/MyHomeComplexIngestServiceTest.java src/test/java/test/domain/ingest/myhome/MyHomeComplexTransactionTest.java src/test/java/test/domain/ingest/myhome/MyHomeFixtures.java
git commit -m "refactor: ingest complexes as validated aggregates"
```

## Task 4: 전국 256개 지역을 완전 페이지 단위로 수집

**Files:**

- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java`
- Create: `src/test/java/test/domain/ingest/myhome/MyHomeComplexNationwideIngestTest.java`

- [ ] **Step 1: 호출 범위와 partial write 실패 테스트 작성**

```java
@Test
void requestsEveryOfficialRegionExactlyOnceWhenFirstPagesAreEmpty() {
    IngestReport report = service.ingestNationwide(500, 50);

    assertThat(report.failed()).isZero();
    assertThat(capturedRegions).containsExactlyInAnyOrderElementsOf(
            regionCatalog.all().stream().map(MyHomeRegion::fullCode).toList());
    assertThat(capturedRegions).hasSize(256).doesNotHaveDuplicates();
}

@Test
void doesNotPersistARegionWhenMaxPagesCutsItOff() {
    fakeClient.returnFullPageForEveryRequest();

    IngestReport report = service.ingestRegion(region("11", "350"), 1, 2);

    assertThat(report.failed()).isOne();
    assertThat(complexRepository.count()).isZero();
}

@Test
void doesNotPersistARegionWhenLaterPageFailsAndContinuesNationwide() {
    fakeClient.failOnPage("11350", 2);
    fakeClient.returnOneRowThenShortPage("41310");

    IngestReport report = service.ingestNationwide(500, 50);

    assertThat(report.failed()).isOne();
    assertThat(complexRepository.findBySourceSystemAndSourceComplexId(
            SourceSystem.MYHOME_PORTAL, "guri-row")).isPresent();
    assertThat(complexRepository.findBySourceSystemAndSourceComplexId(
            SourceSystem.MYHOME_PORTAL, "nowon-partial-row")).isEmpty();
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests test.domain.ingest.myhome.MyHomeComplexNationwideIngestTest`

Expected: `ingestNationwide` 부재 또는 현재 페이지별 즉시 저장 때문에 실패.

- [ ] **Step 3: 지역 전체를 메모리에 모은 뒤 적용하도록 구현**

```java
public IngestReport ingestNationwide(int pageSize, int maxPages) {
    IngestReport report = IngestReport.empty();
    for (MyHomeRegion region : regionCatalog.all()) {
        report = report.plus(ingestRegion(region, pageSize, maxPages));
    }
    return report;
}

IngestReport ingestRegion(MyHomeRegion region, int pageSize, int maxPages) {
    List<MyHomeComplexItem> completeRows = new ArrayList<>();
    try {
        for (int page = 1; page <= maxPages; page++) {
            List<MyHomeComplexItem> rows = fetchPage(region, page, pageSize);
            if (rows.isEmpty()) {
                return apply(completeRows);
            }
            completeRows.addAll(rows);
            if (rows.size() < pageSize) {
                return apply(completeRows);
            }
        }
        log.warn("단지 지역 조회가 maxPages 안에 끝나지 않음: region={}", region.fullCode());
        return IngestReport.oneFailed();
    } catch (RuntimeException exception) {
        log.warn("단지 지역 조회 실패: region={}", region.fullCode(), exception);
        return IngestReport.oneFailed();
    }
}
```

`fetchPage`는 `brtcCode`, `signguCode`, `numOfRows`, `pageNo`만 보낸다. HTTP 호출은 `apply`의 단지 트랜잭션 밖에 둔다.

- [ ] **Step 4: 페이지 경계의 같은 단지가 한 번만 저장되는 테스트 추가**

두 페이지에 같은 `hsmpSn`의 서로 다른 주택형을 배치하고 `created=1`, 프로그램 수와 주택형 수가 모두 보존되는지 검사한다.

Run: `./gradlew test --tests test.domain.ingest.myhome.MyHomeComplexNationwideIngestTest`

Expected: `BUILD SUCCESSFUL`, 256개 지역과 지역별 완전 수집 조건 통과.

```bash
git add src/main/java/test/domain/ingest/myhome/MyHomeComplexIngestService.java src/test/java/test/domain/ingest/myhome/MyHomeComplexNationwideIngestTest.java
git commit -m "feat: ingest nationwide construction rental complexes"
```

## Task 5: 공고를 루트·버전·주택 구조로 재구성

**Files:**

- Create: `src/main/java/test/domain/notice/RecruitmentNotice.java`
- Create: `src/main/java/test/domain/notice/RecruitmentNoticeRepository.java`
- Move: `src/main/java/test/domain/notice/SupplyLine.java` → `src/main/java/test/domain/notice/NoticeHousing.java`
- Move: `src/main/java/test/domain/notice/SupplyLineRepository.java` → `src/main/java/test/domain/notice/NoticeHousingRepository.java`
- Modify: `src/main/java/test/domain/notice/NoticeVersion.java`
- Modify: `src/main/java/test/domain/notice/NoticeVersionRepository.java`
- Modify: `src/main/java/test/domain/notice/NoticeSnapshot.java`
- Modify: `src/main/java/test/domain/notice/SuppliedHousing.java`
- Modify: `src/main/java/test/domain/notice/RentTerms.java`
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java`
- Create: `src/test/java/test/domain/notice/RecruitmentNoticePersistenceTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeNoticeIngestServiceTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeNoticeTransactionTest.java`
- Modify: `src/test/java/test/domain/ingest/lh/LhNoticeDetailIngestServiceTest.java`
- Modify: `src/test/java/test/domain/notice/NoticeSupplementPersistenceTest.java`

- [ ] **Step 1: 정정 체인과 원천 FK 부재를 보이는 실패 테스트 작성**

```java
@Test
void storesCorrectionVersionsUnderOneRecruitmentNotice() {
    RecruitmentNotice root = recruitmentNoticeRepository.save(
            new RecruitmentNotice(SourceSystem.MYHOME_PORTAL, "20965"));
    NoticeVersion original = noticeVersionRepository.save(
            NoticeVersion.firstVersion(root, "20965", null, snapshot("행복주택")));
    NoticeVersion correction = noticeVersionRepository.save(
            original.nextVersion("20989", "20965", snapshot("행복주택")));

    assertThat(correction.getRecruitmentNotice().getId()).isEqualTo(root.getId());
    assertThat(correction.getSupersedesVersion().getId()).isEqualTo(original.getId());
    assertThat(correction.getBeforeSourceNoticeId()).isEqualTo("20965");
    assertThat(correction.getVersionNumber()).isEqualTo(2);
}

@Test
void noticeHousingPreservesSourceRowWithoutCatalogForeignKey() {
    NoticeHousing housing = new NoticeHousing(version, 0, 3, 117, suppliedHousing(), rentTerms(),
            "https://www.myhome.go.kr/pc", "https://www.myhome.go.kr/mobile");

    assertThat(Arrays.stream(NoticeHousing.class.getDeclaredFields()).map(Field::getName))
            .doesNotContain("complex", "housingComplex");
    assertThat(housing.getHouseSn()).isEqualTo(3);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests test.domain.notice.RecruitmentNoticePersistenceTest`

Expected: 새 루트와 `NoticeHousing` 심볼 부재로 컴파일 실패.

- [ ] **Step 3: 새 aggregate 구현**

```java
@Entity
@Table(name = "recruitment_notice", uniqueConstraints = @UniqueConstraint(
        name = "uk_recruitment_notice_source_root",
        columnNames = {"source_system", "source_root_notice_id"}))
public class RecruitmentNotice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    @Column(name = "source_root_notice_id", nullable = false, length = 50)
    private String sourceRootNoticeId;
}
```

`NoticeVersion`은 `noticeId` 문자열 대신 필수 `RecruitmentNotice recruitmentNotice` FK를 갖고, `beforeSourceNoticeId` 원문을 추가한다. unique key는 `(recruitment_notice_id, version_number)`와 `(source_system, source_notice_id)`다. 기존 `sourceSystem`은 source ID의 범위를 고정하기 위해 버전에도 유지한다.

`NoticeHousing`은 테이블명을 `notice_housing`으로, 자연키를 `(notice_version_id, house_sn)`으로 바꾸고 `houseSn`을 non-null로 만든다. `HousingComplex complex`와 `attachComplex`는 삭제한다.

`MyHomeNoticeIngestService`도 같은 커밋 안에서 새 root/repository/생성자 시그니처로 컴파일되게 옮기고 즉시 PNU 연결을 제거한다. 이 단계에서는 기존 한 번의 rental path 수집을 유지하고, 여덟 코드 수집·그래프 순서 해소·aggregate 전체 내용 비교는 Task 6에서 바꾼다. LH 및 supplement persistence 테스트의 `NoticeVersion.firstVersion` 호출도 먼저 `RecruitmentNotice`를 저장하도록 고친다.

- [ ] **Step 4: repository를 새 이름과 조회 방향으로 변경**

```java
public interface RecruitmentNoticeRepository extends JpaRepository<RecruitmentNotice, Long> {
    Optional<RecruitmentNotice> findBySourceSystemAndSourceRootNoticeId(
            SourceSystem sourceSystem, String sourceRootNoticeId);
}

public interface NoticeHousingRepository extends JpaRepository<NoticeHousing, Long> {
    List<NoticeHousing> findByNoticeVersionOrderByDisplayOrder(NoticeVersion noticeVersion);
    List<NoticeHousing> findByNoticeVersionIdOrderByDisplayOrder(Long noticeVersionId);
}

public interface NoticeVersionRepository extends JpaRepository<NoticeVersion, Long> {
    Optional<NoticeVersion> findBySourceSystemAndSourceNoticeId(
            SourceSystem sourceSystem, String sourceNoticeId);
    List<NoticeVersion> findByDetailUrlContaining(String fragment);
}
```

- [ ] **Step 5: 기존 공고 테스트를 새 소유관계로 변경하고 통과**

`attachesComplexByPnu`와 `rematchesComplexesIngestedLater` 테스트는 삭제하지 말고 Task 8의 matcher 테스트로 의미를 이동한 뒤, 현재 파일에서는 원천 FK가 없다는 assertion으로 교체한다. `SupplyLine` 타입명은 모두 `NoticeHousing`으로 바꾼다.

Run: `./gradlew test --tests 'test.domain.notice.*' --tests 'test.domain.ingest.myhome.MyHomeNotice*Test'`

Expected: `BUILD SUCCESSFUL`, 정정 버전 두 개가 하나의 root를 공유하고 공고 주택은 카탈로그 FK를 갖지 않음.

```bash
git add src/main/java/test/domain/notice src/test/java/test/domain/notice src/test/java/test/domain/ingest/myhome
git commit -m "refactor: separate recruitment notice versions and housing"
```

## Task 6: 모집공고를 허용 코드별 완전 페이지로 수집

**Files:**

- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java`
- Modify: `src/main/java/test/domain/notice/NoticeHousing.java`
- Modify: `src/main/java/test/domain/notice/SuppliedHousing.java`
- Modify: `src/main/java/test/domain/notice/RentTerms.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeNoticeIngestServiceTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeNoticeTransactionTest.java`
- Modify: `src/test/java/test/domain/ingest/myhome/MyHomeFixtures.java`

- [ ] **Step 1: 여덟 코드·부분 적재 금지·중복 충돌 테스트 작성**

```java
@Test
void requestsOnlyEightApprovedSupplyTypeCodes() {
    service.ingest(100, 50);

    assertThat(capturedSupplyTypeCodes)
            .containsExactly("01", "02", "03", "05", "06", "07", "10", "12")
            .doesNotContain("13");
}

@Test
void doesNotApplyRowsFromATypeThatDoesNotReachItsLastPage() {
    fakeClient.returnFullPagesUntilLimit("10");

    IngestReport report = service.ingest(1, 2);

    assertThat(report.failed()).isOne();
    assertThat(noticeVersionRepository.findBySourceSystemAndSourceNoticeId(
            SourceSystem.MYHOME_PORTAL, "happy-partial")).isEmpty();
}

@Test
void collapsesIdenticalSourceKeysButRejectsConflictingNoticeRows() {
    IngestReport report = service.apply(rowsWithExactDuplicateAndConflictingDuplicate());

    assertThat(noticeHousingRepository.count()).isOne();
    assertThat(report.rejectedByReason())
            .containsEntry(IngestRejectionReason.INVALID_SOURCE_ROW, 1);
}

@Test
void keepsIdentifiedHousingEvenWhenPnuAndAddressAreMissing() {
    IngestReport report = service.apply(List.of(rowWithHouseSnButNoPnuOrAddress()));

    assertThat(report.created()).isOne();
    assertThat(noticeHousingRepository.findAll()).singleElement().satisfies(housing -> {
        assertThat(housing.getHouseSn()).isEqualTo(1);
        assertThat(housing.getSuppliedHousing().getPnu()).isNull();
        assertThat(housing.getSuppliedHousing().getFullAddress()).isNull();
    });
}

@Test
void resolvesCorrectionChainRegardlessOfInputOrder() {
    service.apply(correctionRowsBeforeOriginalRows());

    NoticeVersion original = version("20965");
    NoticeVersion correction = version("20989");
    assertThat(correction.getRecruitmentNotice().getId())
            .isEqualTo(original.getRecruitmentNotice().getId());
    assertThat(correction.getSupersedesVersion().getId()).isEqualTo(original.getId());
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests test.domain.ingest.myhome.MyHomeNoticeIngestServiceTest`

Expected: 현재 단일 무필터 요청, path 인자, 중복 처리 부재 때문에 실패.

- [ ] **Step 3: 공급유형별 수집을 완결한 뒤 한 번만 적용**

```java
public IngestReport ingest(int pageSize, int maxPages) {
    List<MyHomeNoticeItem> successfulRows = new ArrayList<>();
    IngestReport report = IngestReport.empty();
    for (MyHomeRentalType type : MyHomeRentalType.values()) {
        try {
            Optional<List<MyHomeNoticeItem>> rows = fetchComplete(type, pageSize, maxPages);
            if (rows.isPresent()) {
                successfulRows.addAll(rows.orElseThrow());
            } else {
                report = report.plus(IngestReport.oneFailed());
            }
        } catch (RuntimeException exception) {
            log.warn("마이홈 공고 공급유형 조회 실패: suplyTy={}", type.requestCode(), exception);
            report = report.plus(IngestReport.oneFailed());
        }
    }
    return report.plus(apply(successfulRows));
}
```

`fetchComplete`는 매 페이지에 `suplyTy`, `numOfRows`, `pageNo`를 넣고 빈 페이지 또는 `size < pageSize`일 때만 `Optional.of(rows)`를 반환한다. `maxPages`까지 모두 꽉 차면 `Optional.empty()`를 반환한다. path는 `RENTAL_PATH` 상수만 사용하고 외부 인자를 제거한다.

- [ ] **Step 4: `(pblancId, houseSn)` 중복 제거와 체인 저장 구현**

```java
private record NoticeHousingKey(String pblancId, Integer houseSn) {
}

private record DeduplicatedRows(
        List<MyHomeNoticeItem> accepted,
        Set<String> conflictedNoticeIds) {
}

private DeduplicatedRows deduplicate(List<MyHomeNoticeItem> rows) {
    Map<NoticeHousingKey, MyHomeNoticeItem> unique = new LinkedHashMap<>();
    Set<String> conflictedNoticeIds = new HashSet<>();
    for (MyHomeNoticeItem row : rows) {
        NoticeHousingKey key = new NoticeHousingKey(
                SourceValues.trimToNull(row.pblancId()), row.houseSn());
        MyHomeNoticeItem previous = unique.putIfAbsent(key, row);
        if (previous != null && !previous.equals(row) && key.pblancId() != null) {
            conflictedNoticeIds.add(key.pblancId());
        }
    }
    List<MyHomeNoticeItem> accepted = unique.values().stream()
            .filter(row -> !conflictedNoticeIds.contains(SourceValues.trimToNull(row.pblancId())))
            .toList();
    return new DeduplicatedRows(accepted, Set.copyOf(conflictedNoticeIds));
}
```

충돌한 ID는 공고 전체를 `INVALID_SOURCE_ROW` 한 건으로 제외한다. `NoticeHousing`의 필수 조건은 비어 있지 않은 `pblancId`와 양수 `houseSn`뿐이다. PNU·주소·단지명 누락은 행을 버릴 이유가 아니며 이후 matcher가 `UNMATCHED`로 남긴다.

정정 체인은 숫자 정렬이나 입력 순서에 의존하지 않는다. dedupe 후 `pblancId` 그룹을 만든 다음 아래 순서로 해소한다.

1. `beforePblancId`가 없거나 DB에 이미 있는 그룹을 먼저 저장한다.
2. 직전 그룹이 이번 batch에 있으면 그 그룹이 저장된 다음 현재 그룹을 저장한다.
3. `beforePblancId`가 DB와 batch 모두에 없으면 현재 ID로 새 `RecruitmentNotice`를 만들고 누락된 원문은 `beforeSourceNoticeId`에 남긴다.
4. 한 이전 버전을 둘 이상이 가리키는 분기와 batch 안 순환은 관련 공고들을 `INVALID_SOURCE_ROW`로 제외한다.

기존 `pblancId`가 다시 오면 `NoticeSnapshot`만 비교하지 않는다. 저장된 `houseSn` 집합과 각 `NoticeHousing`의 `supplyCount`, `SuppliedHousing`, `RentTerms`, 두 URL까지 비교한다. 하나라도 다르면 기존 aggregate를 수정하지 않고 `INVALID_SOURCE_ROW`로 보고한다.

이를 위해 `NoticeHousing.hasSameSourceContentAs(Integer houseSn, Integer supplyCount, SuppliedHousing suppliedHousing, RentTerms rentTerms, String detailUrl, String mobileDetailUrl)`, `SuppliedHousing.sameValues(SuppliedHousing left, SuppliedHousing right)`, `RentTerms.sameValues(RentTerms left, RentTerms right)`를 값 필드 전체 비교로 구현한다. JPA 식별자와 연관 FK는 비교 대상에서 제외한다.

- [ ] **Step 5: 공고 단위 트랜잭션 회귀 확인**

`TransactionTemplate`의 propagation을 `REQUIRES_NEW`로 두고 각 `pblancId` 실행을 `try/catch`로 감싼다. root·version·housing 중 하나라도 저장 실패하면 해당 공고를 전부 롤백해 `failed + 1`로 보고한 뒤 다음 공고를 계속한다.

Run: `./gradlew test --tests 'test.domain.ingest.myhome.MyHomeNoticeIngestServiceTest' --tests 'test.domain.ingest.myhome.MyHomeNoticeTransactionTest'`

Expected: `BUILD SUCCESSFUL`; 실패한 `pblancId`만 롤백되고 다음 공고가 저장되며, 한 공급유형의 잘린 페이지는 한 행도 저장되지 않음.

```bash
git add src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java src/test/java/test/domain/ingest/myhome/MyHomeNoticeIngestServiceTest.java src/test/java/test/domain/ingest/myhome/MyHomeNoticeTransactionTest.java src/test/java/test/domain/ingest/myhome/MyHomeFixtures.java
git commit -m "feat: fetch only construction rental notices"
```

## Task 7: LH 보충 aggregate와 공급정보 요청 코드를 정확히 저장

**Files:**

- Move: `src/main/java/test/domain/notice/NoticeSupplement.java` → `src/main/java/test/domain/notice/LhNoticeSupplement.java`
- Move: `src/main/java/test/domain/notice/NoticeSupplementRepository.java` → `src/main/java/test/domain/notice/LhNoticeSupplementRepository.java`
- Move: `src/main/java/test/domain/notice/NoticeComplexSnapshot.java` → `src/main/java/test/domain/notice/LhComplexDetail.java`
- Move: `src/main/java/test/domain/notice/NoticeComplexSnapshotRepository.java` → `src/main/java/test/domain/notice/LhComplexDetailRepository.java`
- Modify: `src/main/java/test/domain/notice/NoticeSchedule.java`
- Modify: `src/main/java/test/domain/notice/ReceptionPlace.java`
- Modify: `src/main/java/test/domain/notice/NoticeAttachment.java`
- Modify: `src/main/java/test/domain/ingest/IngestRejectionReason.java`
- Modify: `src/main/java/test/domain/ingest/lh/LhNoticeDetail.java`
- Create: `src/main/java/test/domain/ingest/lh/LhSupplyInfoTypeResolver.java`
- Create: `src/main/java/test/domain/ingest/lh/LhNoticeRequest.java`
- Modify: `src/main/java/test/domain/ingest/lh/LhNoticeDetailIngestService.java`
- Create: `src/main/java/test/domain/notice/LhSupplementComplexAssembler.java`
- Create: `src/main/java/test/domain/notice/LhSupplementComplexView.java`
- Move: `src/test/java/test/domain/notice/NoticeSupplementPersistenceTest.java` → `src/test/java/test/domain/notice/LhNoticeSupplementPersistenceTest.java`
- Create: `src/test/java/test/domain/notice/LhSupplementComplexAssemblerTest.java`
- Create: `src/test/java/test/domain/ingest/lh/LhSupplyInfoTypeResolverTest.java`
- Create: `src/test/java/test/domain/ingest/lh/LhNoticeRequestTest.java`
- Modify: `src/test/java/test/domain/ingest/lh/LhNoticeDetailMappingTest.java`
- Modify: `src/test/java/test/domain/ingest/lh/LhNoticeDetailIngestServiceTest.java`

- [ ] **Step 1: 공급정보 코드 resolver 실패 테스트 작성**

```java
@ParameterizedTest
@CsvSource({
        "FIVE_YEAR_RENTAL,060",
        "TEN_YEAR_RENTAL,060",
        "FIFTY_YEAR_RENTAL,061",
        "NATIONAL_RENTAL,062",
        "PERMANENT_RENTAL,062",
        "LONG_TERM_JEONSE,062",
        "HAPPY_HOUSE,063"
})
void resolvesOfficialSupplyInfoType(SupplyType supplyType, String expected) {
    assertThat(resolver.resolve(supplyType)).contains(expected);
}

@Test
void defersIntegratedPublicRentalSupplement() {
    assertThat(resolver.resolve(SupplyType.INTEGRATED_PUBLIC_RENTAL)).isEmpty();
}
```

- [ ] **Step 2: 요청 URL 파싱 실패 테스트 작성**

```java
@Test
void acceptsMissingOptionalAnnouncementTypeCode() {
    URI uri = URI.create("https://apply.lh.or.kr/selectWrtancInfo.do"
            + "?panId=2015122300020512&ccrCnntSysDsCd=03&uppAisTpCd=06");

    LhNoticeRequest request = LhNoticeRequest.from(uri, "063").orElseThrow();

    assertThat(request.toParams()).containsEntry("PAN_ID", List.of("2015122300020512"));
    assertThat(request.toParams()).doesNotContainKey("AIS_TP_CD");
    assertThat(request.toParams()).containsEntry("SPL_INF_TP_CD", List.of("063"));
}
```

필수 `panId`, `ccrCnntSysDsCd`, `uppAisTpCd` 중 하나가 빠지면 `Optional.empty()`인지 각각 검사한다.

- [ ] **Step 3: aggregate metadata round-trip 실패 테스트 작성**

고정 `Clock.fixed(Instant.parse("2026-08-12T04:00:00Z"), ZoneId.of("Asia/Seoul"))`를 사용해 다음 값을 검사한다.

```java
assertThat(saved.getSourcePanId()).isEqualTo("2015122300020501");
assertThat(saved.getRequestedSupplyInfoTypeCode()).isEqualTo("063");
assertThat(saved.getSourceRespondedAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 12, 31, 44));
assertThat(saved.getFetchedAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 13, 0));
assertThat(saved.isComplexDetailDatasetPresent()).isTrue();
assertThat(saved.getComplexDetails()).singleElement()
        .extracting(LhComplexDetail::getGuidanceText)
        .isEqualTo("공급 안내 원문");
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew test --tests 'test.domain.ingest.lh.*' --tests test.domain.notice.LhNoticeSupplementPersistenceTest`

Expected: 새 이름, resolver, 요청 값 객체, metadata 필드 부재로 컴파일 실패.

- [ ] **Step 5: LH 모델 이름과 테이블 관계 변경**

`git mv` 후 테이블명을 `lh_notice_supplement`, `lh_complex_detail`, FK를 `lh_notice_supplement_id`로 바꾼다. `NoticeSchedule`, `ReceptionPlace`, `NoticeAttachment`의 부모 타입도 `LhNoticeSupplement`로 바꾼다. aggregate 메서드는 `addComplexDetail`, `getComplexDetails`로 이름을 맞춘다.

`LhNoticeSupplement`에 다음 필드를 저장한다.

```java
private String sourcePanId;
private String requestedConnectionSystemDivisionCode;
private String requestedUpperAnnouncementTypeCode;
private String requestedAnnouncementTypeCode;
private String requestedSupplyInfoTypeCode;
private LocalDateTime sourceRespondedAt;
private LocalDateTime fetchedAt;
private boolean complexDetailDatasetPresent;
private String correctionReason;
```

`LhComplexDetail`에는 `@Column(name = "guidance_text", length = 4000) private String guidanceText;`를 추가한다. DTO `ComplexSnapshot`은 `ComplexDetail`로 바꾸고 `@JsonProperty("SPL_INF_GUD_FCTS") String guidanceText`를 추가한다.

주소 매칭에서 사용할 원문은 두 주소 칸을 버리지 않고 조립한다.

```java
public String fullLotAddress() {
    if (lotAddress == null) {
        return lotDetailAddress;
    }
    return lotDetailAddress == null ? lotAddress : lotAddress + " " + lotDetailAddress;
}
```

- [ ] **Step 6: resolver와 요청 값 객체 구현**

```java
public Optional<String> resolve(SupplyType supplyType) {
    if (supplyType == null || supplyType == SupplyType.INTEGRATED_PUBLIC_RENTAL) {
        return Optional.empty();
    }
    return Optional.of(switch (supplyType) {
        case FIVE_YEAR_RENTAL, TEN_YEAR_RENTAL -> "060";
        case FIFTY_YEAR_RENTAL -> "061";
        case NATIONAL_RENTAL, PERMANENT_RENTAL, LONG_TERM_JEONSE -> "062";
        case HAPPY_HOUSE -> "063";
        case PURCHASED_RENTAL, JEONSE_RENTAL -> throw new IllegalArgumentException(
                "건설임대가 아닌 공급유형입니다: " + supplyType);
        case INTEGRATED_PUBLIC_RENTAL -> throw new IllegalStateException("앞에서 제외되어야 합니다.");
    });
}
```

`LhNoticeRequest.toParams()`는 요청 다섯 코드 중 optional AIS만 조건부로 넣고 `PG_SZ=100`, `PAGE=1`을 더한다.

- [ ] **Step 7: 응답 metadata와 dataset 존재 여부를 보존하도록 서비스 수정**

`resHeader.RS_DTTM`은 `DateTimeFormatter.ofPattern("yyyyMMddHHmmss")`로 파싱한다. JSON 최상위 배열 안에 `dsSbd` 키가 존재하는지는 행 개수와 별도로 검사한다. 통합공공임대면 HTTP 호출 전에 `UNSUPPORTED_LH_SUPPLEMENT_TYPE`으로 한 건 제외한다. 행복주택 fixture의 `dsSch.SPL_INF_TP_CD`는 실제 요청과 맞게 `063`으로 바꾼다.

`LhNoticeDetailIngestService`에도 `TransactionTemplate`을 주입하고 `REQUIRES_NEW`로 설정한다. HTTP 호출은 바깥에서 끝낸 뒤 `LhNoticeSupplement`와 자식 저장만 공고 하나의 transaction에서 실행한다. 첫 공고의 child 저장을 강제로 실패시키고 다음 공고는 정상 저장하는 테스트로 `failed=1`, `created=1`, 실패 공고의 supplement·detail·일정·첨부가 모두 0인지 확인한다.

- [ ] **Step 8: 일정·첨부를 정확히 하나의 LH 단지에만 조립하는 테스트와 조회 조립 구현**

```java
@Test
void attachesLabelsOnlyWhenExactlyOneComplexDetailHasTheSameName() {
    LhNoticeSupplement supplement = supplementWithComplexLabels(
            List.of("파주운정 A1", "파주운정 A2"),
            List.of("파주운정 A1", "없는 단지"),
            List.of("파주운정 A2"));

    List<LhSupplementComplexView> views = assembler.assemble(supplement);

    assertThat(view(views, "파주운정 A1").schedules()).hasSize(1);
    assertThat(view(views, "파주운정 A2").attachments()).hasSize(1);
    assertThat(assembler.unassignedSchedules(supplement)).extracting(NoticeSchedule::getComplexLabel)
            .containsExactly("없는 단지");
}

@Test
void leavesLabelUnassignedWhenTwoDetailsHaveTheSameName() {
    LhNoticeSupplement supplement = supplementWithDuplicateComplexLabels("중복 단지");

    assertThat(assembler.assemble(supplement))
            .allSatisfy(view -> assertThat(view.schedules()).isEmpty());
    assertThat(assembler.unassignedSchedules(supplement)).hasSize(1);
}
```

`LhSupplementComplexView`는 `LhComplexDetail complexDetail`, `List<NoticeSchedule> schedules`, `List<NoticeAttachment> attachments` record다. `LhSupplementComplexAssembler`는 단지명 원문에 trim 외 변형을 하지 않는다. `complexLabel`이 같은 supplement의 `LhComplexDetail.complexLabel`과 정확히 한 건 일치할 때만 조회용 view에 일정·첨부를 넣는다. 0건 또는 2건 이상이면 원천 자식은 supplement에 그대로 남기고 단지 view에는 붙이지 않는다. DB FK를 추가하거나 원천 엔티티를 수정하지 않는다.

- [ ] **Step 9: LH 테스트 통과 및 커밋**

Run: `./gradlew test --tests 'test.domain.ingest.lh.*' --tests 'test.domain.notice.Lh*Test'`

Expected: `BUILD SUCCESSFUL`; AIS 누락 URL 호출 가능, 코드 060/061/062/063 선택, 통합공공임대 호출 0회, metadata와 빈/누락 dataset 구분 통과.

```bash
git add src/main/java/test/domain/notice src/main/java/test/domain/ingest src/test/java/test/domain/notice src/test/java/test/domain/ingest/lh
git commit -m "refactor: preserve typed lh notice supplements"
```

## Task 8: `NoticeHousing`과 현재 카탈로그의 PNU 매칭 분리

**Files:**

- Create: `src/main/java/test/domain/match/NoticeHousingCatalogMatchStatus.java`
- Create: `src/main/java/test/domain/match/NoticeHousingCatalogMatch.java`
- Create: `src/main/java/test/domain/match/NoticeHousingCatalogMatchRepository.java`
- Create: `src/main/java/test/domain/match/NoticeHousingCatalogMatchService.java`
- Create: `src/test/java/test/domain/match/NoticeHousingCatalogMatchPersistenceTest.java`
- Create: `src/test/java/test/domain/match/NoticeHousingCatalogMatchServiceTest.java`
- Modify: `src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java`

- [ ] **Step 1: 세 상태와 버전 교체 실패 테스트 작성**

```java
@Test
void matchesOnlyOneExactPnuCandidate() {
    service.match(version.getId(), "catalog-pnu-v1");

    assertThat(results()).extracting(NoticeHousingCatalogMatch::getStatus)
            .containsExactly(MATCHED_PNU, UNMATCHED, AMBIGUOUS);
    assertThat(results()).extracting(NoticeHousingCatalogMatch::getCandidateCount)
            .containsExactly(1, 0, 2);
    assertThat(results().get(0).getHousingComplex()).isNotNull();
    assertThat(results().get(1).getHousingComplex()).isNull();
    assertThat(results().get(2).getHousingComplex()).isNull();
}

@Test
void replacesOnlyTheSameMatcherVersion() {
    service.match(version.getId(), "catalog-pnu-v1");
    service.match(version.getId(), "catalog-pnu-v2");
    service.match(version.getId(), "catalog-pnu-v1");

    assertThat(repository.findAll()).hasSize(noticeHousingCount * 2);
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'test.domain.match.NoticeHousingCatalogMatch*Test'`

Expected: match 모델 부재로 컴파일 실패.

- [ ] **Step 3: entity와 service 구현**

```java
public enum NoticeHousingCatalogMatchStatus {
    MATCHED_PNU,
    UNMATCHED,
    AMBIGUOUS
}
```

entity는 `noticeHousing`, nullable `housingComplex`, `status`, `comparedPnu`, `candidateCount`, `matcherVersion`, `evaluatedAt`을 갖고 `(notice_housing_id, matcher_version)` unique key를 둔다.

```java
@Transactional
public void match(Long noticeVersionId, String matcherVersion) {
    repository.deleteByNoticeHousingNoticeVersionIdAndMatcherVersion(noticeVersionId, matcherVersion);
    for (NoticeHousing housing : housingRepository.findByNoticeVersionIdOrderByDisplayOrder(noticeVersionId)) {
        String pnu = housing.getSuppliedHousing().getPnu();
        List<HousingComplex> candidates = pnu == null
                ? List.of()
                : complexRepository.findAllByAddressPnu(pnu);
        NoticeHousingCatalogMatchStatus status = switch (candidates.size()) {
            case 0 -> NoticeHousingCatalogMatchStatus.UNMATCHED;
            case 1 -> NoticeHousingCatalogMatchStatus.MATCHED_PNU;
            default -> NoticeHousingCatalogMatchStatus.AMBIGUOUS;
        };
        HousingComplex matched = candidates.size() == 1 ? candidates.getFirst() : null;
        repository.save(new NoticeHousingCatalogMatch(
                housing, matched, status, pnu, candidates.size(), matcherVersion, LocalDateTime.now(clock)));
    }
}
```

단지명 fallback은 추가하지 않는다. 기존 `MyHomeNoticeIngestService`에서 카탈로그 repository 의존성과 공고 저장 중 PNU 매칭 로직이 남아 있지 않은지 `rg 'matchComplex|attachComplex|rematchComplexes' src/main/java`로 확인한다.

- [ ] **Step 4: 테스트 통과 및 커밋**

Run: `./gradlew test --tests 'test.domain.match.NoticeHousingCatalogMatch*Test' --tests test.domain.ingest.myhome.MyHomeNoticeIngestServiceTest`

Expected: `BUILD SUCCESSFUL`, 동일 matcher 버전 재실행은 교체되고 다른 버전은 보존.

```bash
git add src/main/java/test/domain/match src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java src/test/java/test/domain/match
git commit -m "feat: derive notice housing catalog matches"
```

## Task 9: `NoticeHousing`과 LH 단지 상세의 주소·세대수 매칭 구현

**Files:**

- Create: `src/main/java/test/domain/match/NoticeHousingLhMatchStatus.java`
- Create: `src/main/java/test/domain/match/NoticeHousingLhMatchEvidence.java`
- Create: `src/main/java/test/domain/match/NoticeHousingLhMatch.java`
- Create: `src/main/java/test/domain/match/NoticeHousingLhMatchRepository.java`
- Create: `src/main/java/test/domain/match/NoticeHousingLhMatchService.java`
- Create: `src/test/java/test/domain/match/MatchingFixtures.java`
- Create: `src/test/java/test/domain/match/NoticeHousingLhMatchPersistenceTest.java`
- Create: `src/test/java/test/domain/match/NoticeHousingLhMatchServiceTest.java`

- [ ] **Step 1: 상태·정규화·양방향 유일성 실패 테스트 작성**

```java
@Test
void normalizesOnlyTrimAndOrdinarySpaces() {
    assertThat(NoticeHousingLhMatchService.normalize(" 경기도 파주시 교하로 20 "))
            .isEqualTo("경기도파주시교하로20");
    assertThat(NoticeHousingLhMatchService.normalize("교하로-20"))
            .isEqualTo("교하로-20");
}

@Test
void matchesPajuRowsByAddressEvenWhenLhOrderDiffers() {
    MatchingFixtures.PajuData data = fixtures.savePajuWithLastTwoLhRowsReversed();

    service.match(data.noticeVersionId(), "lh-address-unit-v1");

    assertThat(matchedPairs()).containsExactlyInAnyOrder(
            tuple("산내마을1단지", "산내마을1단지"),
            tuple("가람마을14단지", "가람마을14단지"),
            tuple("초롱꽃마을3단지", "초롱꽃마을3단지"),
            tuple("물향기마을7단지", "물향기마을7단지"),
            tuple("초롱꽃마을10단지", "초롱꽃마을10단지"),
            tuple("노을빛마을16단지", "노을빛마을16단지"));
}

@Test
void doesNotForceMatchWhenOneNoticeRowHasTwoLhCandidates() {
    Long noticeVersionId = fixtures.saveBucheonOneToTwoCase();

    service.match(noticeVersionId, "lh-address-unit-v1");

    assertThat(resultsFor(noticeVersionId)).extracting(NoticeHousingLhMatch::getStatus)
            .contains(NoticeHousingLhMatchStatus.AMBIGUOUS);
}
```

같은 테스트 클래스에 세대수 동일, 한쪽 null, 세대수 충돌, 후보 0개, 역방향 후보 2개, `dsSbd` 부재, `dsSbd` 빈 배열, LH에만 남은 상세를 각각 fixture로 추가한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'test.domain.match.NoticeHousingLhMatch*Test'`

Expected: match 모델과 서비스 부재로 컴파일 실패.

- [ ] **Step 3: 상태와 evidence 구현**

```java
public enum NoticeHousingLhMatchStatus {
    MATCHED_ADDRESS_AND_UNIT_COUNT,
    MATCHED_ADDRESS_ONLY,
    UNMATCHED,
    AMBIGUOUS,
    CONFLICT_UNIT_COUNT,
    SOURCE_DETAIL_MISSING
}
```

`NoticeHousingLhMatchEvidence`는 embeddable로 `noticeAddressRaw`, `noticeAddressNormalized`, `lhAddressRaw`, `lhAddressNormalized`, `noticeUnitCount`, `lhUnitCount`, `candidateLhComplexDetailIds`, `reason`을 보존한다. 후보 ID 목록은 정렬된 쉼표 문자열로 저장한다.

entity는 `noticeVersion`, nullable `noticeHousing`, nullable `lhComplexDetail`, `status`, `noticeCandidateCount`, `lhCandidateCount`, `matcherVersion`, `evaluatedAt`, embedded `evidence`, `resultOrder`를 갖는다. unique key는 `(notice_version_id, matcher_version, result_order)`다. 조회 조립은 두 `MATCHED_` 상태만 연결로 인정한다.

- [ ] **Step 4: 후보 그래프와 상태 판정 구현**

```java
static String normalize(String value) {
    return value == null ? null : value.strip().replace(" ", "");
}

private boolean isCandidate(NoticeHousing notice, LhComplexDetail detail) {
    String noticeAddress = normalize(notice.getSuppliedHousing().getFullAddress());
    String lhAddress = normalize(detail.fullLotAddress());
    return noticeAddress != null && lhAddress != null && lhAddress.startsWith(noticeAddress);
}
```

`match`는 한 `NoticeVersion` 안에서 bipartite candidate map을 먼저 완성한 뒤 판정한다.

1. supplement가 없으면 아직 호출 전이므로 매칭 결과를 만들지 않는다.
2. supplement가 있고 `complexDetailDatasetPresent=false`면 각 `NoticeHousing`에 `SOURCE_DETAIL_MISSING`을 만든다.
3. notice 후보 차수 0이면 `UNMATCHED`, 어느 쪽 차수든 2 이상이면 `AMBIGUOUS`다.
4. 양쪽 차수가 1일 때만 세대수를 비교한다.
5. 두 세대수가 같으면 `MATCHED_ADDRESS_AND_UNIT_COUNT`, 하나라도 null이면 `MATCHED_ADDRESS_ONLY`, 둘 다 있고 다르면 `CONFLICT_UNIT_COUNT`다.
6. 어떤 notice의 후보도 아니었던 LH detail은 `noticeHousing=null`, `lhComplexDetail=detail`, `UNMATCHED` 결과로 남긴다.
7. 같은 `noticeVersion + matcherVersion` 결과만 한 트랜잭션에서 지우고 재생성한다. 다른 matcher 버전은 보존한다.

- [ ] **Step 5: 모든 match 테스트 통과 및 커밋**

Run: `./gradlew test --tests 'test.domain.match.*'`

Expected: `BUILD SUCCESSFUL`; 파주 6쌍이 응답 순서와 무관하게 연결되고 부천 1:2, 세대수 충돌, 모호성, source detail 누락이 강제 연결되지 않음.

```bash
git add src/main/java/test/domain/match src/test/java/test/domain/match
git commit -m "feat: derive notice housing lh matches"
```

## Task 10: admin 흐름·새 DB 경로·전체 회귀 정리

**Files:**

- Modify: `src/main/java/test/domain/ingest/IngestController.java`
- Modify: `src/main/resources/application.properties`
- Create: `src/test/java/test/domain/ingest/IngestControllerTest.java`

- [ ] **Step 1: controller delegation 실패 테스트 작성**

`@WebMvcTest(IngestController.class)`와 mocked services로 다음 요청을 검사한다.

```java
mockMvc.perform(post("/admin/ingest/complexes")
        .param("pageSize", "500")
        .param("maxPages", "50"))
        .andExpect(status().isOk());
verify(complexIngestService).ingestNationwide(500, 50);

mockMvc.perform(post("/admin/ingest/notices")
        .param("pageSize", "100")
        .param("maxPages", "50"))
        .andExpect(status().isOk());
verify(noticeIngestService).ingest(100, 50);

mockMvc.perform(post("/admin/ingest/matches/catalog").param("noticeVersionId", "7"))
        .andExpect(status().isOk());
verify(catalogMatchService).match(7L, "catalog-pnu-v1");

mockMvc.perform(post("/admin/ingest/matches/lh").param("noticeVersionId", "7"))
        .andExpect(status().isOk());
verify(lhMatchService).match(7L, "lh-address-unit-v1");
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests test.domain.ingest.IngestControllerTest`

Expected: 기존 지역 파라미터와 `/rematch` 구조 때문에 실패.

- [ ] **Step 3: controller를 새 흐름에 맞게 수정**

- `POST /admin/ingest/complexes`: 전국 256개 지역 수집.
- `POST /admin/ingest/notices`: 허용 공급유형 여덟 개 수집.
- `POST /admin/ingest/notice-details`: 저장된 LH 공고 보충.
- `POST /admin/ingest/matches/catalog?noticeVersionId=`: PNU matcher v1.
- `POST /admin/ingest/matches/lh?noticeVersionId=`: 주소·세대수 matcher v1.
- `/complexes/regions`, `/rematch`와 공고 PNU에서 지역을 추출하는 repository query를 제거한다.
- `/probe`는 그대로 둔다.

- [ ] **Step 4: 기존 DB를 보존하는 새 기본 경로 적용**

```properties
# 기존 ./data/domain.mv.db는 구 모델 보존본이다. 이 모델은 별도 빈 DB에서 시작한다.
spring.datasource.url=jdbc:h2:file:./data/domain-construction-rental-v2;MODE=MySQL;AUTO_SERVER=TRUE
```

`ddl-auto=update`는 새 경로에만 적용한다. 기존 DB 파일 삭제·rename·migration 명령은 실행하지 않는다.

- [ ] **Step 5: 금지된 결합과 코드가 남지 않았는지 정적 확인**

Run: `rg 'SupplyLine|NoticeSupplement|NoticeComplexSnapshot|maxSupplyTypeUnitCount|regionCodesFromNotices|rematchComplexes|SPL_INF_TP_CD.*,.*062|suplyTy.*,.*13' src/main src/test`

Expected: 과거 타입명과 mutable rematch는 0건. `062`는 resolver의 국민·영구·장기전세 mapping 및 관련 테스트에서만 등장. `13`은 “호출하지 않는다” assertion에서만 등장.

- [ ] **Step 6: 전체 검증**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

Run: `git diff --check`

Expected: 출력 없음.

Run: `git status --short`

Expected: 이번 구현 파일만 변경되어 있고, 시작 전부터 있던 사용자 파일 `docs/원천-매핑.md`, `docs/원천-API-데이터-사전.md`, `.gitattributes`, `settings.gradle`, `.DS_Store`, `.superpowers/`, `.understand-anything/`은 stage되지 않음.

- [ ] **Step 7: 구현 범위만 커밋**

```bash
git add src/main/java/test/domain/ingest/IngestController.java src/main/resources/application.properties src/test/java/test/domain/ingest/IngestControllerTest.java
git commit -m "feat: wire construction rental ingestion workflow"
```

## Task 11: 완료 전 독립 코드 리뷰와 최종 검증

**Files:**

- Review: all files changed by Tasks 1–10
- Modify: `docs/superpowers/specs/2026-08-12-public-construction-rental-reimplementation-design.md`

- [ ] **Step 1: `superpowers:requesting-code-review`로 승인 설계 대비 리뷰 요청**

리뷰 범위는 다음을 명시한다.

- 8개 공고 요청 코드와 256개 단지 지역 누락 여부
- region/type pagination partial write 가능성
- `HousingComplex → ComplexRentalProgram → UnitType` 소유권
- `RecruitmentNotice → NoticeVersion → NoticeHousing` 불변성
- LH 060/061/062/063 선택과 통합공공임대 skip
- source entity에 cross-source mutable FK가 없는지
- matcher의 양방향 유일성·근거·버전·재실행 안전성
- 단지/공고/LH aggregate rollback 경계

- [ ] **Step 2: 리뷰 지적을 재현 테스트로 먼저 추가하고 최소 수정**

지적이 실제 요구사항 위반일 때만 실패 테스트를 추가하고 수정한다. 범위 밖 리팩터링은 하지 않는다.

- [ ] **Step 3: 리뷰 수정이 있었다면 해당 테스트와 production 파일만 별도 커밋**

`git status --short`와 리뷰 diff를 대조해 실제 수정한 파일만 명시적으로 stage한다. 사용자 소유 변경이나 디렉터리 전체를 stage하지 않는다. 커밋 메시지는 다음을 사용한다.

```bash
git commit -m "test: close construction rental ingestion gaps"
```

- [ ] **Step 4: 최종 검증을 새로 실행**

Run: `./gradlew clean test`

Expected: `BUILD SUCCESSFUL`.

Run: `git diff --check`

Expected: 출력 없음.

- [ ] **Step 5: 검증 근거가 생긴 뒤 승인 설계 문서 상태 기록**

설계 문서 상단 상태를 `구현 완료`로 바꾸고 최종 구현 커밋과 `./gradlew clean test` 성공 결과를 짧게 추가한다. 사용자 소유의 dirty 문서는 수정하지 않는다.

- [ ] **Step 6: 문서만 커밋하고 깨끗한 구현 상태 확인**

```bash
git add docs/superpowers/specs/2026-08-12-public-construction-rental-reimplementation-design.md
git commit -m "docs: record construction rental implementation"
git status --short
```

Expected: 시작 전 사용자 소유 변경은 그대로 보이고, Tasks 1–11의 구현 파일에는 미커밋 변경이 없음.
