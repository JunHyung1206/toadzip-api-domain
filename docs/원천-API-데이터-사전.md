# 원천 API 데이터 사전

`main` 브랜치가 실제로 호출하는 원천 API의 요청/응답 필드 사전이다. 이 필드가 도메인 엔티티 어디로 가는지, 왜 그렇게 갔는지는 [도메인-설계.md](도메인-설계.md)에 있다.

## 0. 원천 필드명 읽는 법

공공데이터 필드명은 행정표준용어를 약어로 줄인 것이다. 규칙을 알면 처음 보는 필드도 읽힌다.

| 약어 | 뜻 | 약어 | 뜻 | 약어 | 뜻 |
| --- | --- | --- | --- | --- | --- |
| `Nm` | 명(이름) | `Cd` | 코드 | `Sn` | 일련번호 |
| `De` | 일자 | `Co` | 수(개수) | `Ar` | 면적 |
| `Ty`, `Tp` | 유형 | `At` | 여부 | `Se` | 구분 |
| `hsmp` | 주택단지 | `pblanc` | 공고 | `rcrit` | 모집 |
| `suply`, `spl` | 공급 | `instt` | 기관 | `brtc` | 광역시도 |
| `signgu` | 시군구 | `adres` | 주소 | `rn` | 도로명 |
| `hshld` | 세대 | `bass` | 기본 | `gtn` | 보증금 |
| `rnt`, `rent` | 임대 | `mt` | 월 | `chrg` | 료(요금) |
| `przwner` | 당첨자 | `presnatn` | 발표 | `refrn` | 참조 |
| `cnvrs` | 전환 | `lmt` | 한도 | `buld` | 건물 |
| `elvtr` | 승강기 | `instl` | 설치 | `prvuse` | 전용 |
| `cmnuse` | 공용 | `lfsts` | 전세 | `tot`, `sum` | 총 |

LH 원천(`B552555`)은 다른 규칙을 쓴다. `AHFL`=첨부파일, `CMN`=공통, `LCC_NT`=단지, `CRC`=정정, `LGDN`=지번, `SBD`=단지(Subdivision), `DDO`=대지/전용.

## 1. 실제 쓰는 원천

