# 공공 건설임대 전체 재구현 설계

> 상태: 구현 완료
> 기준일: 2026-08-12
> 범위: 전국 건설임대 단지 카탈로그, 건설임대 모집공고, LH 공고 보충정보, 원천 간 파생 매칭
>
> 구현 완료 커밋: `20e5372655f541060334dd93b46312a0629d7ad`
> 최종 검증: `./gradlew clean test` BUILD SUCCESSFUL (테스트 110건)

## 1. 목표

애플리케이션은 전국의 공공 건설임대 단지와 해당 모집공고만 수집한다. 매입임대·전세임대·공공지원민간임대·공공분양은 도메인에 들어오지 않는다.

수집 단계의 의미는 API가 지원하는 범위에 따라 나눈다.

- 모집공고 API는 공급유형 코드를 요청에 넣어 건설임대 코드만 조회한다.
- 단지 API는 공급유형 요청 필터가 없으므로 전국 원천 행을 받은 직후, 엔티티 생성과 저장 전에 건설임대 행만 남긴다.
- LH 상세 API는 이미 저장된 LH 건설임대 공고만 보충한다.

## 2. 포함·제외 경계

### 포함

| 모집공고 `suplyTy` | 원천 이름 |
| --- | --- |
| `01` | 영구임대 |
| `02` | 국민임대 |
| `03` | 50년임대 |
| `05` | 10년임대 |
| `06` | 5년임대 |
| `07` | 장기전세 |
| `10` | 행복주택 |
| `12` | 통합공공임대 |

단지 API는 코드를 주지 않으므로 위 여덟 이름을 `suplyTyNm` 허용 목록으로 사용한다.

### 제외

- `04`, `09`: 매입임대
- `08`: 전세임대
- `11`: 공공지원민간임대
- `13`: 6년임대. 공식 코드에는 있지만 실제 표본과 LH 상세 분류를 확인한 뒤 별도 변경으로 추가한다.
- 빈 값, 처음 보는 코드·이름
- 공공분양 오퍼레이션 `ltRsdtRcritNtcList`

새로운 값은 자동 허용하지 않는다. 원문과 제외 사유를 남기고 정책을 명시적으로 변경한다.

## 3. 원천별 수집

### 3.1 마이홈 모집공고 `15108420`

`HWSPR02/rsdtRcritNtcList`를 허용 코드 여덟 개에 대해 각각 호출한다.

```text
for suplyTy in [01, 02, 03, 05, 06, 07, 10, 12]
    pageNo=1부터 마지막 페이지까지 조회
    해당 코드의 모든 페이지가 끝난 경우에만 그 결과를 적재
```

- 요청에는 `suplyTy`, `numOfRows`, `pageNo`를 보낸다.
- `lfstsTyAt`은 전세임대 취득방식과 같은 뜻이 아니므로 건설임대 경계에 사용하지 않는다.
- 한 공급유형의 페이지가 `maxPages` 안에 끝나지 않거나 중간 호출이 실패하면 그 공급유형 결과는 부분 적재하지 않는다.
- 성공한 다른 공급유형은 적재할 수 있으며, 실패한 유형은 재시도 대상으로 보고한다.
- 전체 응답은 `(pblancId, houseSn)`으로 중복을 제거한다. 같은 키의 내용이 충돌하면 해당 공고를 `INVALID_SOURCE_ROW`로 제외한다.
- 같은 `pblancId`의 행을 한 공고버전으로 묶고 `beforePblancId`로 정정·취소 체인을 구성한다.

### 3.2 마이홈 단지 `15110581`

`HWSPR04/rentalHouseGwList`는 공급유형 요청 필터가 없다. 공식 지역 코드표의 전국 256개 시군구를 모두 순회한다.

```text
전국 지역 코드 순회
    지역의 모든 페이지 조회
    원천 DTO 변환
    suplyTyNm 허용 목록 필터
    hsmpSn 단위 그룹
    건설 흔적 검증
    도메인 변환·저장
```

- 지역 코드는 공식 XLSX에서 가져온 값을 버전 관리되는 애플리케이션 리소스로 둔다.
- 공고에 등장한 PNU나 지역만으로 수집 범위를 줄이지 않는다.
- 한 지역의 페이지가 `maxPages` 안에 끝나지 않거나 호출에 실패하면 그 지역은 부분 적재하지 않는다.
- 재실행 가능한 upsert로 처리하고, 이번 응답에서 사라진 기존 단지를 자동 삭제하지 않는다.

