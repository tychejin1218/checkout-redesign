# 주문 흐름: 요청부터 RESERVED 까지

주문 요청이 들어와 주문이 `RESERVED`(자원 확보 성공)가 될 때까지 거치는 클래스와 메서드를 순서대로 적었다. 설계의 배경은 [DESIGN.md](../DESIGN.md) 5장과 [ADR 0003](../adr/0003-cross-database-write.md)에 있다.

- 코드 경로는 `src/main/kotlin/com/example/checkout/` 아래, 테스트는 `src/test/kotlin/com/example/checkout/` 아래다.
- HTTP API(컨트롤러)는 구현하지 않았다. 진입점은 `OrderService.placeOrder`이고 테스트도 이 메서드를 직접 부른다.
- `placeOrder`는 트랜잭션이 아니다. 아래 표의 "커밋" 열이 실제 커밋이 일어나는 지점이다.

## 한눈에 보기

```
OrderService.placeOrder
 ├─ 1. 같은 회원·같은 멱등 키의 주문이 있으면 내용이 같을 때 그 주문을 돌려준다 [신규 DB 읽기]
 ├─ 2. OrderQuoteService.quote: 회원·상품·쿠폰 확인, 금액 계산과 검증      [레거시 DB 읽기]
 ├─ 3. OrderWriter.createPending: 주문을 PENDING 으로 INSERT              [신규 DB 커밋 ①]
 ├─ 4. LegacyResourceAdapter.reserve: 반영 기록 + 재고·쿠폰·포인트 차감    [레거시 DB 커밋 ②]
 ├─ 5. OrderWriter.transition: PENDING → RESERVED                        [신규 DB 커밋 ③]
 └─ 6. 주문을 다시 읽어 결과를 돌려준다                                   [신규 DB 읽기]
```

## 단계별 설명과 테스트

### 1. 진입과 멱등 키 확인

| 클래스.메서드 | 하는 일 | 커밋 | 증명하는 테스트 |
|---|---|---|---|
| `order/service/OrderService.placeOrder` | 진입점이다. 같은 회원이 같은 멱등 키로 낸 주문이 이미 있으면 다시 실행하지 않고 그 주문의 현재 상태를 돌려준다(`existingResult`). 없으면 `placeNewOrder`로 간다 | 없음 | `OrderPlacementTest` · 같은 멱등 키로 다시 요청하면 같은 주문을 돌려주고 재고는 한 번만 줄어든다 |
| `domain/repository/OrderRepository.findByMemberNoAndIdempotencyKey` | 회원 번호와 멱등 키로 신규 DB의 주문을 찾는다. 회원 번호를 함께 넣으므로 다른 회원의 주문은 나오지 않는다 | 없음 | `OrderPlacementTest` · 다른 회원이 같은 멱등 키를 써도 각자의 주문이 만들어진다 |
| `OrderService.isSameRequest` → `domain/repository/OrderItemRepository.findByIdOrderNoOrderByIdSeq` | 찾은 주문의 품목(상품·수량), 쿠폰, 포인트를 요청과 비교한다. 품목 순서는 보지 않는다. 하나라도 다르면 `ORDER_IDEMPOTENCY_KEY_REUSED`로 거절하고 기존 주문과 레거시 자원은 바뀌지 않는다 | 없음 | `OrderPlacementTest` · 같은 멱등 키로 품목 순서만 바꿔 다시 요청해도 같은 주문을 돌려준다 / 같은 멱등 키로 수량이 다른 요청을 보내면 거절하고 기존 주문과 재고는 그대로다 / 같은 멱등 키로 상품이 다른 요청을 보내면 거절한다 / 같은 멱등 키로 쿠폰이 다른 요청을 보내면 거절하고 쿠폰은 쓰이지 않는다 / 같은 멱등 키로 포인트가 다른 요청을 보내면 거절하고 포인트는 줄지 않는다 |

### 2. 요청 검증과 금액 계산 (레거시 DB 읽기)

`OrderService.placeNewOrder`가 `OrderQuoteService.quote`를 부른다. 여기서 거절되면 예외로 끝나고 **주문은 기록되지 않는다.**

