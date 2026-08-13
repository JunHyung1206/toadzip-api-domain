# Table Design and Source API Reference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 실제 JPA 모델·H2 스키마·적재 코드를 기준으로 17개 테이블 설계 사전과 4개 원천 API 명세를 신규 개발자용 계층형 문서로 작성한다.

**Architecture:** `테이블-설계-사전.md`는 저장 모델과 설계 이유를, `원천-API-명세.md`는 외부 요청·응답 계약과 저장 매핑을 책임진다. 두 문서는 상호 링크하되 설명을 길게 중복하지 않으며, 원천 사실·불변 스냅샷·파생 매칭을 명확히 구분한다.

**Tech Stack:** Markdown, Mermaid ER diagram, Spring Data JPA entities, H2 `INFORMATION_SCHEMA`, Java records/Jackson source DTOs, `rg`, Gradle tests

---

## File map

- Create: `docs/테이블-설계-사전.md` — 17개 테이블의 역할·관계·설계 이유·전체 컬럼 사전
- Create: `docs/원천-API-명세.md` — 실제 호출 API 4개의 요청·응답·필드·저장 매핑과 제외 원천
- Reference: `docs/superpowers/specs/2026-08-13-table-and-source-api-reference-design.md` — 승인된 범위와 검증 기준
- Reference: `docs/도메인-설계.md` — 기존 도메인 판단과 실측 근거
- Reference: `docs/원천-API-데이터-사전.md` — 기존 원천 필드 조사 결과
- Reference: `src/main/java/test/domain/housing/*.java` — 카탈로그 엔티티·값 객체
- Reference: `src/main/java/test/domain/notice/*.java` — 공고·LH 보충 엔티티·값 객체
- Reference: `src/main/java/test/domain/match/*.java` — 파생 매칭 엔티티·상태
- Reference: `src/main/java/test/domain/ingest/**/*.java` — 엔드포인트·요청 파라미터·응답 DTO·적재 규칙
- Reference: `src/main/resources/application.properties` — 실제 base URL과 인증 설정

### Task 1: 실제 스키마와 원천 호출 목록을 기준선으로 고정

**Files:**
- Read: `src/main/java/test/domain/**/*.java`
- Read: `src/main/resources/application.properties`
- Read: `docs/도메인-설계.md`
- Read: `docs/원천-API-데이터-사전.md`

- [ ] **Step 1: 작업 전 상태를 확인한다**

Run:

```bash
git status --short
git log --oneline -5
```

Expected: 승인된 명세 커밋 `21b4919`가 최근 이력에 있고, 기존 미추적 파일은 작업 범위에서 제외한다.

- [ ] **Step 2: 실제 H2 테이블 17개를 조회한다**

Run:

```bash
java -cp /Users/pjh/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.3.232/4fcc05d966ccdb2812ae8b9a718f69226c0cf4e2/h2-2.3.232.jar \
  org.h2.tools.Shell \
  -url 'jdbc:h2:file:./data/domain-construction-rental-v2;MODE=MySQL;AUTO_SERVER=TRUE' \
  -user sa -password '' \
  -sql "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME"
```

Expected: `PUBLIC` 스키마의 도메인·파생 테이블이 정확히 17개 출력된다.

- [ ] **Step 3: 컬럼·PK·FK·유니크 제약을 조회해 엔티티와 대조한다**

Run:

```bash
java -cp /Users/pjh/.gradle/caches/modules-2/files-2.1/com.h2database/h2/2.3.232/4fcc05d966ccdb2812ae8b9a718f69226c0cf4e2/h2-2.3.232.jar \
  org.h2.tools.Shell \
  -url 'jdbc:h2:file:./data/domain-construction-rental-v2;MODE=MySQL;AUTO_SERVER=TRUE' \
  -user sa -password '' \
  -sql "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME,ORDINAL_POSITION; SELECT TABLE_NAME,CONSTRAINT_NAME,CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME,CONSTRAINT_TYPE,CONSTRAINT_NAME"
```