#### 단지 판정

1. 행의 `suplyTyNm`이 허용 이름이어야 한다.
2. 허용된 행을 `hsmpSn`으로 묶는다.
3. 묶음에 아파트 행이 하나라도 있거나 파싱 가능한 준공일이 하나라도 있으면 건설 단지로 인정한다.
4. 둘 다 없으면 `NOT_CONSTRUCTION_HOUSING`으로 제외한다.
5. 단지 식별자·주소가 없거나 동일 `hsmpSn` 안에서 서로 다른 비어 있지 않은 주소가 충돌하면 단지 전체를 `INVALID_SOURCE_ROW`로 제외한다.
6. 인정한 단지에서도 허용 공급유형의 행만 프로그램과 주택형으로 저장한다.

공급유형 이름만으로 판정하지 않는 이유는 `10년임대`, `장기전세`, `50년임대`로 표시된 매입주택 표본이 있기 때문이다. 아파트 또는 준공일 조건은 이름 허용 목록을 통과한 오탐을 막는다.

### 3.3 LH 공고 상세 `15057999`

LH 상세는 마이홈 `NoticeVersion.detailUrl`에 `panId`가 있는 공고만 호출한다. 공고 URL에서 다음 값을 그대로 전달한다.

- `panId` → `PAN_ID`
- `ccrCnntSysDsCd` → `CCR_CNNT_SYS_DS_CD`
- `uppAisTpCd` → `UPP_AIS_TP_CD`
- `aisTpCd` → `AIS_TP_CD` (있을 때만)

`SPL_INF_TP_CD`는 공고의 공급유형으로 결정한다.

| 공고 공급유형 | `SPL_INF_TP_CD` |
| --- | --- |
| 5년임대, 10년임대 | `060` |
| 50년임대 | `061` |
| 국민임대, 영구임대, 장기전세 | `062` |
| 행복주택 | `063` |

통합공공임대는 공식 LH 상세 분류가 확인되지 않았으므로 마이홈 공고는 보존하되 LH 보충 호출은 명시적으로 제외한다. 성공 코드만 받고 필요한 데이터셋이 빠진 응답을 정상 완료로 오인하지 않도록 요청 코드와 응답 메타데이터를 보존한다.

## 4. 도메인 모델

### 4.1 현재 단지 카탈로그

```text
HousingComplex
└─ ComplexRentalProgram
   └─ UnitType
```

#### `HousingComplex`

- 원천 키: `sourceSystem + hsmpSn`
- 단지명, 주소, PNU, 기관, 준공일, 주택유형, 난방·건물·주차 정보를 소유한다.
- 재조회하면 현재 값으로 갱신되는 카탈로그다.
- 공급유형별 세대수나 임대조건을 직접 소유하지 않는다.

#### `ComplexRentalProgram`

- 한 단지의 한 공급유형이다.
- 자연키: `(housingComplex, supplyTypeName)`
- 공급유형 enum과 원문 이름, 공급유형별 세대수를 소유한다.
- 같은 프로그램 안의 비어 있지 않은 `hshldCo`가 충돌하면 그 프로그램을 저장하지 않고 `INVALID_SOURCE_ROW`로 보고한다.

#### `UnitType`

- 한 프로그램 안의 주택형이다.
- 자연키: `(complexRentalProgram, typeName, exclusiveArea, residentialCommonArea)`
- 기본 보증금·월임대료·전환보증금 한도를 소유한다.
- 공급유형과 공급유형별 세대수를 중복 저장하지 않는다.

### 4.2 모집공고 스냅샷

```text
RecruitmentNotice
└─ NoticeVersion
   └─ NoticeHousing
```

#### `RecruitmentNotice`

- 최초공고와 정정·취소공고를 묶는 내부 루트다.
- 확인 가능한 체인의 최초 `pblancId`를 `sourceRootNoticeId`로 보존한다.
- 관심공고 같은 향후 사용자 기능은 특정 버전이 아니라 이 루트에 연결한다.

#### `NoticeVersion`

