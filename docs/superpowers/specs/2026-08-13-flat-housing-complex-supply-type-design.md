# 공급유형별 단지 평탄화 설계

## 상태

승인된 설계. 2026-08-13

## 목표

마이홈 단지 카탈로그를 `물리 단지 → 공급유형별 프로그램 → 주택형`의 3단계로 정규화하지 않는다. 원천이 내려주는 단위인 **단지 × 공급유형 × 주택형**을 그대로 반영해, 공급유형별 `HousingComplex` 행 아래에 `UnitType`을 둔다. 공급유형이 다르면 단지명·주소·PNU·준공정보·기관명처럼 같은 값도 각 행에 중복 저장한다. 공유되는 물리 단지 부모나 공급기관 공통 엔티티는 만들지 않는다.

## 확정된 규칙

### 1. 단지의 식별 단위

같은 물리 단지라도 공급유형이 다르면 서로 다른 `HousingComplex` 행이다.

예:

```text
HousingComplex(경복궁자이, sourceComplexId=123, FIFTY_YEAR_RENTAL)
HousingComplex(경복궁자이, sourceComplexId=123, HAPPY_HOUSE)
```

두 행은 이름·주소·PNU가 같더라도 서로 독립된 행이다. 값은 애플리케이션이나 별도 테이블에서 공유하지 않고 각 행에 중복 저장하며, 세대수·주택형·기준 임대조건도 공급유형별로 별도 관리한다.

카탈로그 자연키는 다음과 같다.

```text
(source_system, source_complex_id, supply_type)
```

원천 표기가 같은 enum 값으로 해석되는 경우를 하나의 공급유형으로 본다. `supplyTypeName`은 원천 문자열을 보존하는 증거 필드다.

### 2. 도메인 관계

```text
HousingComplex ──< UnitType
```

`ComplexRentalProgram`은 제거한다. `UnitType`은 `complex_rental_program_id` 대신 `housing_complex_id`를 직접 가진다.

### 3. HousingComplex 필드

`HousingComplex`에 다음 속성을 둔다.

| 속성 | 의미 | 원천 |
|---|---|---|
| `supplyType` | 공급유형 enum | `suplyTyNm` 해석값 |
| `supplyTypeName` | 공급유형 원문 | `suplyTyNm` |
| `unitCount` | 해당 단지·공급유형 전체 세대수 | `hshldCo` |
| `supplyInstitutionName` | 공급기관 원문 | `insttNm` |

`SupplyType` enum은 입력 검증·정책 필터·공고와 단지의 공급유형 매칭에 사용한다. DB에는 enum을 문자열로 저장하고, 원천 표기는 `supplyTypeName`에 별도로 남긴다.

### 4. 공급기관

`HousingProviderAgency` 엔티티와 테이블을 제거한다. 기관명은 공급유형별 `HousingComplex` 행마다 중복 저장한다.

- `HousingComplex.supplyInstitutionName`: 단지 원천의 `insttNm`
- `NoticeVersion.supplyInstitutionName`: 공고 원천의 `suplyInsttNm`

기관 계층이나 기관 코드가 현재 원천에서 제공되지 않으므로 별도 FK를 만들지 않는다.

## 적재 흐름

### 마이홈 15110581

`MyHomeComplexIngestService`는 `hsmpSn`만으로 행을 묶지 않는다. `(hsmpSn, SupplyType.from(suplyTyNm))` 조합별로 검증·저장한다.

- 같은 공급유형 안의 주소가 갈리면 해당 단지·공급유형을 제외한다.
- 같은 공급유형 안의 `hshldCo`가 갈리면 해당 단지·공급유형을 제외한다.
- 같은 `hsmpSn`에 공급유형이 두 개면 `HousingComplex` 두 건을 만든다.
- 각 공급유형 행의 `hshldCo`는 `HousingComplex.unitCount`에 저장한다.
- 각 원천 주택형은 해당 `HousingComplex`에 직접 연결된 `UnitType`이 된다.

### LH 15059475

`LhLeaseInfoIngestService`는 다음 키로 `HousingComplex` 후보를 찾는다.

```text
ARA_NM + SBD_LGO_NM + AIS_TP_CD_NM + SUM_HSH_CNT
```