| 클래스.메서드 | 하는 일 | 커밋 | 증명하는 테스트 |
|---|---|---|---|
| `order/service/OrderQuoteService.validateMember` → `legacy/resource/LegacyMemberPort.isActive` → `legacy/mapper/LegacyMemberMapper.countActiveMember` | 레거시 `TB_MEMBER`에 회원이 있고 `USE_YN = 'Y'`인지 확인한다. 아니면 `ORDER_MEMBER_UNAVAILABLE` | 없음 | `OrderPlacementTest` · 레거시에 없는 회원의 주문은 받지 않는다 / 사용 여부가 N 인 회원의 주문은 받지 않는다 |
| `OrderQuoteService.validateLines` | 품목이 비었거나, 수량이 0 이하이거나, 같은 상품이 두 번 들어 있으면 `VALIDATION_INVALID_REQUEST` | 없음 | `OrderRequestValidationTest` · 품목이 없는 주문은 받지 않는다 / 수량이 0 이하인 품목이 있는 주문은 받지 않는다 / 같은 상품이 두 번 들어 있는 주문은 받지 않는다 |
| `OrderQuoteService.quoteLine` → `legacy/resource/LegacyResourcePort.findGoods` | 레거시 `TB_GOODS`에서 현재 가격과 상품명을 읽는다. 상품이 없거나 판매 중이 아니면 `ORDER_GOODS_UNAVAILABLE` | 없음 | `OrderPlacementTest` · 판매 중이 아닌 상품은 주문을 받지 않는다 |
| `OrderQuoteService.quote` | 품목마다 수량 × 단가를 더해 상품 금액을 구한다. 금액은 `Long`이고 수량은 `Int`라서 곱이 32비트 범위를 넘어도 값이 넘치지 않는다 | 없음 | `OrderPlacementTest` · 수량과 단가를 곱한 금액이 Int 범위를 넘어도 금액이 정확히 계산된다 |
| `OrderQuoteService.discountOf` → `LegacyResourcePort.findCoupon` | 쿠폰을 쓰면 본인 소유의 미사용 쿠폰인지 확인하고 할인 금액을 정한다. 아니면 `ORDER_COUPON_UNAVAILABLE` | 없음 | `OrderPlacementTest` · 쿠폰과 포인트를 쓰면 재고와 함께 레거시에서 차감된다. `OrderRequestValidationTest` · 다른 회원의 쿠폰으로는 주문을 받지 않는다 / 이미 사용한 쿠폰으로는 주문을 받지 않는다 / 없는 쿠폰으로는 주문을 받지 않는다 |
| `OrderQuoteService.validateAmounts` | 포인트가 0 이상 결제 금액 이하인지, 사용자가 확인한 금액이 서버가 계산한 금액과 같은지 본다. 다르면 `ORDER_POINT_INVALID`, `ORDER_AMOUNT_CHANGED` | 없음 | `OrderPlacementTest` · 사용자가 확인한 금액과 서버가 계산한 금액이 다르면 주문을 받지 않는다. `OrderRequestValidationTest` · 사용할 포인트가 음수이면 주문을 받지 않는다 / 사용할 포인트가 결제 금액보다 크면 주문을 받지 않는다 |

### 3. 주문을 PENDING 으로 기록 (신규 DB 커밋 ①)

| 클래스.메서드 | 하는 일 | 커밋 | 증명하는 테스트 |
|---|---|---|---|
| `OrderService.recordPending` | 주문과 품목을 만들어 기록을 맡긴다. INSERT가 거절되면 같은 회원·같은 멱등 키의 주문이 있는지 먼저 본다. 있으면 동시에 들어온 같은 요청이 먼저 기록된 것이므로 1과 같은 비교를 거쳐 그 주문을 돌려준다. 없으면 주문번호가 겹친 것으로 보아 새 번호로 다시 한다(첫 시도 포함 3번) | 없음 | `OrderPlacementTest` · 주문번호가 다른 주문과 겹치면 그 주문을 덮어쓰지 않고 새 번호로 기록한다 / 주문번호가 계속 겹치면 주문을 받지 않고 기존 주문과 재고는 그대로다 |
| `order/service/OrderSupport.newOrder` → `order/service/OrderNoGenerator.next` | 주문번호(20자: `N` + 한국 날짜 + 무작위 13자)를 만들고, 2에서 계산한 금액으로 `CheckoutOrder`를 만든다. 상태는 `PENDING`이다 | 없음 | `OrderNoGeneratorTest` · 주문번호는 N 과 한국 날짜와 무작위 13자로 된 20자다 / 부를 때마다 다른 번호가 나온다 |
| `OrderSupport.newItems` | 2에서 읽은 단가와 상품명을 품목(`OrderItem`)에 스냅샷으로 담는다 | 없음 | `OrderPlacementTest` · 품목에는 주문 시점의 단가와 상품명이 저장된다 |
| `order/service/OrderWriter.createPending` (`@NewTransactional`) | 주문과 품목을 신규 DB에 INSERT한다. `save`가 아니라 `EntityManager.persist`를 써서 기존 주문을 덮어쓰지 않는다. 메서드가 끝나면 커밋된다 | **신규 DB ①** | `OrderConcurrencyTest` · 같은 멱등 키의 요청이 동시에 10번 들어와도 주문은 하나이고 재고는 한 번만 줄어든다. `NewSchemaConstraintTest` · 같은 회원은 같은 멱등 키로 주문을 두 번 넣을 수 없다 / 상품 금액에서 할인을 뺀 값이 결제 금액과 다르면 넣을 수 없다 |
| `order/service/OrderStepObserver.passed(PENDING_RECORDED)` | 커밋 ①과 ② 사이의 지점이다. 운영에서는 아무것도 하지 않고, 테스트가 여기서 예외를 던져 프로세스 중단을 재현한다 | 없음 | `OrderCrashRecoveryTest` · 주문 기록 뒤 레거시 차감 전에 죽으면 주문만 PENDING 으로 남고 복구가 FAILED 로 끝낸다 |