Expected: 모든 영속 컬럼의 실제 타입과 NULL 여부, PK/FK/UNIQUE 제약이 출력된다.

- [ ] **Step 4: 자동 적재 코드가 호출하는 엔드포인트를 확정한다**

Run:

```bash
rg -n 'PATH\s*=|RENTAL_PATH\s*=|getRaw\(' src/main/java/test/domain/ingest
```

Expected: 자동 적재 경로는 아래 네 개다.

```text
rentalHouseGwList
rsdtRcritNtcList
lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1
lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1
```

`IngestController.probe()`는 임의 경로를 수동 조회하는 개발 도구이므로 자동 적재 원천 수에서 제외한다.

### Task 2: 테이블 문서의 전체 구조와 카탈로그 영역 작성

**Files:**
- Create: `docs/테이블-설계-사전.md`
- Read: `src/main/java/test/domain/housing/HousingProviderAgency.java`
- Read: `src/main/java/test/domain/housing/HousingComplex.java`
- Read: `src/main/java/test/domain/housing/ComplexRentalProgram.java`
- Read: `src/main/java/test/domain/housing/UnitType.java`
- Read: `src/main/java/test/domain/housing/Address.java`
- Read: `src/main/java/test/domain/housing/CatalogDetails.java`
- Read: `src/main/java/test/domain/housing/BaseRentTerms.java`

- [ ] **Step 1: 문서 머리말과 읽는 순서를 작성한다**

`docs/테이블-설계-사전.md`에 기준 시점, 17개 테이블 범위, 네 데이터 층, 원천 사실과 파생 매칭의 차이를 적고 승인 명세와 `원천-API-명세.md` 링크를 둔다.

- [ ] **Step 2: 전체 ER 다이어그램과 17개 관계 요약표를 작성한다**

Mermaid `erDiagram`에 카탈로그, 공고 버전, LH 보충, LH 공급정보, 파생 매칭의 모든 부모·자식 관계를 넣는다. 관계 요약표에는 DB 테이블명, Java 엔티티, 행 하나의 의미, 분류, 부모를 적는다.

- [ ] **Step 3: 카탈로그 네 테이블의 설계 이유와 전체 컬럼을 작성한다**

아래 순서로 동일한 서식을 사용한다.

```text
housing_provider_agency
housing_complex
complex_rental_program
unit_type
```

각 절에는 행의 알갱이, 분리 이유, 생명주기, 관계, 제약, 전체 컬럼 표, 주의점을 넣는다. 다음 판단을 빠뜨리지 않는다.

```text
housing_provider_agency: 기관명이 반복되므로 참조 엔티티로 분리
housing_complex: hsmpSn이 원천 식별자이며 주소는 Address로 내장
complex_rental_program: hshldCo의 알갱이가 단지가 아니라 단지×공급유형
unit_type: 자연키는 프로그램×주택형명×전용면적×공용면적
```

- [ ] **Step 4: 카탈로그 값 객체가 펼쳐지는 컬럼을 별도 표로 작성한다**

다음을 실제 소유 테이블 컬럼과 연결한다.

```text
Address -> housing_complex의 지역 코드·지역명·도로명주소·PNU
CatalogDetails -> housing_complex의 준공·난방·주차·복도·승강기·주택유형
BaseRentTerms -> unit_type의 기본 보증금·월임대료·전환보증금 한도
```

### Task 3: 공고 이력과 LH 보충 원천 테이블 작성

**Files:**
- Modify: `docs/테이블-설계-사전.md`
- Read: `src/main/java/test/domain/notice/*.java`

- [ ] **Step 1: 공고 이력 세 테이블을 작성한다**

다음 순서와 핵심 판단을 사용한다.

```text
recruitment_notice: 원공고와 모든 정정·취소 버전을 묶는 루트
notice_version: pblancId 하나의 불변 스냅샷, 이전 버전 self FK
notice_housing: 마이홈 응답의 공급행 한 줄, 카탈로그 FK를 직접 두지 않음
```

