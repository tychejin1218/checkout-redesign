---
name: sync-docs
description: src/main 이나 src/test 의 코드가 바뀌면 DESIGN.md 와 docs/order-flow.md 에 반영한다. 코드를 수정한 작업을 끝내기 전에, 또는 Stop 훅이 문서 반영을 요구할 때 실행한다.
---

# 코드 변경을 설계 문서에 반영하기

`src/main` 이나 `src/test` 의 코드가 바뀌면 `DESIGN.md` 와 `docs/order-flow.md` 가 바뀐 코드와 같은 내용을 말하도록 맞춘다.
두 문서는 과제 제출물이고, 코드와 어긋난 설명은 없느니만 못하다.

## 절차

### 1. 무엇이 바뀌었는지 본다

```bash
git status --short -- src/main src/test
git diff HEAD -- src/main src/test
```

커밋되지 않은 변경이 "이번 작업"이다. 새로 만든 파일(`??`)은 diff 에 나오지 않으므로 직접 읽는다.

### 2. 바뀐 내용이 닿는 문서 위치를 찾는다

아래 표로 후보를 좁힌 뒤, 바뀐 클래스·메서드·테스트 이름·상태 코드로 두 문서를 검색해 빠진 곳이 없는지 확인한다.

| 바뀐 것 | DESIGN.md | docs/order-flow.md |
|---|---|---|
| 주문 생성 흐름 (`OrderService`, `OrderQuoteService`, `OrderWriter`, `OrderSupport`) | 3.2 단계, 5.2 확보 절차, 6.2 가격 | 한눈에 보기, 단계별 표 1~6 |
| 멱등 키, 주문번호 | 4.3 "레거시와 달라지는 점", 4.5 | 1, 3 |
| 반환·복구·만료 (`OrderCancelService`, `OrderRecoveryService`, 스케줄러) | 3.1, 5.3, 5.4 | "RESERVED 가 되지 않는 경우" |
| 레거시 접근 (`legacy/`, `resources/mapper/legacy`) | 4.1, 4.4, 5.2 의 SQL, 6.1 | 2, 4 |
| 스키마 (`resources/db`) · 엔티티 | 4.3, 4.4 (`docs/erd/*.md` 도 함께) | 해당 단계 |
| 응답 코드 (`ApiStatus`) | 코드를 언급한 곳 | 그 코드를 내는 단계의 "하는 일" |
| 구현 범위가 늘거나 줄었다 | 10.1 | 문서 머리말 |
| 테스트를 추가·삭제·개명했다 | 10.2 "테스트가 증명하는 것" | "증명하는 테스트" 열, "테스트가 없는 곳" |
| 가정·한계·미결 사항이 생기거나 풀렸다 | 9, 10.3, 11 | - |

`docs/order-flow.md` 는 주문 요청부터 `RESERVED` 까지만 다룬다. 그 범위 밖의 변경(취소·복구 내부 등)은 "RESERVED 가 되지 않는 경우" 표에 걸릴 때만 고친다.

### 3. 문서를 고친다

- **지금의 코드가 어떻게 동작하는지**를 적는다. "~로 변경했다", "기존에는 ~였다" 같은 변경 이력은 적지 않는다. 이력은 git 과 `prompts/used_prompts.md` 에 있다
- 기존 문장의 용어와 문체를 따른다(반영 기록, 확보·반환, 복구 작업자, `~다` 체). 새 용어를 만들지 않는다
- 테스트 이름은 테스트 함수의 백틱 이름과 글자 그대로 같아야 한다
- 설계 결정이 바뀌었다면 근거도 함께 적는다. `adr/` 의 결정과 어긋나면 고치지 말고 사용자에게 알린다
- 문서에 영향이 없는 변경(리팩터링, 포맷, 주석)이면 문서를 고치지 않는다. 억지로 문장을 넣지 않는다

### 4. 확인한다

`docs/order-flow.md` 와 `DESIGN.md` 10.2 가 인용한 테스트 이름·클래스 이름이 실제로 있는지 검색해 확인한다.

```bash
grep -rn "fun \`" src/test/kotlin | grep "<문서에 적은 테스트 이름 일부>"
```

### 5. 반영을 마쳤다고 기록한다

```bash
.claude/hooks/docs-sync-check.sh --mark
```

지금의 `src` 변경 내용을 "문서 반영 확인함"으로 기록한다(`.claude/docs-sync.stamp`, 커밋하지 않음). 문서를 고칠 필요가 없다고 판단한 경우에도 실행한다.
이후 `src` 가 다시 바뀌면 기록이 맞지 않게 되어 Stop 훅이 다시 알린다.

### 6. 사용자에게 알린다

어느 문서의 어느 절을 고쳤는지, 고치지 않았다면 왜 필요 없는지를 한두 줄로 알린다.

## Stop 훅과의 관계

`.claude/settings.json` 의 Stop 훅(`.claude/hooks/docs-sync-check.sh`)이 응답을 끝내기 전에 검사한다.
`src/main`·`src/test` 에 커밋되지 않은 변경이 있는데 `DESIGN.md` 나 `docs/order-flow.md` 가 바뀌지 않았고 5의 기록도 없으면, 응답 종료를 한 번 막고 이 스킬을 실행하라고 알린다.
훅은 스킬을 직접 실행하지 못한다. 알림을 받은 Claude 가 이 스킬을 실행한다.