후보가 정확히 하나이고 `DDO_AR`가 그 단지의 `UnitType.exclusiveArea`와 정확히 하나 일치할 때만 `HSH_CNT`를 해당 `UnitType.totalUnitCount`에 반영한다. 근사 면적 매칭은 사용하지 않는다.

### 공고 PNU 매칭

공급유형별로 동일 PNU의 `HousingComplex`가 여러 건일 수 있다. 따라서 `NoticeHousingCatalogMatchService`는 다음을 모두 사용한다.

```text
NoticeHousing.suppliedHousing.pnu
NoticeVersion.supplyType
```

PNU와 공급유형이 모두 일치하는 후보만 매칭 후보로 남긴다. 그래도 여러 건이면 `AMBIGUOUS`로 기록하고 연결하지 않는다.

`NoticeHousingUnitTypeMatchService`는 매칭된 `HousingComplex`에서 직접 `UnitType`을 조회한다. 더 이상 `ComplexRentalProgramRepository`를 거치지 않는다.

## 데이터베이스 변경

### 제거

- `complex_rental_program`
- `housing_provider_agency`
- `unit_type.complex_rental_program_id`

### 추가·변경

- `housing_complex.supply_type`
- `housing_complex.supply_type_name`
- `housing_complex.unit_count`
- `housing_complex.supply_institution_name`
- `housing_complex` 유니크 키를 `(source_system, source_complex_id, supply_type)`로 변경
- `unit_type.housing_complex_id` FK 추가
- `unit_type` 자연키를 `(housing_complex_id, type_name, exclusive_area, residential_common_area)`로 변경

이 프로젝트는 Flyway 없이 H2 `ddl-auto=update`를 사용한다. 기존 FK와 공급유형별 중복 단지를 자동 변환하는 것은 안전하지 않으므로, 개발 DB는 백업 파일로 보존한 뒤 새 스키마에서 원천을 다시 적재한다. 기존 데이터 파일을 삭제하지 않는다.

구현 전에 다음 기존 문서를 원천·적재 기준으로 다시 대조한다.

- `docs/원천-API-명세.md`
- `docs/원천-API-데이터-사전.md`
- `docs/원천-매핑.md`
- `docs/테이블-설계-사전.md`
- `docs/도메인-설계.md`

이 문서들에 남아 있는 `ComplexRentalProgram`·`HousingProviderAgency` 관계와 적재 순서·매칭 규칙을 새 모델에 맞게 함께 갱신한다.

권장 재적재 순서는 다음과 같다.

1. 마이홈 단지정보 15110581
2. LH 단지 카탈로그 15059475
3. 마이홈 공고 15108420
4. LH 공고 상세·공급정보 및 매칭

## 테스트 기준

- 같은 `hsmpSn`에 `50년임대`와 `행복주택`이 있으면 `HousingComplex` 두 건이 생성된다.
- 두 단지의 `UnitType`과 `unitCount`가 서로 섞이지 않는다.
- `HousingComplex`와 `UnitType`이 직접 저장·조회된다.
- 기관 엔티티 없이 단지와 공고의 기관명이 각각 문자열로 보존된다.
- 공급유형이 다른 두 `HousingComplex` 행에 주소·PNU·기관명 등 공통 값이 각각 중복 저장된다.
- 15059475의 `SUM_HSH_CNT`가 공급유형별 단지의 `unitCount`와 검증된다.
- 15059475의 `HSH_CNT`가 정확히 매칭된 `UnitType.totalUnitCount`에만 반영된다.
- 동일 PNU에 공급유형별 단지가 있으면 공고 공급유형으로 올바른 단지만 선택된다.
- 면적 불일치·후보 중복·원천행 중복은 기존과 같이 갱신하지 않고 매칭 결과로 남는다.
- 전체 Gradle 테스트가 통과하고 이전 `ComplexRentalProgram`·`HousingProviderAgency` 참조가 남지 않는다.

## 범위 밖

- 공급기관 계층·기관 코드 마스터 도입
- 공급유형 문자열 표준화 규칙의 확장
- 조회 API나 화면 추가
- 기존 운영 DB에 대한 무중단 마이그레이션
