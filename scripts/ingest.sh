#!/usr/bin/env bash
#
# 적재 순서대로 /admin/ingest/* 를 호출한다.
#
#   ./scripts/ingest.sh                       # 다섯 단계 전부
#   ./scripts/ingest.sh notices lh-notices    # 일부만
#   ./scripts/ingest.sh links                 # 규칙만 고쳤을 때 (원천 재호출 없음)
#
# 앱이 먼저 떠 있어야 한다. 인증키는 이 스크립트가 아니라 앱이 시작할 때 읽는다.
#
#   DATA_GO_KR_SERVICE_KEY=... ./gradlew bootRun
#
set -uo pipefail

BASE_URL="${INGEST_BASE_URL:-http://localhost:8080}"
ALL_STEPS=(complexes lease-infos notices lh-notices links)

# /complexes 는 256개 시군구를 도느라 20분 가까이 걸린다. 나머지는 훨씬 짧다.
timeout_for() {
    case "$1" in
        complexes) echo 2400 ;;
        lh-notices) echo 900 ;;
        *) echo 300 ;;
    esac
}

describe() {
    case "$1" in
        complexes)   echo "15110581 마이홈 단지정보 → 단지·주택형 카탈로그" ;;
        lease-infos) echo "15059475 LH 임대주택 → 주택형 전체 세대수·기준 임대조건" ;;
        notices)     echo "15108420 마이홈 모집공고 → 공고 + 단지 단위 공급행" ;;
        lh-notices)  echo "15057999 + 15056765 → 일정·접수처·첨부, 공급행을 주택형 단위로" ;;
        links)       echo "원천 호출 없음 → 공급행에 카탈로그 FK 채우기" ;;
    esac
}

require_app() {
    if ! curl -s -o /dev/null -m 5 "${BASE_URL}" 2>/dev/null; then
        echo "앱에 붙지 못했습니다: ${BASE_URL}" >&2
        echo "  DATA_GO_KR_SERVICE_KEY=... ./gradlew bootRun" >&2
        exit 1
    fi
}

# 인증키 없이 뜬 앱은 모든 적재가 0초 만에 failed 로 끝난다. 원천 호출 한 번으로 미리 가른다.
# 게이트웨이가 불안정해서 실패할 수도 있으므로 경고만 하고 진행 여부는 사용자가 정한다.
check_service_key() {
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' -m 30 \
        "${BASE_URL}/admin/ingest/probe?source=lh&path=lhLeaseInfo1/lhLeaseInfo1&PG_SZ=1&PAGE=1")
    if [ "$code" = "200" ]; then
        return 0
    fi
    echo "! 원천 확인 호출이 http=${code} 로 실패했습니다." >&2
    echo "  앱이 DATA_GO_KR_SERVICE_KEY 없이 떠 있으면 모든 단계가 0초 만에 failed 로 끝납니다." >&2
    echo "  앱을 내리고 키와 함께 다시 띄우세요:  DATA_GO_KR_SERVICE_KEY=... ./gradlew bootRun" >&2
    echo "  (LH 게이트웨이가 간헐적으로 실패하기도 합니다. 키가 맞다면 그냥 진행해도 됩니다.)" >&2
}

# 응답 JSON 에서 정수 필드 하나를 꺼낸다. jq 없이 동작한다.
field() {
    echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | cut -d: -f2
}

run_step() {
    local step="$1" start elapsed body code failed
    printf '\n▶ /%s  — %s\n' "$step" "$(describe "$step")"
    start=$(date +%s)
    body=$(curl -s -m "$(timeout_for "$step")" -w $'\n%{http_code}' \
        -X POST "${BASE_URL}/admin/ingest/${step}")
    code=$(echo "$body" | tail -1)
    body=$(echo "$body" | sed '$d')
    elapsed=$(( $(date +%s) - start ))

    if [ "$code" != "200" ]; then
        printf '  실패  http=%s  (%ds)\n  %s\n' "$code" "$elapsed" "$body" >&2
        return 1
    fi
    printf '  %s\n  %ds\n' "$body" "$elapsed"

    # 일일 요청제한에 걸리면 전 지역이 failed 로 돌아온다. 계속 두들겨도 소용없으니 멈춘다.
    failed=$(field "$body" failed)
    if [ -n "${failed:-}" ] && [ "$failed" -gt 0 ]; then
        printf '  ! failed=%s — 인증키 누락 · 일일 요청제한(429) · 원천 게이트웨이 오류 중 하나입니다.\n' "$failed" >&2
        if [ "$failed" -ge 100 ]; then
            echo "  ! 실패가 100건을 넘어 중단합니다." >&2
            return 1
        fi
    fi
    return 0
}

steps=("$@")
if [ ${#steps[@]} -eq 0 ]; then
    steps=("${ALL_STEPS[@]}")
fi
for step in "${steps[@]}"; do
    case " ${ALL_STEPS[*]} " in
        *" $step "*) ;;
        *) echo "모르는 단계입니다: $step (가능: ${ALL_STEPS[*]})" >&2; exit 1 ;;
    esac
done

require_app
check_service_key
echo "대상: ${BASE_URL}"
echo "단계: ${steps[*]}"

for step in "${steps[@]}"; do
    if ! run_step "$step"; then
        echo $'\n중단했습니다. 앞 단계 결과는 DB 에 남아 있습니다.' >&2
        exit 1
    fi
done

printf '\n끝났습니다.\n'