### 4. 레거시에서 자원 확보 (레거시 DB 커밋 ②)

| 클래스.메서드 | 하는 일 | 커밋 | 증명하는 테스트 |
|---|---|---|---|
| `order/service/OrderResourceMapping.kt`의 `CheckoutOrder.toResources` | 방금 기록한 주문과 품목에서 레거시에 차감할 내용(`OrderResources`)을 만든다 | 없음 | (아래 `reserve` 테스트들이 함께 확인한다) |
| `legacy/resource/LegacyResourceAdapter.reserve` | 레거시 트랜잭션 하나를 연다(`TransactionTemplate`). 아래 네 단계를 그 안에서 실행하고, 하나라도 조건에 맞지 않으면 전부 롤백한 뒤 `Rejected(이유)`를 돌려준다. 모두 되면 커밋하고 `Reserved`를 돌려준다 | **레거시 DB ②** | `OrderPlacementTest` · 주문하면 신규 DB 에 RESERVED 주문이 남고 레거시 재고가 줄고 반영 기록 A 가 남는다 |
| └ `legacy/mapper/LegacyResourceMapper.insertApplyMarker` | 반영 기록(`TB_NEW_ORD_APPLY`)에 `'A'`를 가장 먼저 넣는다. 이미 있으면 아무것도 바꾸지 않고 `AlreadyDecided`를 돌려준다 | | `LegacyResourceAdapterTest` · 같은 주문의 확보를 반복해도 재고와 포인트는 한 번만 줄어든다 / 반환한 주문을 다시 확보할 수 없다. `OrderCrashRecoveryTest` · 복구가 실패로 끝낸 주문의 차감이 뒤늦게 도착해도 재고는 줄지 않는다 |
| └ `LegacyResourceAdapter.deductStocks` → `LegacyResourceMapper.deductStock` | 상품 코드 순으로 재고를 조건부 UPDATE로 줄인다. 판매 중이고, 재고가 충분하고, 가격이 2에서 읽은 값과 같을 때만 줄어든다 | | `OrderPlacementTest` · 재고보다 많이 주문하면 FAILED 가 되고 레거시에는 아무것도 남지 않는다 / 금액 계산과 재고 차감 사이에 가격이 바뀌면 차감하지 않고 FAILED 가 된다. `LegacyResourceAdapterTest` · 여러 상품 중 하나라도 재고가 부족하면 다른 상품도 차감하지 않는다. `OrderConcurrencyTest` · 재고 3개인 상품에 20명이 동시에 주문하면 3명만 성공하고 재고는 0 이 된다 |
| └ `LegacyResourceAdapter.useCoupon` → `LegacyResourceMapper.useCoupon` | 쿠폰을 쓰는 주문이면 본인 소유의 미사용 쿠폰일 때만 사용 처리하고 주문번호를 남긴다 | | `OrderPlacementTest` · 쿠폰과 포인트를 쓰면 재고와 함께 레거시에서 차감된다. `OrderConcurrencyTest` · 같은 쿠폰으로 동시에 5건을 주문해도 쿠폰은 한 주문에만 쓰인다 |
| └ `LegacyResourceAdapter.deductPoint` → `LegacyResourceMapper.deductPoint` | 포인트를 쓰는 주문이면 잔액이 충분할 때만 줄인다 | | `OrderPlacementTest` · 포인트가 부족하면 먼저 차감한 재고와 쿠폰까지 함께 롤백된다 |
| `OrderStepObserver.passed(LEGACY_RESERVED)` | 커밋 ②와 ③ 사이의 지점이다 | 없음 | `OrderCrashRecoveryTest` · 레거시 차감 뒤 주문 상태 반영 전에 죽으면 재고만 줄어 있고 복구가 RESERVED 로 진행시킨다 |

### 5. 주문을 RESERVED 로 변경 (신규 DB 커밋 ③)