`NoticeSnapshot`, `SuppliedHousing`, `RentTerms`가 어느 컬럼으로 펼쳐지는지 컬럼 표와 값 객체 절 양쪽에서 연결한다.

- [ ] **Step 2: LH 공고 상세 루트와 네 자식 테이블을 작성한다**

```text
lh_notice_supplement
notice_schedule
reception_place
lh_complex_detail
notice_attachment
```

`lh_notice_supplement`가 15057999 호출 한 번의 루트이고, 자식들이 서로 다른 데이터셋·다건·표시 순서를 가지므로 별도 테이블임을 설명한다. `LhComplexDetail`은 현재 카탈로그가 아니라 공고 시점 스냅샷임을 명시한다.

- [ ] **Step 3: LH 공급정보 두 테이블을 작성한다**

```text
lh_unit_supply_batch: NoticeVersion당 15056765 호출 결과 한 묶음
lh_unit_supply: 단지×주택형별 이번 공고 공급 세대수 한 행
```

15057999와 요청 파라미터가 같아도 호출 실패·재시도 수명이 다르므로 별도 aggregate로 둔 이유를 적는다.

### Task 4: 파생 매칭·상태·조회 경로 작성 후 테이블 문서 검증

**Files:**
- Modify: `docs/테이블-설계-사전.md`
- Read: `src/main/java/test/domain/match/*.java`

- [ ] **Step 1: 파생 매칭 세 테이블을 작성한다**

```text
notice_housing_catalog_match: NoticeHousing PNU -> HousingComplex
notice_housing_lh_match: NoticeHousing 주소·세대수 -> LhComplexDetail
notice_housing_unit_type_match: LhUnitSupply -> LH 이름 -> 주소 -> PNU -> 전용면적 -> UnitType
```

각 테이블에 `matcher_version`, 판정 시각, 후보 수, 상태, 원문 증거, 실패 사유를 남기는 이유와 재계산 정책을 적는다.

- [ ] **Step 2: enum과 상태값 사전을 작성한다**

`SourceSystem`, `SupplyType`, `HouseType`, `HeatingType`, `NoticeChangeStatus`와 세 매칭 상태 enum을 표로 정리한다. 원문과 enum을 함께 저장하는 경우와 파이프라인 판정만 저장하는 경우를 구분한다.

- [ ] **Step 3: 주요 조회 경로와 실제 적재 건수를 작성한다**

다음 두 경로를 보여준다.

```text
공고 -> NoticeVersion -> LhUnitSupplyBatch -> LhUnitSupply
LhUnitSupply -> UnitTypeMatch -> UnitType -> BaseRentTerms
```

현재 DB에서 단지 2,969개, 카탈로그 주택형 11,170개, 공고 버전 56개, 공급행 290개, 확정 주택형 매칭 202개임을 다시 조회해 확인 시각과 함께 적는다.

- [ ] **Step 4: 17개 테이블 이름의 문서 포함 여부를 검사한다**

Run:

```bash
for table in complex_rental_program housing_complex housing_provider_agency lh_complex_detail lh_notice_supplement lh_unit_supply lh_unit_supply_batch notice_attachment notice_housing notice_housing_catalog_match notice_housing_lh_match notice_housing_unit_type_match notice_schedule notice_version reception_place recruitment_notice unit_type; do
  rg -q "\\`$table\\`" docs/테이블-설계-사전.md || exit 1
done
```

Expected: exit code 0.

- [ ] **Step 5: Markdown과 전체 테스트를 검증한다**

Run:

```bash
git diff --check -- docs/테이블-설계-사전.md
./gradlew test --rerun-tasks
```

Expected: whitespace error가 없고 `BUILD SUCCESSFUL`.

- [ ] **Step 6: 테이블 문서를 커밋한다**

```bash
git add docs/테이블-설계-사전.md
git commit -m "docs: add table design reference"
```

### Task 5: API 문서 공통부와 마이홈 두 API 작성

