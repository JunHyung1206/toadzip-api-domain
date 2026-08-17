# 원천 API 데이터 사전

활용 승인을 받은 원천 API 8개의 요청/응답 필드 사전이다. 실제 도메인 적재에 사용하는 5개와 검토 후 제외한 3개를 함께 기록한다. 이 필드가 도메인 엔티티 어디로 가는지, 왜 그렇게 갔는지는 [도메인-설계.md](도메인-설계.md)에 있다.

여기서 "전체 응답 필드"는 현재 프로젝트의 건설임대 수집 경로에서 공식 명세와 실제 응답으로 확인한 필드를 뜻한다. LH API는 같은 엔드포인트가 토지·분양·상가 등 다른 공급정보구분코드에서 전혀 다른 데이터셋을 반환하므로, 프로젝트 경계 밖 데이터셋은 싣지 않는다.

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

## 1. 활용 승인 원천과 사용 여부

| 데이터 ID | 신청일 / 만료예정일 | 사용 여부 | 사용 도메인 또는 판단 |
| --- | --- | --- | --- |
| [15110581](https://www.data.go.kr/data/15110581/openapi.do) | 2026-08-10 / 2028-08-10 | **사용** | `HousingComplex`, `Address`, `UnitType` 생성 |
| [15108420](https://www.data.go.kr/data/15108420/openapi.do) | 2026-08-10 / 2028-08-10 | **사용** | `Notice`, 단지 단위 `NoticeSupply` 생성 |
| [15057999](https://www.data.go.kr/data/15057999/openapi.do) | 2026-08-10 / 2028-08-10 | **사용** | `Notice`, `NoticeSupply`, `NoticeSchedule`, `ReceptionPlace`, `NoticeAttachment` 보강 |
| [15056765](https://www.data.go.kr/data/15056765/openapi.do) | 2026-08-13 / 2028-08-13 | **사용** | `NoticeSupply`를 주택형 단위로 재구성 |
| [15059475](https://www.data.go.kr/data/15059475/openapi.do) | 2026-08-12 / 2028-08-12 | **사용** | `UnitType.totalUnitCount`, `UnitType.baseRentTerms` 보강 |
| [15108378](https://www.data.go.kr/data/15108378/openapi.do) | 2026-08-10 / 2028-08-10 | 미사용 | 예비입주 대기현황은 현재 카탈로그·공고 도메인과 수명이 달라 제외 |
| [15058530](https://www.data.go.kr/data/15058530/openapi.do) | 2026-08-10 / 2028-08-10 | 미사용 | 공고 목록은 15108420보다 식별·버전·공급 정보가 부족해 제외 |
| [15058476](https://www.data.go.kr/data/15058476/openapi.do) | 확인 필요 | 미사용 | 15110581로 대체 안내된 구 단지정보 API |

신청정보가 확인된 앞의 7개 API는 모두 개발계정이다. 15058476은 전달받은 목록에 신청일과 만료예정일이 없어 확인 필요로 남겼다.

실제 호출 흐름은 다음과 같다.

| 순서 | API | 엔드포인트 | 역할 |
| ---: | --- | --- | --- |
| 1 | 15110581 | `apis.data.go.kr/1613000/HWSPR04/rentalHouseGwList` | 단지·공급유형·주택형 카탈로그 생성 |
| 2 | 15059475 | `apis.data.go.kr/B552555/lhLeaseInfo1/lhLeaseInfo1` | 주택형 전체 세대수·현재 기준 임대조건 보강 |
| 3 | 15108420 | `apis.data.go.kr/1613000/HWSPR02/rsdtRcritNtcList` | 공고 스냅샷과 단지 단위 공급행 생성 |
| 4 | 15057999 | `apis.data.go.kr/B552555/lhLeaseNoticeDtlInfo1/getLeaseNoticeDtlInfo1` | 일정·접수처·첨부·공고 시점 단지정보 수집 |
| 5 | 15056765 | `apis.data.go.kr/B552555/lhLeaseNoticeSplInfo1/getLeaseNoticeSplInfo1` | 공고 공급행을 주택형 단위로 재구성 |

실제 사용하는 다섯 API는 계정 단위로 발급되는 같은 인증키(`serviceKey`)를 쓰고 엔드포인트만 다르다. `IngestProperties`가 `lh`/`myhomeNotice`/`myhomeComplex` 세 base-url을 갖고, LH 세 API는 같은 `lhApiClient`(같은 `B552555` 계정)를 그대로 쓴다. [IngestConfig.java:19](../src/main/java/test/domain/ingest/IngestConfig.java)

도메인에서 원천을 거꾸로 찾으면 다음과 같다.

| 도메인 | 생성·보강 원천 | 사용 내용 |
| --- | --- | --- |
| `HousingComplex` / `Address` | 15110581 | 단지 식별, 공급유형, 기관, 주소, 세대수, 건물 속성 |
| `UnitType` | 15110581 + 15059475 | 주택형·면적·기본 임대조건 생성 후 전체 세대수와 현재 임대조건 보강 |
| `Notice` | 15108420 + 15057999 | 공고 버전·기간·기관·원문 URL 생성 후 정정사유와 LH 수집 상태 보강 |
| `NoticeSupply` | 15108420 + 15057999 + 15056765 | 단지 단위 공급·임대조건 생성, 주소 연결·입주예정월 보강, 주택형별 공급 수로 재구성 |
| `NoticeSchedule` | 15057999 | 서류 제출·계약 일정 |
| `ReceptionPlace` | 15057999 | 현장 접수처와 운영 안내 |
| `NoticeAttachment` | 15057999 | 공고문 파일과 공고에 딸린 단지 이미지 |
| 현재 도메인 없음 | 15108378, 15058530, 15058476 | 각각 별도 수명, 정보 부족, 대체 원천 때문에 미사용 |

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
| `insttNm` | String | 공급기관명 | `HousingComplex.supplyInstitutionName` |
| `brtcCode`/`brtcNm` | String | 광역시도 코드/명 | `Address.provinceCode/Name` |
| `signguCode`/`signguNm` | String | 시군구 코드/명 | `Address.districtCode/Name` |
| `hsmpNm` | String | 단지명 (매입임대는 지역명) | `HousingComplex.name` |
| `rnAdres` | String | 도로명주소 | `Address.roadAddress` |
| `pnu` | String | 필지고유번호 19자리 | `Address.pnu` (단지 API ↔ 공고 API를 잇는 유일한 안전한 키) |
| `competDe` | String | 준공일자 `yyyyMMdd` | `HousingComplex.completionDate/Year` |
| `hshldCo` | Integer | 세대수 — **단지 단위가 아니라 (단지,공급유형) 단위** | `HousingComplex.unitCount` |
| `suplyTyNm` | String | 공급유형명 | `HousingComplex.supplyTypeName` (자연키의 일부) |
| `styleNm` | String | 주택형명("36", "51A") | `UnitType.typeName` |
| `suplyPrvuseAr`/`suplyCmnuseAr` | BigDecimal | 전용/공용면적 | `UnitType.exclusiveArea/residentialCommonArea` |
| `houseTyNm` | String | 주택유형명 | `HousingComplex.houseTypeName` 원문 |
| `heatMthdDetailNm` | String | 난방방식 상세 | `HousingComplex.heatingTypeName` 원문 |
| `buldStleNm` | String | 건물형태(복도식 등) | `HousingComplex.corridorType` |
| `elvtrInstlAtNm` | String | 승강기 설치 여부 | `HousingComplex.elevatorInstallation` |
| `parkngCo` | Integer | 주차 가능 대수 | `HousingComplex.parkingSpaces` |
| `bassRentGtn`/`bassMtRntchrg`/`bassCnvrsGtnLmt` | Long | 기본 임대보증금/월임대료/전환한도 | `UnitType.baseRentTerms` |

전체 필드는 `MyHomeComplexItem` 레코드가 원천을 하나도 버리지 않고 그대로 보관한다. [MyHomeComplexItem.java:14](../src/main/java/test/domain/ingest/myhome/MyHomeComplexItem.java)

## 3. 15108420 — 마이홈 모집공고

### 요청 (임대: `rsdtRcritNtcList`)

| 이름 | 명세상 필수 | 실제 호출 | 예시 | 설명 |
| --- | --- | --- | --- | --- |
| `serviceKey` | O | O | - | 인증키 |
| `brtcCode` | X | X | `43` | 광역시도 코드. 현재는 전국 공고 수집이라 보내지 않는다 |
| `signguCode` | X | X | `750` | 시군구 코드. 현재는 보내지 않는다 |
| `numOfRows` | X | O | `100` | 페이지당 결과 수 |
| `pageNo` | X | O | `1` | 페이지 번호 |
| `suplyTy` | X | O | `02` | 공급유형 코드. 현재는 공급유형별로 전국을 순회한다 |
| `lfstsTyAt` | X | X | `N` | 전세유형 모집 여부. 건설임대 경계는 `suplyTy`로 제한하므로 보내지 않는다 |
| `bassMtRntchrgSe` | X | X | `01` | 기본 월임대료 구분. 공고를 빠뜨릴 수 있어 필터로 쓰지 않는다 |

**분양공고(`ltRsdtRcritNtcList`)는 받지 않는다.** 단지 원천(15110581)이 임대주택만 담아서 분양공고의 공급행은 붙을 단지가 아예 없고(실측 63행 중 11행만 붙음), 분양의 보증금·계약금·잔금은 분양대금 분할이라 임대와 같은 칸에 담기면 뜻이 달라진다. [MyHomeNoticeIngestService.java:61](../src/main/java/test/domain/ingest/myhome/MyHomeNoticeIngestService.java)

`suplyTy`는 `MyHomeRentalType` 7개 코드를 전부 순회한다.

| 코드 | 명칭 | `SupplyType` |
| --- | --- | --- |
| `01` | 영구임대 | `PERMANENT_RENTAL` |
| `02` | 국민임대 | `NATIONAL_RENTAL` |
| `03` | 50년임대 | `FIFTY_YEAR_RENTAL` |
| `05` | 10년임대 | `TEN_YEAR_RENTAL` |
| `06` | 5년임대 | `FIVE_YEAR_RENTAL` |
| `10` | 행복주택 | `HAPPY_HOUSE` |
| `12` | 통합공공임대 | `INTEGRATED_PUBLIC_RENTAL` |

매입임대(`04`/`09`)·전세임대(`08`)·장기전세(`07`)는 이 목록에 없다 — 요청 코드부터 건설임대만 돈다. [MyHomeRentalType.java:10](../src/main/java/test/domain/ingest/myhome/MyHomeRentalType.java)

### `item` — `MyHomeNoticeItem`

원천 한 행은 공고가 아니라 **공급행**이다. 같은 `pblancId`가 단지별로 여러 행에 걸쳐 나온다. 행이 2개 이상인 공고 59건을 실측해 필드를 공고 단위/행 단위로 나눴다.

**공고 단위(행마다 안 갈림)**

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `pblancId` | 공고(버전) ID | `Notice.sourceNoticeId` |
| `sttusNm` | 공고 상태명("일반공고"/"정정공고"/"취소공고") | `Notice.noticeChangeStatusName` 원문 |
| `pblancNm` | 공고명 | `Notice.title` |
| `suplyInsttNm` | 공급기관명 | `Notice.supplyInstitutionName` |
| `houseTyNm`/`suplyTyNm` | 주택유형/공급유형명 | `Notice.houseTypeName/supplyTypeName` 원문 |
| `beforePblancId` | 이전 공고 ID | `Notice.beforeSourceNoticeId` 원문 + `supersedesNotice`·`rootSourceNoticeId` |
| `rcritPblancDe` | 모집공고일자 | `Notice.publishedAt` |
| `przwnerPresnatnDe` | 당첨자 발표일자 | `Notice.winnerAnnouncedOn` |
| `refrnc` | 문의처 | `Notice.contact` |
| `url` | 청약 사이트 원문(LH는 `panId` 포함) | `Notice.detailUrl` |
| `beginDe`/`endDe` | 모집 시작/종료일 | `Notice.applicationBeginOn/EndOn` |

**행 단위(공급행마다 다름)**

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `houseSn` | 공급행 일련번호 | `NoticeSupply.houseSn` — 같은 단지 행을 묶는 키 |
| `pcUrl`/`mobileUrl` | 행별 상세 URL | `NoticeSupply.detailUrl/mobileDetailUrl` |
| `hsmpNm`/`fullAdres`/`pnu` | 공고가 말하는 대상 주택 | `NoticeSupply.complexName/suppliedAddress/suppliedPnu` |
| `rnCodeNm`/`refrnLegaldongNm`/`brtcNm`/`signguNm` | 도로명·법정동명·광역/시군구명 | 저장하지 않는다 — `fullAdres`에 이미 들어 있다 |
| `heatMthdNm` | 난방방식명 | 저장하지 않는다 — 카탈로그 `HousingComplex.heatingTypeName`을 쓴다 |
| `totHshldCo` | 전체 세대수(문자열/숫자 혼재) | `NoticeSupply.complexTotalUnitCount` |
| `sumSuplyCo` | 이 단지의 공고 공급 수 | `NoticeSupply.complexSupplyCount` |
| `rentGtn`/`enty`/`surlus`/`mtRntchrg` | **최소** 임대보증금/계약금/잔금/월임대료 | `NoticeSupply.rentTerms` — 단지 단위 값이라 주택형 행마다 반복 |

공식 Swagger 명세의 필드명이 `최소_임대_보증금`·`최소_계약금`·`최소_중도금`·`최소_잔금`·`최소_월_임대료`다.
**단지 대표값이 아니라 그 공급행에서 가장 싼 값**이다. 주택형 행마다 이 값을 복사하면 46㎡ 행에
26㎡의 보증금이 붙는다. 「[주택형 알갱이와 매칭](주택형-알갱이와-매칭.md)」 9장 참고.

`suplyHoCo`(공급호수)와 `prtpay`(중도금)는 받지 않는다 — 건설임대 표본에서 각각 0 또는 상수(70), 중도금은 늘 0으로 나와 의미 있는 값이 아니었다. [MyHomeNoticeItem.java:8](../src/main/java/test/domain/ingest/myhome/MyHomeNoticeItem.java)

응답 봉투는 15110581과 같은 `response.header/body` 구조다. `header.resultCode/resultMsg`,
`body.totalCount/numOfRows/pageNo/item`을 가지며 `resultCode=00`은 정상, `03`은 자료 없음으로 처리한다.

## 4. 15057999 — LH 공고 상세

### 어떻게 호출 파라미터를 얻나

별도 지역·기관 코드를 조사할 필요가 없다. 마이홈이 준 `Notice.detailUrl`이 LH 청약 사이트 주소라 호출에 필요한 값이 쿼리스트링에 그대로 박혀 있다.

```
.../selectWrtancInfo.do?panId=2015122300020476&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=10
                              PAN_ID           CCR_CNNT_SYS_DS_CD    UPP_AIS_TP_CD   AIS_TP_CD
```

`LhNoticeRequest.from()`이 이 네 값을 뽑는다. `aisTpCd`는 링크에 없을 수 있어 생략 가능하고, 나머지 셋은 없으면 호출 자체를 하지 않는다. 같은 요청으로 15056765도 이어서 부른다(5장). [LhNoticeRequest.java:28](../src/main/java/test/domain/ingest/lh/LhNoticeRequest.java)

현재 공고 52건 중 48건은 상세 URL에서 `panId`와 호출 파라미터를 확보했다. 나머지는 지방공사 3건과, `SPL_INF_TP_CD`를 아직 확인하지 못한 LH 통합공공임대 1건이다.

### `SPL_INF_TP_CD` — 공급정보구분코드

링크에는 없어서 마이홈이 준 공급유형명으로 직접 계산한다. `LhSupplyInfoTypeResolver`가 담당하고,
15057999·15056765 둘 다 같은 코드를 쓴다. enum이 아니라 표 하나다 — 저장되지 않고 요청 파라미터로만 쓰인다.

| `suplyTyNm` | `SPL_INF_TP_CD` |
| --- | --- |
| `5년임대`, `10년임대` | `060` |
| `50년임대` | `061` |
| `국민임대`, `영구임대` | `062` |
| `행복주택` | `063` |
| `통합공공임대` | 미확인 — 호출 자체를 건너뛴다(`UNSUPPORTED_LH_SUPPLEMENT_TYPE`) |
| `장기전세` | 제외 유형 — 같은 경로로 건너뛴다. 예외를 던지지 않는 이유는 제외 이전에 적재된 공고가 남아 있을 수 있고, LH 적재 루프에 try/catch 가 없어서다 |

[LhSupplyInfoTypeResolver.java:17](../src/main/java/test/domain/ingest/lh/LhSupplyInfoTypeResolver.java)

### 요청 전체

| 이름 | 필수 | 출처 |
| --- | --- | --- |
| `serviceKey` | O | 공공데이터포털 인증키. `OpenApiClient`가 공통으로 추가 |
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
| `dsEtcInfo` | `CRC_RSN`(정정/취소 사유) | `Notice.correctionReason` |
| `dsSplScdl` | 서류 제출·계약 일정 | `NoticeSchedule` |
| `dsCtrtPlc` | 현장 접수처 | `ReceptionPlace` |
| `dsSbd` | 공고 시점 단지정보 | 저장하지 않는다 — 공급행을 잇는 지번주소 키. 입주예정월만 `NoticeSupply.moveInYearMonth`로 |
| `dsAhflInfo` | 공고문 원문(hwp/PDF) | `NoticeAttachment` |
| `dsSbdAhfl` | 단지 이미지(조감도·배치도·위치도) | `NoticeAttachment` |

`dsEtcInfo` 필드:

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `CRC_RSN` | 정정·취소 사유 | `Notice.correctionReason` |
| `ETC_CTS` | 기타 안내 원문 | DTO에서는 읽지만 현재 저장하지 않는다 |

`dsSplScdl` 필드:

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `SBD_LGO_NM` | 일정 대상 단지명 | `NoticeSchedule.complexLabel` |
| `ACP_DTTM` | 접수 기간 원문 | `NoticeSchedule.applicationPeriodText` |
| `PPR_SBM_OPE_ANC_DT` | 서류제출 대상자 발표일 | `NoticeSchedule.documentTargetAnnouncedOn` |
| `PPR_ACP_ST_DT` / `PPR_ACP_CLSG_DT` | 서류 접수 시작일 / 종료일 | `NoticeSchedule.documentSubmissionBeginOn/EndOn` |
| `CTRT_ST_DT` / `CTRT_ED_DT` | 계약 시작일 / 종료일 | `NoticeSchedule.contractBeginOn/EndOn` |

`dsCtrtPlc` 필드:

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `CTRT_PLC_ADR` / `CTRT_PLC_DTL_ADR` | 접수처 주소 / 상세주소 | `ReceptionPlace.address/detailAddress` |
| `TSK_ST_DTTM` / `TSK_ED_DTTM` | 운영 시작 / 종료 | `ReceptionPlace.operationBeginText/EndText` |
| `SIL_OFC_TLNO` | 현장 사무실 전화번호 | `ReceptionPlace.phone` |
| `SIL_OFC_GUD_FCTS` | 방문·접수 안내 | `ReceptionPlace.guidance` |

`dsSbd` 필드:

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `LCC_NT_NM` | LH 단지명 | 15056765의 `SBD_LGO_NM`과 연결하는 일시적 매칭 키 |
| `LGDN_ADR` / `LGDN_DTL_ADR` | 소재지주소 / 상세주소 | 마이홈 `NoticeSupply.suppliedAddress`와 연결하는 일시적 매칭 키 |
| `HSH_CNT` | 총세대수 | 주소 후보 충돌 검증에만 사용 |
| `HTN_FMLA_DESC` | 난방방식 | 현재 저장하지 않는다 |
| `DDO_AR` | 전용면적 범위(예: `36.67~51.93`) | 범위값이라 주택형 매칭에 쓰지 않고 저장하지 않는다 |
| `MVIN_XPC_YM` | 입주예정월 | `NoticeSupply.moveInYearMonth` |
| `SPL_INF_GUD_FCTS` | 단지 안내 | 현재 저장하지 않는다 |

`dsAhflInfo` / `dsSbdAhfl` 필드:

| 데이터셋 | 필드 | 의미 | 도메인 대응 |
| --- | --- | --- | --- |
| `dsAhflInfo` | `SL_PAN_AHFL_DS_CD_NM` | 공고 첨부 구분 | `NoticeAttachment.kind` |
| 공통 | `CMN_AHFL_NM` | 파일명 | `NoticeAttachment.name` |
| 공통 | `AHFL_URL` | 다운로드 URL | `NoticeAttachment.url` |
| `dsSbdAhfl` | `LS_SPL_INF_UPL_FL_DS_CD_NM` | 단지 이미지 구분 | `NoticeAttachment.kind` |
| `dsSbdAhfl` | `LCC_NT_NM` | 이미지 대상 단지명 | `NoticeAttachment.complexLabel` |

`dsAhflInfo`/`dsSbdAhfl`는 값 대신 **컬럼 이름을 담은 행**(`dsAhflInfoNm` 등, URL 자리에 "다운로드" 문자열)을 같은 응답에 같이 준다. URL이 `http`로 시작하고 호스트가 있는지로 걸러낸다. [LhNoticeIngestService.java](../src/main/java/test/domain/ingest/lh/LhNoticeIngestService.java)

## 5. 15056765 — 주택형별 공급정보

마이홈 공고(15108420)와 LH 상세(15057999) 둘 다 "이 단지에 몇 호"까지만 말하고 **어느 주택형에 몇 호**인지는 안 준다. 이 원천이 그 배분표를 준다. 2026-08-13에 실제 호출해 확인했다.

### 요청

15057999와 파라미터가 완전히 같다 — 같은 `LhNoticeRequest`로 두 호출을 이어서 부른다.

| 이름 | 필수 | 출처 |
| --- | --- | --- |
| `serviceKey` | O | 공공데이터포털 인증키. `OpenApiClient`가 공통으로 추가 |
| `PAN_ID` | O | `detailUrl`의 `panId` |
| `CCR_CNNT_SYS_DS_CD` | O | `detailUrl`의 `ccrCnntSysDsCd` |
| `UPP_AIS_TP_CD` | O | `detailUrl`의 `uppAisTpCd` |
| `AIS_TP_CD` | X | `detailUrl`의 `aisTpCd` |
| `SPL_INF_TP_CD` | O | 4장의 표에서 계산(15057999와 같은 값) |
| `PG_SZ` / `PAGE` | O | `100` / `1` 고정 |

### 응답 — `dsList01`

실제 호출 예시(국민임대 공고, 단지 6곳·9행 중 3행):

```json
{"SBD_LGO_NM":"울산구영1BL 국민임대","HTY_NNA":"59㎡","DDO_AR":"59.94",
 "SPL_AR":"82.1224","HSH_CNT":"235","NOW_HSH_CNT":"20"}
```

| 필드 | 의미 | 도메인 대응 |
| --- | --- | --- |
| `SBD_LGO_NM` | 단지명 | `NoticeSupply.lhComplexLabel` — PNU 없이 문자열로만 대조한다 |
| `HTY_NNA` | 주택형명("59㎡"처럼 단위가 붙기도 함) | `NoticeSupply.typeName` — 매칭 근거로는 안 쓴다(아래) |
| `DDO_AR` | 전용면적 | `NoticeSupply.exclusiveArea` — **매칭의 주 근거** |
| `SPL_AR` | 공급면적 | `NoticeSupply.supplyArea` |
| `HSH_CNT` | 단지·주택형 총세대수 | `NoticeSupply.unitTotalCount` |
| `NOW_HSH_CNT` | 이번 회차 공급 세대수 | `NoticeSupply.unitSupplyCount` — **금회 공급호수**. 마이홈에 없던 값 |
| `RFE`/`LS_GMY` | 월임대료/임대보증금 | `NoticeSupply.lhMonthlyRentText/lhDepositText` — **문자열 그대로**. 실측 fixture에서 `공고문 참조`가 와서 숫자로 파싱하지 않는다. 주택형별 임대료를 주는 유일한 원천이라 버리지 않는다 |

`LhUnitSupplyItem`이 이 행을 그대로 옮긴다. [LhUnitSupplyItem.java:17](../src/main/java/test/domain/ingest/lh/LhUnitSupplyItem.java)

### 왜 15057999와 한 서비스인가

요청 파라미터가 같고, **공급행 한 줄을 만들려면 둘 다 필요하다** — 15056765가 주택형과 금회
공급호수를, 15057999의 `dsSbd`가 그걸 마이홈 공급행에 잇는 지번주소를 준다. 그래서
`LhNoticeIngestService` 하나가 두 응답을 받아 한 트랜잭션에서 공급행을 다시 쓴다.

예전에는 `LhNoticeSupplement`에 "저장 후 자식을 추가할 수 없다"는 불변식이 있어 배치를 나눠야 했는데,
그 aggregate가 사라지면서 나눌 이유도 사라졌다.

### 단지·주택형 매칭

**`SBD_LGO_NM`을 카탈로그 단지명과 대조하면 안 된다.** 두 원천의 명명 체계가 다르다 — 실측 19%만 맞았다([원천-정리.md](원천-정리.md) 3장). 같은 LH 계열인 15057999의 `LCC_NT_NM`과 맞춰야 하고, 현재 실측은 252/252다.

```
dsList01 ─LH단지명─> dsSbd ─상세주소─> 마이홈 공급행 ─PNU─> HousingComplex ─면적─> UnitType
         252/252       228/252             224/252             224/252
```

앞 두 구간은 **행을 만들 때** 결정된다(`LhNoticeIngestService`). 어느 LH 주택형 행과 어느 마이홈
공급행이 한 줄이 되는지를 정하기 때문에 사후 재계산이 안 된다. 뒤 두 구간(PNU·전용면적)만
`NoticeSupplyCatalogLinker`가 나중에 다시 채울 수 있다.

주택형명(`HTY_NNA`)도 매칭 키로 쓰지 않는다 — "59㎡"처럼 단위가 붙어 카탈로그 `styleNm`("59")과 표기가 갈린다. 두 원천 다 ㎡ 소수로 오는 **전용면적**(허용오차 0.05㎡)이 근거고, 원문은 `NoticeSupply.typeName`에 그대로 남는다.

| 결과 | 의미 | 실측(252행) |
| --- | --- | ---: |
| `unitTypeId` 확정 | 단지 경로와 면적 규칙으로 카탈로그 주택형을 확정 | 224 |
| 면적 후보 다수 | 공급면적까지 적용해도 카탈로그 주택형이 여러 개 | 0 |
| 면적 후보 없음 | 단지는 확정됐는데 면적이 맞는 주택형이 없음 | 0 |
| 앞 구간 단절 | 주소 또는 PNU에서 끊김 | 28 |

못 붙은 이유는 상태 enum이 아니라 `NoticeSupply.unmatchedReason` 문자열 한 칸에 남는다.
`/admin/ingest/lh-notices` 뒤에 `/admin/ingest/links`를 돌린다. [NoticeSupplyCatalogLinker.java](../src/main/java/test/domain/ingest/NoticeSupplyCatalogLinker.java)

## 6. 15059475 — LH 임대단지 주택형 카탈로그

15059475는 공고가 아니라 전국 LH 임대단지의 전용면적별 전체 세대수 카탈로그다.
한 `dsList` 행의 `HSH_CNT`가 해당 전용면적 주택형의 전체 세대수이고,
`SUM_HSH_CNT`는 단지·공급유형 전체 세대수다.

### 요청

```http
GET https://apis.data.go.kr/B552555/lhLeaseInfo1/lhLeaseInfo1
```

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `serviceKey` | O | `DATA_GO_KR_SERVICE_KEY` 환경변수 |
| `CNP_CD` | X | 지역 필터. 생략하면 전국 |
| `SPL_TP_CD` | X | 공급유형 필터. 생략하면 전체 |
| `PG_SZ` | O | 페이지 크기. 기본 호출은 `9999` |
| `PAGE` | O | 1부터 증가하는 페이지 번호 |

```bash
curl -X POST "localhost:8080/admin/ingest/lease-infos"
```

### 응답 필드

```json
{"ARA_NM":"강원특별자치도 강릉시","AIS_TP_CD_NM":"행복주택",
 "SBD_LGO_NM":"강릉교동 행복주택","SUM_HSH_CNT":"180",
 "DDO_AR":"36.97","HSH_CNT":"72","LS_GMY":"19546000","RFE":"195460"}
```

| 필드 | 뜻 | 도메인 매핑 |
| --- | --- | --- |
| `ARA_NM` | 지역명 | 카탈로그 지역명과 비교. 저장하지 않는다 |
| `AIS_TP_CD_NM` | 공급유형명 | `HousingComplex.supplyTypeName`과 비교. 저장하지 않는다 |
| `SBD_LGO_NM` | LH 단지명 | `HousingComplex.name`과 비교. 저장하지 않는다 |
| `SUM_HSH_CNT` | 단지·공급유형 전체 세대수 | `HousingComplex.unitCount` 검증. 다르면 반영하지 않는다 |
| `DDO_AR` | 전용면적(㎡) | `UnitType.exclusiveArea`와 정확 비교 |
| `HSH_CNT` | 전용면적 주택형 전체 세대수 | 확정된 `UnitType.totalUnitCount` |
| `LS_GMY` | 현재 카탈로그 주택형 임대보증금 | 확정된 `UnitType.baseRentTerms.deposit` (6,710/6,710 전부 숫자) |
| `RFE` | 현재 카탈로그 주택형 월임대료 | 확정된 `UnitType.baseRentTerms.monthlyRent` |
| `MVIN_XPC_YM` | 최초 입주년월 | 현재 저장하지 않는다. 공고의 입주예정월과 의미가 다르다 |

응답의 `resHeader`에는 `SS_CODE`(성공 여부)와 `RS_DTTM`(응답 시각)이 있고,
`dsList` 각 행에는 `RNUM`(순번)이 추가로 온다. 둘 다 적재 성공 판정·페이지 확인에만 쓰고 저장하지 않는다.

### 매칭과 보존 규칙

이 원천에는 단지 ID·PNU·상세주소가 없다. 따라서 지역·단지명·공급유형·
`SUM_HSH_CNT`가 하나의 카탈로그 단지·공급유형으로 좁혀지고, `DDO_AR`가 정확히 하나의
`UnitType.exclusiveArea`와 일치할 때만 `HSH_CNT`를 기록한다. `BigDecimal` 비교라
36.97과 36.9700은 같지만, 서로 다른 수치에는 ±0.05㎡ 근사를 적용하지 않는다.

같은 UnitType을 가리키는 원천행이 여러 개면 마지막 값이 앞 값을 덮어쓰지 못하도록 아무것도 반영하지 않는다.
`dsList`가 누락되거나 모든 페이지가 비어 있으면 아무것도 바꾸지 않는다.
**원천행은 저장하지 않는다** — 한 번 호출로 전부 받아서 재적재 비용이 원천행을 남길 값보다 작다.

실제 2026-08-13 전국 적재 관측값은 원천 6,710행, `MATCHED` 1,487행,
`AMBIGUOUS` 76행, `CONFLICT_PROGRAM_UNIT_COUNT` 8행, `UNMATCHED` 5,139행이다.

2026-08-16 재적재(장기전세 제외 후) 관측값은 원천 6,710행 중 주택형 1,481건 확정이다.
안 붙는 5,000여 행은 단지명이 LH식이라 마이홈 이름과 19%만 맞기 때문이다(3장).

## 7. 15108378 — 마이홈 예비입주자 대기현황 (미사용)

```http
GET https://apis.data.go.kr/1613000/HWSPR03/moveWaitStsList
```

### 요청

| 이름 | 필수 | 예시 | 설명 |
| --- | --- | --- | --- |
| `serviceKey` | O | - | 인증키 |
| `brtcCode` | O | `11` | 광역시도 코드 |
| `signguCode` | X | `350` | 시군구 코드 |
| `numOfRows` | X | `10` | 페이지당 결과 수. 기본값 10 |
| `pageNo` | X | `1` | 페이지 번호. 기본값 1 |
| `suplyTy` | X | `01` | 임대종류 코드 |
| `houseTy` | X | `11` | 주택유형 코드 |

공식 2026-07-01 요청 코드표의 주택유형은 `11` 아파트, `12` 연립주택, `13` 다세대주택,
`14` 단독주택, `15` 오피스텔, `16` 다가구주택이다.

공급유형은 `01` 영구임대, `02` 국민임대, `03` 50년임대, `04` 매입임대,
`05` 10년임대, `06` 5년임대, `07` 장기전세, `09` 행복주택, `10` 공공기숙사,
`11` 통합공공임대, `12` 6년임대다. **15108420 모집공고의 공급유형 코드와 일부 번호가 다르므로
공통 enum 코드로 재사용하면 안 된다.**

### 응답

| 위치 | 필드 | 의미 |
| --- | --- | --- |
| `header` | `resultCode` / `resultMsg` | 결과코드 / 결과메시지 |
| `body` | `totalCount` / `numOfRows` / `pageNo` | 전체 결과 수 / 페이지 크기 / 페이지 번호 |
| `item` | `rtsInsttNm` | 임대사업자명 |
| `item` | `brtcNm` / `signguNm` | 광역시도명 / 시군구명 |
| `item` | `rnAdres` | 도로명주소 |
| `item` | `hsmpSn` / `hsmpNm` | 단지 일련번호 / 단지명 |
| `item` | `houseTyNm` / `suplyTyNm` | 주택유형명 / 공급유형명 |
| `item` | `styleNm` | 주택형명 |
| `item` | `drwtUnit` | 추첨단위 |
| `item` | `waitCo` | 입주대기자 수 |
| `item` | `trmnatCo` | 퇴거 건수 |

### 도메인 사용 판단

현재 코드에서는 호출하지 않고 저장 도메인도 없다. `hsmpSn + suplyTyNm`으로
`HousingComplex` 후보를 찾을 수 있지만, `waitCo`와 `trmnatCo`는 조회 시점마다 바뀌는 운영 현황이다.
현재의 카탈로그나 불변 공고 스냅샷에 넣으면 갱신 수명이 섞인다. 사용하게 되면
`WaitlistStatusSnapshot`처럼 수집시각을 가진 별도 시계열 도메인으로 두는 편이 맞다.

## 8. 15058530 — LH 분양임대공고문 조회 (미사용)

```http
GET https://apis.data.go.kr/B552555/lhLeaseNoticeInfo1/lhLeaseNoticeInfo1
```

### 요청

| 이름 | 필수 | 예시 | 설명 |
| --- | --- | --- | --- |
| `ServiceKey` | O | - | 인증키 |
| `PG_SZ` / `PAGE` | O | `10` / `1` | 페이지 크기 / 페이지 번호 |
| `PAN_NM` | X | `대전` | 공고명 검색어 |
| `UPP_AIS_TP_CD` | X | `06` | 공고유형 코드. `06`은 임대주택 |
| `CNP_CD` | X | `11` | 지역코드 |
| `PAN_SS` | X | `공고중` | 공고상태 코드 |
| `PAN_NT_ST_DT` | O | `2019.07.23` | 공고 게시 시작일 |
| `CLSG_DT` | O | `2019.08.22` | 공고 마감일 |

### 응답

| 위치 | 필드 | 의미 |
| --- | --- | --- |
| `resHeader` | `SS_CODE` / `RS_DTTM` | 성공 여부 / 응답 시각 |
| `dsList` | `RNUM` | 순번 |
| `dsList` | `UPP_AIS_TP_NM` | 공고유형명 |
| `dsList` | `AIS_TP_CD_NM` | 공고 세부유형명 |
| `dsList` | `PAN_NM` | 공고명 |
| `dsList` | `CNP_CD_NM` | 지역명 |
| `dsList` | `PAN_SS` | 공고상태 |
| `dsList` | `ALL_CNT` | 전체 조회 건수 |
| `dsList` | `DTL_URL` | LH 공고 상세 URL. URL 안에 `PAN_ID` 등 후속 호출 키가 포함될 수 있다 |

### 도메인 사용 판단

`Notice` 후보 원천이지만 현재는 사용하지 않는다. 15108420은 `pblancId`, `beforePblancId`,
정정·취소 상태, PNU, 공급 수, 공고 시점 임대조건을 함께 준다. 15058530은 목록 검색에는 유용하지만
현재 공고 버전 체인과 `NoticeSupply`를 만들 정보가 부족하다. 후속 LH 호출 키도 15108420의
`Notice.detailUrl`에서 이미 얻는다.

## 9. 15058476 — LH 공공임대주택 단지정보 (미사용·대체됨)

공식 포털이 인증키 오류를 개선한 15110581 마이홈 단지정보로 제공 중이라고 안내하는 구 API다.

```http
GET https://data.myhome.go.kr/rentalHouseList
```

### 요청

| 이름 | 필수 | 예시 | 설명 |
| --- | --- | --- | --- |
| `ServiceKey` | O | - | 인증키 |
| `brtcCode` | O | `11` | 광역시도 코드 |
| `signguCode` | O | `140` | 시군구 코드 |
| `numOfRows` | X | `10` | 페이지당 결과 수 |
| `pageNo` | X | `1` | 페이지 번호 |

### 응답

봉투에는 `code`, `msg`, `numOfRows`, `pageNo`, `totalCount`가 온다. `item`은 15110581과 같은
단지·공급유형·주택형 구조이며 다음 필드를 제공한다.

| 묶음 | 필드 |
| --- | --- |
| 식별·기관 | `hsmpSn`, `insttNm` |
| 지역 | `brtcCode`, `brtcNm`, `signguCode`, `signguNm` |
| 단지·주소 | `hsmpNm`, `rnAdres`, `pnu`, `competDe` |
| 공급 | `hshldCo`, `suplyTyNm` |
| 주택형·면적 | `styleNm`, `suplyPrvuseAr`, `suplyCmnuseAr` |
| 건물 | `houseTyNm`, `heatMthdDetailNm`, `buldStleNm`, `elvtrInstlAtNm`, `parkngCo` |
| 기본 임대조건 | `bassRentGtn`, `bassMtRntchrg`, `bassCnvrsGtnLmt` |

### 도메인 사용 판단

응답은 `HousingComplex`, `Address`, `UnitType`에 맞지만 직접 호출하지 않는다. 동일 역할의 15110581을
사용하므로 두 원천을 함께 적재하면 같은 단지·주택형이 중복될 수 있다.

15059475도 LH 응답 봉투를 사용한다(최상위 배열, `resHeader`/`dsList`로 키 검색).
`OpenApiClient.findRows`의 LH 분기를 그대로 재사용한다.

## 10. 인증키와 오류 응답

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
| 국토부 계열(15110581, 15108420, 15108378) | `response.header.resultCode` ∈ {`00`, `03`} |
| LH 계열(15057999, 15056765, 15059475 등, `B552555`) | 배열 안 `resHeader[].SS_CODE` = `Y` |

## 11. Spring 매핑 원칙

- 요청 DTO는 전부 `record` + `@JsonIgnoreProperties(ignoreUnknown = true)`다. 원천이 필드를 늘려도 역직렬화가 깨지지 않는다.
- 도메인 판단에 사용하기로 한 원천 필드는 record에 담고, 아직 쓰지 않는 필드는 `@JsonIgnoreProperties(ignoreUnknown = true)`로 안전하게 무시한다. 예를 들어 15108420의 `suplyHoCo`·`prtpay`, 15059475의 `RNUM`·`MVIN_XPC_YM`은 현재 DTO에 없다.
- 코드·ID·PNU·날짜·금액 문자열은 원천 DTO에서 `String`/`Long`/`BigDecimal`로 그대로 받고, `SourceValues`가 도메인 타입으로 옮긴다 — 날짜 포맷이 `yyyyMMdd`/`yyyy.MM.dd` 둘 다 오고, 숫자가 문자열로도 숫자로도 오기 때문이다. [SourceValues.java:9](../src/main/java/test/domain/ingest/SourceValues.java)
- LH 원천은 필드명이 `LhNoticeDetail`/`LhUnitSupplyItem` 안에서 `@JsonProperty`로 도메인 이름에 매핑된다 — 원문 약어(`SBD_LGO_NM` 등)를 엔티티까지 끌고 가지 않는다.
- 두 원천의 응답 봉투를 하나의 공통 파서로 합치지 않는다. `OpenApiClient.findRows()`가 JSON Pointer 경로(국토부, `/`로 시작)와 배열-내-키 검색(LH)을 분기해서 처리한다.
- 15057999와 15056765는 요청 키가 같고 공급행 하나를 완성하려면 두 응답이 함께 필요하므로 `LhNoticeIngestService`가 한 공고 단위로 수집한다. 어느 한쪽이라도 실패하면 그 공고의 LH 보강은 반영하지 않고 다음 공고를 계속 처리한다.