| 데이터 ID | 이름 | 엔드포인트 | 역할 |
| --- | --- | --- | --- |
| [15110581](https://www.data.go.kr/data/15110581/openapi.do) | 마이홈포털 공공임대주택 단지정보 조회 | `apis.data.go.kr/1613000/HWSPR04/rentalHouseGwList` | 단지·공급유형·주택형 카탈로그. 재조회하면 최신 값으로 갱신 |
| [15108420](https://www.data.go.kr/data/15108420/openapi.do) | 마이홈포털 공공주택 모집공고 조회 | `apis.data.go.kr/1613000/HWSPR02/rsdtRcritNtcList` (임대만 사용) | 정정 체인, 공고 기본정보, 대상 주택, 공급수, 이번 공고 임대조건. 공고버전별 불변 스냅샷 |
| [15057999](https://www.data.go.kr/data/15057999/openapi.do) | LH 분양임대공고별 상세정보 조회 | `apis.data.go.kr/B552555/lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1` | 일정·접수처·정정사유·공고 시점 단지정보·첨부파일. 마이홈이 비운 칸을 채우는 보조 원천 |
| [15056765](https://www.data.go.kr/data/15056765/openapi.do) | LH 분양임대공고별 공급정보 조회 | `apis.data.go.kr/B552555/lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1` | 단지·주택형별 이번 회차 공급 세대수. 마이홈·15057999 어디에도 없는 주택형 배분표 |

네 API 모두 계정 단위로 발급되는 같은 인증키(`serviceKey`)를 쓰고 엔드포인트만 다르다. `IngestProperties`가 `lh`/`myhomeNotice`/`myhomeComplex` 세 base-url을 갖고, 15056765는 15057999와 같은 `lhApiClient`(같은 `B552555` 계정)를 그대로 쓴다. [IngestConfig.java:19](../src/main/java/test/domain/ingest/IngestConfig.java)

## 2. 15110581 — 마이홈 단지정보

### 요청

| 이름 | 필수 | 예시 | 설명 |
| --- | --- | --- | --- |
| `serviceKey` | O | - | 인증키 |
| `brtcCode` | O | `43` | 광역시도 코드 (2자리) |
| `signguCode` | O | `750` | 시군구 코드 (3자리) |
| `numOfRows` | O | `200` | 페이지당 결과 수 |
| `pageNo` | O | `1` | 페이지 번호 |

전체 256개 시군구 조합은 `MyHomeRegionCatalog`가 리소스 CSV(`myhome-region-codes.csv`)로 관리하고, 로드 시 정확히 256행인지·중복 코드가 없는지 검증한다. **단지 API에는 공급유형 필터가 없다** — 다른 값을 붙여도 결과가 안 변한다. [MyHomeRegionCatalog.java:20](../src/main/java/test/domain/ingest/myhome/MyHomeRegionCatalog.java)

### 응답 봉투

```
{"response":{"header":{"resultCode":"00","resultMsg":"..."},
             "body":{"totalCount":"...","item":[ ... ]}}}
```

`resultCode`가 `00`(정상) 또는 `03`(자료없음)이면 통과시킨다. 그 외 코드는 예외로 올린다. [OpenApiClient.java:122](../src/main/java/test/domain/ingest/OpenApiClient.java)

### `item` — `MyHomeComplexItem`

원천 한 행은 **단지 × 공급유형 × 주택형**이다. 같은 `hsmpSn`이 여러 행에 걸쳐 나오고 행마다 `suplyTyNm`·`styleNm`·면적이 달라진다.

| 필드 | 타입 | 의미 | 도메인 대응 |
| --- | --- | --- | --- |
| `hsmpSn` | Long | 단지 식별자 | `HousingComplex.sourceComplexId` |
| `insttNm` | String | 공급기관명 | `HousingProviderAgency` |
| `brtcCode`/`brtcNm` | String | 광역시도 코드/명 | `Address.provinceCode/Name` |
| `signguCode`/`signguNm` | String | 시군구 코드/명 | `Address.districtCode/Name` |
| `hsmpNm` | String | 단지명 (매입임대는 지역명) | `HousingComplex.name` |
| `rnAdres` | String | 도로명주소 | `Address.roadAddress` |
| `pnu` | String | 필지고유번호 19자리 | `Address.pnu` (단지 API ↔ 공고 API를 잇는 유일한 안전한 키) |
| `competDe` | String | 준공일자 `yyyyMMdd` | `HousingComplex.completionDate/Year` |
| `hshldCo` | Integer | 세대수 — **단지 단위가 아니라 (단지,공급유형) 단위** | `ComplexRentalProgram.unitCount` |
| `suplyTyNm` | String | 공급유형명 | `SupplyType.from(...)` |
| `styleNm` | String | 주택형명("36", "51A") | `UnitType.typeName` |
| `suplyPrvuseAr`/`suplyCmnuseAr` | BigDecimal | 전용/공용면적 | `UnitType.exclusiveArea/residentialCommonArea` |
| `houseTyNm` | String | 주택유형명 | `HouseType.from(...)` |
| `heatMthdDetailNm` | String | 난방방식 상세 | `HeatingType.from(...)` |
| `buldStleNm` | String | 건물형태(복도식 등) | `HousingComplex.corridorType` |
| `elvtrInstlAtNm` | String | 승강기 설치 여부 | `HousingComplex.elevatorInstallation` |
| `parkngCo` | Integer | 주차 가능 대수 | `HousingComplex.parkingSpaces` |
| `bassRentGtn`/`bassMtRntchrg`/`bassCnvrsGtnLmt` | Long | 기본 임대보증금/월임대료/전환한도 | `UnitType.baseRentTerms` |

전체 필드는 `MyHomeComplexItem` 레코드가 원천을 하나도 버리지 않고 그대로 보관한다. [MyHomeComplexItem.java:14](../src/main/java/test/domain/ingest/myhome/MyHomeComplexItem.java)

## 3. 15108420 — 마이홈 모집공고

### 요청 (임대: `rsdtRcritNtcList`)

| 이름 | 필수 | 예시 | 설명 |
| --- | --- | --- | --- |
| `serviceKey` | O | - | 인증키 |
| `suplyTy` | O(운영상) | `02` | 공급유형 코드. 이 API는 지역 필터가 없어 전국이 오고, 대신 공급유형별로만 걸 수 있다 |
| `numOfRows`/`pageNo` | O | `100`/`1` | 페이지 |

**분양공고(`ltRsdtRcritNtcList`)는 받지 않는다.** 단지 원천(15110581)이 임대주택만 담아서 분양공고의 공급행은 붙을 단지가 아예 없고(실측 63행 중 11행만 붙음), 분양의 보증금·계약금·잔금은 분양대금 분할이라 임대와 같은 칸에 담기면 뜻이 달라진다. [MyHomeNoticeIngestService.java:61](../src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java)

`suplyTy`는 `MyHomeRentalType` 8개 코드를 전부 순회한다.

| 코드 | 명칭 | `SupplyType` |
| --- | --- | --- |
| `01` | 영구임대 | `PERMANENT_RENTAL` |
| `02` | 국민임대 | `NATIONAL_RENTAL` |
| `03` | 50년임대 | `FIFTY_YEAR_RENTAL` |
| `05` | 10년임대 | `TEN_YEAR_RENTAL` |
| `06` | 5년임대 | `FIVE_YEAR_RENTAL` |
| `07` | 장기전세 | `LONG_TERM_JEONSE` |
| `10` | 행복주택 | `HAPPY_HOUSE` |
| `12` | 통합공공임대 | `INTEGRATED_PUBLIC_RENTAL` |

매입임대(`04`/`09`)·전세임대(`08`)는 이 목록에 없다 — 요청 코드부터 건설임대만 돈다. [MyHomeRentalType.java:10](../src/main/java/test/domain/ingest/myhome/MyHomeRentalType.java)

### `item` — `MyHomeNoticeItem`

원천 한 행은 공고가 아니라 **공급행**이다. 같은 `pblancId`가 단지별로 여러 행에 걸쳐 나온다. 행이 2개 이상인 공고 59건을 실측해 필드를 공고 단위/행 단위로 나눴다.

**공고 단위(행마다 안 갈림)**

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `pblancId` | 공고(버전) ID | `NoticeVersion.sourceNoticeId` |
| `sttusNm` | 공고 상태명("일반공고"/"정정공고"/"취소공고") | `NoticeChangeStatus.fromStatusName` |
| `pblancNm` | 공고명 | `NoticeVersion.title` |
| `suplyInsttNm` | 공급기관명 | `NoticeVersion.supplyInstitutionName` |
| `houseTyNm`/`suplyTyNm` | 주택유형/공급유형명 | `NoticeVersion.houseType/supplyType` (+원문) |
| `beforePblancId` | 이전 공고 ID | `NoticeVersion.beforeSourceNoticeId` (체인 연결의 원문 근거) |
| `rcritPblancDe` | 모집공고일자 | `NoticeVersion.publishedAt` |
| `przwnerPresnatnDe` | 당첨자 발표일자 | `NoticeVersion.winnerAnnouncedOn` |
| `refrnc` | 문의처 | `NoticeVersion.contact` |
| `url` | 청약 사이트 원문(LH는 `panId` 포함) | `NoticeVersion.detailUrl` |
| `beginDe`/`endDe` | 모집 시작/종료일 | `NoticeVersion.applicationBeginOn/EndOn` |

**행 단위(공급행마다 다름)**

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `houseSn` | 공급행 일련번호 | `NoticeHousing.houseSn` (자연키) |
| `pcUrl`/`mobileUrl` | 행별 상세 URL | `NoticeHousing.detailUrl/mobileDetailUrl` |
| `hsmpNm`/`fullAdres`/`pnu`/`rnCodeNm`/`refrnLegaldongNm` | 공고가 말하는 대상 주택 주소 | `SuppliedHousing` |
| `brtcNm`/`signguNm` | 광역/시군구명 | `SuppliedHousing.provinceName/districtName` |
| `heatMthdNm` | 난방방식명(원문만, enum 변환 없음) | `SuppliedHousing.heatingTypeName` |
| `totHshldCo` | 전체 세대수(문자열/숫자 혼재) | `SuppliedHousing.totalUnitCount` |
| `sumSuplyCo` | 공급 수(선발 수) | `NoticeHousing.supplyCount` |
| `rentGtn`/`enty`/`surlus`/`mtRntchrg` | 임대보증금/계약금/잔금/월임대료 | `RentTerms` |

`suplyHoCo`(공급호수)와 `prtpay`(중도금)는 받지 않는다 — 건설임대 표본에서 각각 0 또는 상수(70), 중도금은 늘 0으로 나와 의미 있는 값이 아니었다. [MyHomeNoticeItem.java:8](../src/main/java/test/domain/ingest/myhome/MyHomeNoticeItem.java)

## 4. 15057999 — LH 공고 상세

### 어떻게 호출 파라미터를 얻나

별도 지역·기관 코드를 조사할 필요가 없다. 마이홈이 준 `NoticeVersion.detailUrl`이 LH 청약 사이트 주소라 호출에 필요한 값이 쿼리스트링에 그대로 박혀 있다.

```
.../selectWrtancInfo.do?panId=2015122300020476&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10
                              PAN_ID           CCR_CNNT_SYS_DS_CD    UPP_AIS_TP_CD   AIS_TP_CD
```

`LhNoticeRequest.from()`이 이 네 값을 뽑는다. `aisTpCd`는 링크에 없을 수 있어 생략 가능하고, 나머지 셋은 없으면 호출 자체를 하지 않는다. 이 레코드는 15056765 호출에도 그대로 재사용된다(5장). [LhNoticeRequest.java:28](../src/main/java/test/domain/ingest/lh/LhNoticeRequest.java)

`panId`가 있다 = LH 공고라는 뜻이다. 공고버전 68건 중 65건이 LH 건이고 나머지 3건은 지방공사라 이 원천에 없다.

### `SPL_INF_TP_CD` — 공급정보구분코드

링크에는 없어서 마이홈이 준 `SupplyType`으로 직접 계산한다. `LhSupplyInfoTypeResolver`가 담당하고, 15057999·15056765 둘 다 같은 코드를 쓴다.

| `SupplyType` | `SPL_INF_TP_CD` |
| --- | --- |
| `FIVE_YEAR_RENTAL`, `TEN_YEAR_RENTAL` | `060` |
| `FIFTY_YEAR_RENTAL` | `061` |
| `NATIONAL_RENTAL`, `PERMANENT_RENTAL`, `LONG_TERM_JEONSE` | `062` |
| `HAPPY_HOUSE` | `063` |
| `INTEGRATED_PUBLIC_RENTAL` | 미확인 — 호출 자체를 건너뛴다(`UNSUPPORTED_LH_SUPPLEMENT_TYPE`) |

[LhSupplyInfoTypeResolver.java:17](../src/main/java/test/domain/ingest/lh/LhSupplyInfoTypeResolver.java)

### 요청 전체

| 이름 | 필수 | 출처 |
| --- | --- | --- |
| `PAN_ID` | O | `detailUrl`의 `panId` |
| `CCR_CNNT_SYS_DS_CD` | O | `detailUrl`의 `ccrCnntSysDsCd` |
| `UPP_AIS_TP_CD` | O | `detailUrl`의 `uppAisTpCd` |
| `AIS_TP_CD` | X | `detailUrl`의 `aisTpCd`(없을 수 있음) |
| `SPL_INF_TP_CD` | O | 위 표에서 계산 |
| `PG_SZ`/`PAGE` | O | `100`/`1` 고정 |

### 응답 — 배열 안에 데이터셋별 배열

국토부 계열과 봉투 모양이 다르다. 최상위가 배열이고, 그 안에서 키 이름으로 데이터셋을 찾는다(`OpenApiClient.findRows`가 배열 각 원소를 순회하며 키를 검사).

| 데이터셋 | 무엇 | 도메인 대응 |
| --- | --- | --- |
| `resHeader` | 처리결과. `SS_CODE=Y`가 정상 | — |
| `dsEtcInfo` | `CRC_RSN`(정정/취소 사유) | `LhNoticeSupplement.correctionReason` |
| `dsSplScdl` | 서류 제출·계약 일정 | `NoticeSchedule` |
| `dsCtrtPlc` | 현장 접수처 | `ReceptionPlace` |
| `dsSbd` | 공고 시점 단지정보 | `LhComplexDetail` |
| `dsAhflInfo` | 공고문 원문(hwp/PDF) | `NoticeAttachment` |
| `dsSbdAhfl` | 단지 이미지(조감도·배치도·위치도) | `NoticeAttachment` |

`dsSbd`(`LhComplexDetail`) 필드:

| 필드 | 의미 |
| --- | --- |
| `LCC_NT_NM` | 단지명 |
| `LGDN_ADR`/`LGDN_DTL_ADR` | 소재지주소/상세주소 |
| `HSH_CNT` | 총세대수 |
| `HTN_FMLA_DESC` | 난방방식 |
| `DDO_AR` | 전용면적 범위(예: `36.67~51.93`, 범위값이라 그대로 문자열 보관) |
| `MVIN_XPC_YM` | 입주예정월 |
| `SPL_INF_GUD_FCTS` | 단지 안내 |

`dsAhflInfo`/`dsSbdAhfl`는 값 대신 **컬럼 이름을 담은 행**(`dsAhflInfoNm` 등, URL 자리에 "다운로드" 문자열)을 같은 응답에 같이 준다. URL이 `http`로 시작하고 호스트가 있는지로 걸러낸다. [LhNoticeDetailIngestService.java:363](../src/main/java/test/domain/ingest/lh/LhNoticeDetailIngestService.java)

## 5. 15056765 — 주택형별 공급정보

`NoticeHousing`과 `LhComplexDetail` 둘 다 "이 단지에 몇 호"까지만 말하고 **어느 주택형에 몇 호**인지는 안 준다. 이 원천이 그 배분표를 준다. 2026-08-13에 실제 호출해 확인했다.

### 요청

15057999와 파라미터가 완전히 같다 — `LhNoticeRequest`를 그대로 재사용한다.

| 이름 | 필수 | 출처 |
| --- | --- | --- |
| `PAN_ID` | O | `detailUrl`의 `panId` |
| `CCR_CNNT_SYS_DS_CD` | O | `detailUrl`의 `ccrCnntSysDsCd` |
| `UPP_AIS_TP_CD` | O | `detailUrl`의 `uppAisTpCd` |
| `AIS_TP_CD` | X | `detailUrl`의 `aisTpCd` |
| `SPL_INF_TP_CD` | O | 4장의 표에서 계산(15057999와 같은 값) |

### 응답 — `dsList01`

실제 호출 예시(국민임대 공고, 단지 6곳·9행 중 3행):

```json
{"SBD_LGO_NM":"울산구영1BL 국민임대","HTY_NNA":"59㎡","DDO_AR":"59.94",
 "SPL_AR":"82.1224","HSH_CNT":"235","NOW_HSH_CNT":"20"}
```

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `SBD_LGO_NM` | 단지명 | `LhUnitSupply.complexLabel` — PNU 없이 문자열로만 대조한다 |
| `HTY_NNA` | 주택형명("59㎡"처럼 단위가 붙기도 함) | `LhUnitSupply.typeName` — 매칭 근거로는 안 쓴다(아래) |
| `DDO_AR` | 전용면적 | `LhUnitSupply.exclusiveArea` — **매칭의 주 근거** |
| `SPL_AR` | 공급면적 | `LhUnitSupply.supplyArea` |
| `HSH_CNT` | 단지·주택형 총세대수 | `LhUnitSupply.totalUnitCount` |
| `NOW_HSH_CNT` | 이번 회차 공급 세대수 | `LhUnitSupply.suppliedUnitCount` — `NoticeHousing`에 없던 값 |
| `RFE`/`LS_GMY` | 월임대료/임대보증금 | 받지 않음 — 실측에서 전부 "공고문 참조" 문자열이었다 |

`LhUnitSupplyItem`이 이 행을 그대로 옮긴다. [LhUnitSupplyItem.java:17](../src/main/java/test/domain/ingest/lh/LhUnitSupplyItem.java)

### 왜 별도 aggregate인가

15057999와 요청 파라미터가 같지만 **엔드포인트와 응답이 다른 별도 호출**이다. `LhNoticeSupplement`(15057999 저장 대상)는 "저장 후에는 자식을 추가할 수 없다"는 불변식이 있어서, 여기 얹으면 한쪽 호출이 실패했을 때 이미 성공한 다른 쪽 데이터까지 트랜잭션째 날린다. 그래서 `LhUnitSupplyBatch`(1:1 `NoticeVersion`) + `LhUnitSupply`(자식) 로 완전히 분리했다. `LhUnitSupplyIngestService.ingest()`가 `/admin/ingest/unit-supplies`로 독립 실행된다. [LhUnitSupplyBatch.java:22](../src/main/java/test/domain/notice/LhUnitSupplyBatch.java)

### 단지·주택형 매칭 — `NoticeHousingUnitTypeMatchService`

**`SBD_LGO_NM`을 카탈로그 단지명과 대조하면 안 된다.** 두 원천의 명명 체계가 다르다 — 실측 19%만 맞았다([원천-정리.md](원천-정리.md) 3장). 같은 LH 계열인 15057999의 `LCC_NT_NM`과 맞춰야 하고, 그건 실측 290/290이다.

```
LhUnitSupply ─LH단지명─> LhComplexDetail ─주소─> NoticeHousing ─PNU─> HousingComplex ─전용면적─> UnitType
              290/290      (LhMatch 95건)     (CatalogMatch 97건)
```

주택형명(`HTY_NNA`)도 매칭 키로 쓰지 않는다 — "59㎡"처럼 단위가 붙어 카탈로그 `styleNm`("59")과 표기가 갈린다. 두 원천 다 ㎡ 소수로 오는 **전용면적**(허용오차 0.05㎡)이 근거고, 원문은 `sourceTypeName`에 증거로만 남는다.

| 상태 | 의미 | 실측(290행) |
| --- | --- | ---: |
| `MATCHED` | 전용면적 근처 카탈로그 주택형이 정확히 하나 | 202 |
| `AMBIGUOUS` | 둘 이상 — 대개 공급대상만 다른 같은 면적 | 30 |
| `UNMATCHED` | 단지는 확정됐는데 면적이 맞는 주택형이 없음 | 0 |
| `NO_CATALOG_PATH` | 카탈로그까지 가는 길이 끊김. `reason`에 구간이 남는다 | 58 |
| `SOURCE_DATA_MISSING` | 15056765를 아직 안 받았거나 데이터셋이 없었음 | — |

`/admin/ingest/matches/lh` → `/matches/catalog` → `/matches/unit-type` 순서로 돌린다. [NoticeHousingUnitTypeMatchService.java](../src/main/java/test/domain/match/NoticeHousingUnitTypeMatchService.java)

## 6. 검토했으나 안 쓰는 원천

| 데이터 ID | 이름 | 왜 안 쓰나 |
| --- | --- | --- |
| [15058476](https://www.data.go.kr/data/15058476/openapi.do) | 공공임대주택 단지 기본정보(LH, 구버전) | 포털 안내가 "마이홈 단지정보(15110581)로 제공 중"이라고 가리킨다. 같은 데이터의 구버전 |
| [15059475](https://www.data.go.kr/data/15059475/openapi.do) | LH 임대주택 단지별 면적·보증금·월임대료 | 응답에 단지 ID·주소·PNU가 전혀 없다(`ARA_NM`, `SBD_LGO_NM`, 세대수·면적·임대조건뿐). 기존 카탈로그에 안전하게 붙일 키가 없다 |
| 15058530 LH 분양임대공고문 | LH 공고 원문 조회 | 정정/취소 구분, 이전 버전 연결, PNU, 임대조건이 전부 없다. 마이홈 `url`에 `panId`가 박혀 있어 필요하면 그걸로 잇는다 |

15059475는 응답 봉투가 위 네 API와 또 다르다(최상위가 배열, `resHeader`/`dsList`로 키 검색) — 혹시 나중에 다시 검토하게 되면 `OpenApiClient.findRows`의 LH 분기를 그대로 재사용할 수 있다.

## 7. 인증키와 오류 응답

공공데이터포털은 Encoding 키와 Decoding 키를 모두 발급한다. `OpenApiClient.encodeServiceKey()`는 키에 이미 `%`가 있으면(Encoding 키) 그대로 쓰고, 없으면(Decoding 키) 한 번만 인코딩한다 — 이미 인코딩된 키를 다시 인코딩하면 `%`가 `%25`로 바뀌어 인증이 깨진다. [OpenApiClient.java:85](../src/main/java/test/domain/ingest/OpenApiClient.java)

인증 실패는 성공 응답과 전혀 다른 구조로 온다.

```json
{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NULL","returnAuthMsg":"서비스 접근거부","returnReasonCode":"20"}}}
```

| 코드 | 의미 |
| --- | --- |
| `01` | 애플리케이션 오류 |
| `02` | 데이터베이스 오류 |
| `03` | 데이터 없음(국토부 계열은 정상 취급) |
| `10`/`11` | 잘못된 요청 파라미터 / 필수 파라미터 누락 |
| `20` | 인증키 누락·접근 거부·권한 문제 |
| `22`/`23` | 일일/초당 호출 한도 초과 |
| `30`/`31`/`32` | 등록되지 않은/만료된 인증키, 미등록 IP·도메인 |

`20`은 여러 인증 상황에 재사용되므로 코드만 보지 말고 `errMsg`·`returnAuthMsg`도 같이 봐야 한다.

각 원천의 정상 판정 위치도 다르다 — `OpenApiClient.verifyResultCode()`가 이 둘만 처리한다.

| 원천 | 정상 판정 |
| --- | --- |
| 국토부 계열(15110581, 15108420) | `response.header.resultCode` ∈ {`00`, `03`} |
| LH 계열(15057999, 15056765 등, `B552555`) | 배열 안 `resHeader[].SS_CODE` = `Y` |

## 8. Spring 매핑 원칙

- 요청 DTO는 전부 `record` + `@JsonIgnoreProperties(ignoreUnknown = true)`다. 원천이 필드를 늘려도 역직렬화가 깨지지 않는다.
- 원천이 주는 필드는 하나도 버리지 않고 record에 담는다(`MyHomeComplexItem`, `MyHomeNoticeItem`). 안 쓰는 필드라도 나중에 필요해지면 파싱 코드만 추가하면 된다.
- 코드·ID·PNU·날짜·금액 문자열은 원천 DTO에서 `String`/`Long`/`BigDecimal`로 그대로 받고, `SourceValues`가 도메인 타입으로 옮긴다 — 날짜 포맷이 `yyyyMMdd`/`yyyy.MM.dd` 둘 다 오고, 숫자가 문자열로도 숫자로도 오기 때문이다. [SourceValues.java:9](../src/main/java/test/domain/ingest/SourceValues.java)
- LH 원천은 필드명이 `LhNoticeDetail`/`LhUnitSupplyItem` 안에서 `@JsonProperty`로 도메인 이름에 매핑된다 — 원문 약어(`SBD_LGO_NM` 등)를 엔티티까지 끌고 가지 않는다.
- 두 원천의 응답 봉투를 하나의 공통 파서로 합치지 않는다. `OpenApiClient.findRows()`가 JSON Pointer 경로(국토부, `/`로 시작)와 배열-내-키 검색(LH)을 분기해서 처리한다.
- **같은 요청 파라미터라도 엔드포인트가 다르면 별도 aggregate를 쓴다**(15057999 `LhNoticeSupplement` vs 15056765 `LhUnitSupplyBatch`, 5장). 파라미터가 같다고 하나의 저장 트랜잭션으로 묶으면 한쪽 호출 실패가 다른 쪽 성공 데이터까지 날린다.