**Files:**
- Create: `docs/원천-API-명세.md`
- Read: `src/main/java/test/domain/ingest/OpenApiClient.java`
- Read: `src/main/java/test/domain/ingest/IngestConfig.java`
- Read: `src/main/java/test/domain/ingest/IngestProperties.java`
- Read: `src/main/java/test/domain/ingest/myhome/*.java`

- [ ] **Step 1: API 문서 머리말과 네 원천 요약을 작성한다**

다음 네 원천만 자동 적재 대상으로 표시한다.

```text
15110581 / HWSPR04/rentalHouseGwList
15108420 / HWSPR02/rsdtRcritNtcList
15057999 / lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1
15056765 / lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1
```

API 수와 정보 종류 수가 다른 이유로 15057999의 다중 데이터셋 구조를 먼저 안내한다.

- [ ] **Step 2: 공통 인증·HTTP·오류 처리를 작성한다**

`serviceKey`의 Encoding/Decoding 키 처리, GET 요청, JSON 응답, 마이홈 `resultCode`와 LH `SS_CODE`, 자료 없음, 인증 실패 봉투, 호출 실패 시 공고별 독립 실패 처리를 설명한다.

- [ ] **Step 3: 15110581 단지정보 명세를 작성한다**

다음을 포함한다.

```text
GET /1613000/HWSPR04/rentalHouseGwList
요청: serviceKey, brtcCode, signguCode, numOfRows, pageNo
알갱이: 단지 × 공급유형 × 주택형
순회: 공식 256개 시군구
응답: response.header + response.body.totalCount/item
필드 출처: MyHomeComplexItem의 모든 record component
```

각 응답 필드에 타입, 의미, 예시, 저장 위치, 변환·필터 여부를 적는다.

- [ ] **Step 4: 15108420 임대 모집공고 명세를 작성한다**

다음을 포함한다.

```text
GET /1613000/HWSPR02/rsdtRcritNtcList
요청: serviceKey, suplyTy, numOfRows, pageNo
suplyTy: 01,02,03,05,06,07,10,12
알갱이: 공고버전 × 공급행
응답: response.header + response.body.totalCount/item
필드 출처: MyHomeNoticeItem의 모든 record component
```

공고 공통 필드와 공급행별 필드를 나누고, 분양 `ltRsdtRcritNtcList`·매입·전세를 제외하는 이유를 적는다.

### Task 6: LH 두 API·연결 키·제외 원천 작성 후 API 문서 검증

**Files:**
- Modify: `docs/원천-API-명세.md`
- Read: `src/main/java/test/domain/ingest/lh/*.java`

- [ ] **Step 1: LH 공통 요청 파라미터 도출 과정을 작성한다**

`NoticeVersion.detailUrl`의 `panId`, `ccrCnntSysDsCd`, `uppAisTpCd`, 선택적 `aisTpCd`를 대문자 API 파라미터로 옮기고, `SupplyType`에서 `SPL_INF_TP_CD`를 계산하는 표를 작성한다. `PG_SZ=100`, `PAGE=1` 고정값도 명시한다.

- [ ] **Step 2: 15057999 LH 공고 상세 명세를 작성한다**

최상위 배열 안의 다음 데이터셋을 각각 별도 표로 작성한다.

```text
resHeader
dsEtcInfo
dsSplScdl
dsCtrtPlc
dsSbd
dsAhflInfo
dsSbdAhfl
```

필드 목록은 `LhNoticeDetail`의 모든 `@JsonProperty`와 적재 서비스가 직접 읽는 `RS_DTTM`까지 포함한다. 첨부파일 컬럼명 설명행을 URL 검증으로 제외하는 규칙도 적는다.

- [ ] **Step 3: 15056765 LH 공급정보 명세를 작성한다**

`dsList01`의 `SBD_LGO_NM`, `HTY_NNA`, `DDO_AR`, `SPL_AR`, `HSH_CNT`, `NOW_HSH_CNT`, `RFE`, `LS_GMY`를 모두 적는다. `RFE`, `LS_GMY`가 실측상 “공고문 참조”라 저장하지 않는다는 사실과 나머지 필드의 저장 위치를 명시한다.

