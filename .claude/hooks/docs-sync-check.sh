#!/bin/bash
# Claude Code Stop 훅: src 코드가 바뀌었는데 설계 문서가 그대로면 응답을 끝내기 전에 sync-docs 스킬을 실행하라고 알린다.
#
# 연결: .claude/settings.json 의 hooks.Stop
# 훅은 스킬을 직접 실행할 수 없다. 종료 코드 2 로 응답 종료를 막으면 stderr 의 내용이 Claude 에게 전달되고,
# Claude 가 그 지시에 따라 스킬(.claude/skills/sync-docs)을 실행한다.
#
# 알리는 조건 (모두 참일 때):
#   1. src/main 또는 src/test 에 커밋되지 않은 변경이 있다
#   2. DESIGN.md 와 docs/order-flow.md 중 바뀌지 않은 문서가 있다
#   3. 지금의 src 변경에 대해 "문서 반영 확인함" 기록이 없다 (스킬의 마지막 단계가 --mark 로 남긴다)
#
# 사용:
#   docs-sync-check.sh          Stop 훅으로 실행 (stdin 으로 훅 입력 JSON 을 받는다)
#   docs-sync-check.sh --mark   지금의 src 변경을 "문서 반영 확인함" 으로 기록한다

set -u

root="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
cd "$root" 2>/dev/null || exit 0

stamp=".claude/docs-sync.stamp"
docs="DESIGN.md docs/order-flow.md"

# 지금의 src 변경 내용을 가리키는 값. 변경이 달라지면 값도 달라진다
fingerprint() {
  {
    git diff HEAD -- src/main src/test
    git ls-files --others --exclude-standard -z -- src/main src/test | xargs -0 shasum 2>/dev/null
  } | shasum | cut -d' ' -f1
}

if [ "${1:-}" = "--mark" ]; then
  fingerprint > "$stamp"
  echo "문서 반영 확인을 기록했습니다: $stamp"
  exit 0
fi

input=$(cat)

# 이 훅이 막아서 이어진 응답이면 다시 막지 않는다 (무한 반복 방지)
if echo "$input" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
  exit 0
fi

[ -z "$(git status --porcelain -- src/main src/test 2>/dev/null)" ] && exit 0
[ -f "$stamp" ] && [ "$(cat "$stamp")" = "$(fingerprint)" ] && exit 0

unchanged=""
for doc in $docs; do
  [ -z "$(git status --porcelain -- "$doc")" ] && unchanged="$unchanged $doc"
done
[ -z "$unchanged" ] && exit 0

{
  echo "src/main 또는 src/test 의 코드가 바뀌었는데 다음 문서가 바뀌지 않았습니다:$unchanged"
  echo "응답을 끝내기 전에 sync-docs 스킬을 실행해 DESIGN.md 와 docs/order-flow.md 에 반영하세요."
  echo "문서를 고칠 필요가 없다면 스킬의 5단계(.claude/hooks/docs-sync-check.sh --mark)만 실행하고 그 이유를 사용자에게 알리세요."
} >&2
exit 2