- 원천 `pblancId` 하나에 대응하는 불변 스냅샷이다.
- `beforePblancId` 원문과 바로 이전 버전 연결을 함께 보존한다.
- 이전 공고가 응답과 DB 모두에 없으면 새 `RecruitmentNotice`로 시작하고 누락된 이전 ID를 그대로 남긴다.
- 같은 `pblancId`의 원천 내용이 바뀌어도 기존 버전을 덮어쓰지 않고 충돌을 보고한다.

#### `NoticeHousing`

- 마이홈 한 행 `(pblancId, houseSn)`이다.
- 공고 시점의 단지명, 전체주소, PNU, 지역, 난방, 총세대수, 공급수, 최소 임대조건, 행별 URL을 보존한다.
- 주택형별 공급행으로 해석하지 않는다.
- 현재 카탈로그나 LH 단지 상세를 직접 FK로 소유하지 않는다.

### 4.3 LH 불변 보충정보

```text
NoticeVersion
└─ LhNoticeSupplement
   ├─ LhComplexDetail
   ├─ NoticeSchedule
   ├─ ReceptionPlace
   └─ NoticeAttachment
```

#### `LhNoticeSupplement`

- 한 `NoticeVersion`에 최대 하나다.
- `sourcePanId`, `requestedSupplyInfoTypeCode`, `sourceRespondedAt`, `fetchedAt`, 정정사유를 보존한다.
- 정상 빈 응답과 아직 호출하지 않은 상태를 구분한다.

#### `LhComplexDetail`

- `dsSbd` 한 행이며 LH 화면의 단지 탭 하나다.
- 단지명, 소재지·상세주소, 전용면적 범위, 총세대수, 난방, 입주예정월, 공급 안내 원문을 보존한다.
- 카탈로그 `HousingComplex`를 직접 참조하지 않는다.

일정·첨부의 원천 단지명은 그대로 보존한다. 같은 보충 응답 안에서 그 이름이 정확히 하나의 `LhComplexDetail`과 일치할 때만 조회 조립에서 해당 단지에 붙인다.

## 5. 파생 매칭

원천 스냅샷의 FK를 나중에 수정하지 않는다. 매칭은 규칙 버전과 근거를 가진 별도 결과다.

### 5.1 `NoticeHousingCatalogMatch`

- 같은 PNU의 `HousingComplex`가 정확히 하나면 `MATCHED_PNU`다.
- 후보가 없으면 `UNMATCHED`, 둘 이상이면 `AMBIGUOUS`다.
- 단지명이나 응답 순서를 fallback으로 쓰지 않는다.
- 비교한 PNU, 후보 수, matcher 버전, 평가 시각을 남긴다.

### 5.2 `NoticeHousingLhMatch`

같은 `NoticeVersion` 안에서만 `NoticeHousing`과 `LhComplexDetail`을 비교한다.

1. 양쪽 주소를 trim하고 일반 공백을 제거한다.
2. LH 소재지주소가 마이홈 전체주소로 시작하는 행을 후보로 삼는다.
3. 후보 그래프가 양쪽 모두 유일한 1:1일 때만 세대수를 검증한다.
4. 양쪽 세대수가 있고 같으면 `MATCHED_ADDRESS_AND_UNIT_COUNT`다.
5. 한쪽 또는 양쪽 세대수가 없으면 `MATCHED_ADDRESS_ONLY`다.
6. 양쪽 세대수가 있고 다르면 `CONFLICT_UNIT_COUNT`이며 연결하지 않는다.
7. 후보가 없으면 `UNMATCHED`, 복수 후보면 `AMBIGUOUS`다.
8. LH 단지 데이터셋 자체가 없으면 `SOURCE_DETAIL_MISSING`이다.

응답 순서, 단지명 유사도, 행 개수는 연결 근거로 사용하지 않는다. 매칭 결과에는 원문 주소, 정규화 주소, 양쪽 세대수, 후보 수, matcher 버전과 평가 시각을 남긴다.

## 6. 적재 순서와 트랜잭션

```text
1. 허용 코드별 모집공고 수집
2. 전국 지역별 단지 수집
3. NoticeHousing ↔ HousingComplex 매칭
4. LH 공고 상세 수집
5. NoticeHousing ↔ LhComplexDetail 매칭
```