- [ ] **Step 4: 원천 간 연결 키 지도를 작성한다**

다음 흐름을 하나의 표와 Mermaid flowchart로 작성한다.

```text
pblancId/beforePblancId -> 공고 정정 체인
detailUrl의 panId -> 마이홈 공고와 LH 호출 연결
PNU -> NoticeHousing과 HousingComplex 연결
LH 단지명 -> LhUnitSupply와 LhComplexDetail 연결
주소 -> LhComplexDetail과 NoticeHousing 연결
전용면적 ±0.05㎡ -> LhUnitSupply와 UnitType 연결
```

- [ ] **Step 5: 적재 순서·재호출·부분 실패 정책을 작성한다**

다음 순서를 명시한다.

```text
complexes -> notices -> notice-details / unit-supplies -> matches/catalog -> matches/lh -> matches/unit-type
```

카탈로그는 갱신되고 공고·LH 배치는 불변이며, matcherVersion 단위로 파생 결과를 지우고 다시 계산한다는 차이를 적는다.

- [ ] **Step 6: 검토했지만 사용하지 않는 원천과 비저장 필드를 작성한다**

```text
15058476: 15110581로 대체된 구버전
15059475: 단지 ID·주소·PNU 부재
15058530: 정정 체인·PNU·임대조건 부재
ltRsdtRcritNtcList: 분양은 건설임대 모델 경계 밖
```

개발용 `probe`가 임의 경로를 호출할 수 있어도 자동 적재 원천으로 세지 않는다는 점을 적는다.

- [ ] **Step 7: 네 엔드포인트와 주요 데이터셋의 포함 여부를 검사한다**

Run:

```bash
for token in rentalHouseGwList rsdtRcritNtcList lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1 lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1 dsEtcInfo dsSplScdl dsCtrtPlc dsSbd dsAhflInfo dsSbdAhfl dsList01; do
  rg -q "$token" docs/원천-API-명세.md || exit 1
done
```

Expected: exit code 0.

- [ ] **Step 8: 두 문서의 자체 모순과 자리표시자를 검사한다**

Run:

```bash
rg -n 'TODO|TBD|PLACEHOLDER|추후 결정|미정' docs/테이블-설계-사전.md docs/원천-API-명세.md
git diff --check -- docs/테이블-설계-사전.md docs/원천-API-명세.md
```

Expected: 첫 명령은 결과 없음, 두 번째 명령은 exit code 0.

- [ ] **Step 9: 전체 테스트를 새로 실행한다**

Run:

```bash
./gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: API 문서를 커밋한다**

```bash
git add docs/원천-API-명세.md
git commit -m "docs: add source API reference"
```

### Task 7: 최종 교차 검토

**Files:**
- Verify: `docs/테이블-설계-사전.md`
- Verify: `docs/원천-API-명세.md`
- Verify: `docs/superpowers/specs/2026-08-13-table-and-source-api-reference-design.md`

- [ ] **Step 1: 승인된 명세 항목을 두 문서에서 역추적한다**

다음 항목마다 실제 문서 절을 하나씩 확인한다.

```text
17개 테이블 전체 컬럼
PK/FK/UNIQUE와 설계 이유
값 객체의 실제 컬럼
API 4개의 요청·응답 전체 형태
원천 DTO의 수신 필드
API 간 키 연결
비저장 필드와 제외 원천
현재 적재 수치와 확인 한계
```

- [ ] **Step 2: 두 문서의 상호 링크와 로컬 링크를 검사한다**

Run:

```bash
rg -n '\]\([^)]*\.md(?:#[^)]*)?\)' docs/테이블-설계-사전.md docs/원천-API-명세.md
```

Expected: 두 문서가 서로 연결되고, 참조하는 로컬 Markdown 파일이 모두 존재한다.

- [ ] **Step 3: 최종 변경 범위를 확인한다**

Run:

```bash
git status --short
git log --oneline -5
```

Expected: 이번 작업의 문서 두 개는 각각 커밋되어 있고, 기존 미추적 파일에는 손대지 않았다.