| 클래스.메서드 | 하는 일 | 커밋 | 증명하는 테스트 |
|---|---|---|---|
| `OrderService.settle` | 4의 결과에 따라 갈 상태를 정한다. `Reserved`면 `RESERVED`, `Rejected`면 `FAILED`와 실패 사유, `AlreadyDecided`면 `OrderSupport.settleByMarker`로 반영 기록에 따라 정한다 | 없음 | `OrderPlacementTest` · 주문하면 신규 DB 에 RESERVED 주문이 남고 … / 재고보다 많이 주문하면 FAILED 가 되고 … |
| `OrderWriter.transition` (`@NewTransactional`) → `OrderRepository.transition` | `UPDATE … WHERE status = 'PENDING'`으로 상태를 바꾼다. 복구 작업자가 먼저 바꿨으면 영향 행 수가 0이고 아무것도 바뀌지 않는다 | **신규 DB ③** | `OrderConcurrencyTest` · 복구와 지연된 차감이 동시에 실행돼도 주문 상태와 재고는 같은 결론에 이른다. `OrderCrashRecoveryTest` · 복구를 여러 번 돌려도 결과가 같다 |

### 6. 결과 반환

| 클래스.메서드 | 하는 일 | 커밋 | 증명하는 테스트 |
|---|---|---|---|
| `OrderRepository.findByOrderNo` → `order/dto/OrderDto.kt`의 `toResult` | 주문을 다시 읽어 `OrderResult`로 바꿔 돌려준다. 다시 읽는 이유는 복구 작업자가 먼저 결론을 냈을 수 있어서다 | 없음 | `OrderPlacementTest` · 주문하면 신규 DB 에 RESERVED 주문이 남고 … (`result.status`, `result.payAmount`를 확인한다) |

## 전체 흐름을 한 번에 확인하는 테스트

`order/OrderPlacementTest.kt`의 첫 테스트가 1~6을 끝까지 지나간 결과를 두 DB에서 확인한다.

```kotlin
@Test
fun `주문하면 신규 DB 에 RESERVED 주문이 남고 레거시 재고가 줄고 반영 기록 A 가 남는다`() {
    val result = orderService.placeOrder(orderCommand(G001, qty = 2))

    assertThat(result.status).isEqualTo(OrderStatus.RESERVED)   // 신규 DB: 커밋 ③
    assertThat(result.payAmount).isEqualTo(56_000)              // 2의 금액 계산 (28,000 × 2)
    assertThat(stockOf(G001)).isEqualTo(G001_STOCK - 2)         // 레거시 DB: 재고 차감
    assertThat(markerOf(result.orderNo)).isEqualTo("A")         // 레거시 DB: 반영 기록
}
```

주석은 이 문서에서 붙인 것이다. `stockOf`, `markerOf`는 애플리케이션 코드를 거치지 않고 레거시 DB를 직접 읽는다(`support/IntegrationTest`).

## RESERVED 가 되지 않는 경우

| 어디서 | 결과 | 증명하는 테스트 |
|---|---|---|
| 1에서 거절 | 예외(`ORDER_IDEMPOTENCY_KEY_REUSED`). 같은 멱등 키의 기존 주문은 그대로이고 새 주문은 기록되지 않는다 | `OrderPlacementTest` · 같은 멱등 키로 수량이 다른 요청을 보내면 … |
| 2에서 거절 | 예외. 주문이 기록되지 않는다 | 2의 테스트들 |
| 3과 4 사이에서 중단 | 주문은 `PENDING`. 복구 작업자가 거부 기록 `'X'`를 넣고 `FAILED`로 끝낸다. 복구는 주문별로 예외를 잡으므로 다른 주문의 복구 실패가 이 주문의 복구를 막지 않는다 | `OrderCrashRecoveryTest` · 주문 기록 뒤 레거시 차감 전에 죽으면 … / 한 주문의 복구가 예외로 끝나도 나머지 주문은 복구되고 실패한 주문은 다음 실행에서 복구된다 |
| 4에서 차감이 거절됨 | 레거시는 전부 롤백되고 주문은 `FAILED`(실패 사유 포함) | `OrderPlacementTest` · 재고보다 많이 주문하면 … / 포인트가 부족하면 … |
| 4와 5 사이에서 중단 | 재고는 줄었고 주문은 `PENDING`. 복구 작업자가 반영 기록 `'A'`를 보고 `RESERVED`로 진행시킨다 | `OrderCrashRecoveryTest` · 레거시 차감 뒤 주문 상태 반영 전에 죽으면 … |

## 테스트가 없는 곳

이 흐름에서 테스트가 직접 확인하지 않는 곳이다.

- 4가 거절이 아니라 오류(DB 장애 등)로 끝나는 경우. 주문이 `PENDING`에 남는 것은 3과 4 사이 중단 테스트와 같은 상태다
- 3에서 INSERT가 거절된 뒤(동시에 들어온 같은 멱등 키) 요청 내용이 달라 거절되는 경우. 비교 자체는 1의 테스트가 확인하고, 동시 요청 테스트는 내용이 같은 경우만 본다