- HTTP 호출은 DB 트랜잭션 밖에서 수행한다.
- 공고는 `pblancId` 하나를 한 트랜잭션으로 저장한다.
- 단지는 `hsmpSn` 하나와 그 프로그램·주택형을 한 트랜잭션으로 저장한다.
- LH 보충정보는 공고 하나와 모든 자식을 한 트랜잭션으로 저장한다.
- 매칭은 `NoticeVersion` 단위로 기존 같은 matcher 버전 결과를 교체할 수 있다. 원천 엔티티는 변경하지 않는다.
- 개별 단지·공고·보충정보 실패가 다음 항목의 수집을 막지 않는다.

## 7. 결과와 오류

`IngestReport`는 생성·갱신·변경 없음·실패와 의도적 제외를 구분한다.

최소 제외 사유:

- `UNKNOWN_SUPPLY_TYPE`
- `UNSUPPORTED_SUPPLY_TYPE`
- `NOT_CONSTRUCTION_HOUSING`
- `MISSING_IDENTITY`
- `INVALID_SOURCE_ROW`
- `UNSUPPORTED_LH_SUPPLEMENT_TYPE`

인증 실패, 타임아웃, 비정상 응답, 끝나지 않은 페이지 조회는 `rejected`가 아니라 `failed`다. 로그에는 원천, 가능한 원천 ID, 공급유형 코드·원문, 지역 또는 공고 ID와 사유를 남기되 인증키가 포함된 URL은 남기지 않는다.

## 8. 기존 데이터 처리

이번 변경은 테이블 소유관계와 자연키를 바꾸므로 H2 `ddl-auto=update`로 안전하게 변환할 수 없다.

- 구현이 기존 `data/domain.mv.db`를 삭제하거나 덮어쓰지 않는다.
- 기존 파일은 보존한다.
- 새 모델 검증은 빈 DB 또는 별도 DB 경로에서 수행한다.
- 운영 데이터 마이그레이션은 이번 범위 밖이며, 새 모델이 안정된 뒤 별도 작업으로 다룬다.

## 9. 구현하지 않는 것

- 6년임대
- 공공분양, 매입임대, 전세임대, 공공지원민간임대
- 안정적인 ID가 없는 `15059475` 자동 결합
- PDF/HWP에서만 얻을 수 있는 `SupplyAllocation`, `SelectionTier`의 추측 구현
- 단지명 유사도, 지오코딩, 응답 순서를 이용한 fallback 매칭
- 수집 결과에서 사라진 기존 카탈로그 행의 자동 삭제
- 기존 H2 파일 자동 마이그레이션

## 10. 검증 기준

- 모집공고 API가 허용 코드 여덟 개만 요청하고 `13`을 호출하지 않는다.
- 공고별 모든 페이지를 모은 뒤 저장하며 잘린 공급유형은 부분 저장하지 않는다.
- 공식 전국 지역 코드가 중복 없이 모두 순회된다.
- 단지 API 응답의 비허용 이름은 엔티티 생성 전에 제외된다.
- 허용 이름이어도 아파트도 아니고 준공일도 없는 단지는 제외된다.
- 한 단지의 여러 공급유형은 별도 `ComplexRentalProgram`이 되고 주택형은 해당 프로그램 아래에 저장된다.
- 공고가 없는 건설임대 단지도 전국 카탈로그에 남는다.
- 정정공고는 기존 버전을 수정하지 않고 같은 `RecruitmentNotice`의 새 버전이 된다.
- `NoticeHousing`은 카탈로그와 LH 상세에 직접 FK를 갖지 않는다.
- LH 요청 코드가 공급유형에 맞게 `060`·`061`·`062`·`063`으로 선택된다.
- 통합공공임대의 마이홈 공고는 보존되고 확인되지 않은 LH 상세 호출만 제외된다.
- 파주처럼 양쪽 행 순서가 달라도 주소 근거로 연결된다.
- 부천 A5·A6처럼 행 개수가 다르면 순서나 개수로 강제 연결하지 않는다.
- 세대수 충돌, 모호한 후보, 매칭 실패가 근거와 상태로 남는다.
- 각 aggregate 저장 중 실패하면 그 aggregate의 부분 데이터가 남지 않는다.
- 같은 원천과 같은 matcher 버전을 재실행해도 중복 행이 생기지 않는다.
- 전체 `./gradlew test`가 통과한다.
