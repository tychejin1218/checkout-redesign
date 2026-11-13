---
paths:
  - "src/test/kotlin/**/*.kt"
---
# 테스트

- 함수명은 백틱 한글 문장 (`` fun `재고보다 많이 주문하면 실패한다`() ``), `@DisplayName` 불필요
- DB 를 쓰는 테스트는 `support/IntegrationTest` 상속. 실제 SQLite 파일 2개(`build/test-db/*.db`)를 쓰고, 테스트마다 두 DB 를 초기화 스크립트 직후 상태로 되돌린다
- 테스트 클래스에 `@Transactional` 을 붙여 롤백에 기대지 않는다. 두 DB 의 트랜잭션이 따로이고, 커밋된 결과와 여러 커넥션의 동작을 확인해야 하기 때문이다
- 레거시 초기 데이터(`legacy_db.sql`: 상품 4, 주문 6 등)에 의존하는 단정은 허용한다. 그 밖의 데이터는 테스트 안에서 만든다
- 동시성 테스트는 `support/runConcurrently` 로 스레드를 동시에 출발시킨다. 스레드마다 커넥션이 따로 잡힌다. 인메모리 SQLite 는 커넥션마다 별개의 DB 라서 쓰지 않는다
- 두 DB 의 커밋 사이에서 프로세스가 죽는 상황은 베이스의 `orderStepObserver` 목으로 재현한다: `every { orderStepObserver.passed(OrderStep.XXX, any()) } throws SimulatedCrash()`. 이후 정상 동작이 필요하면 `justRun { orderStepObserver.passed(any(), any()) }` 로 되돌린다
- 레거시 DB 의 결과는 애플리케이션 코드를 거치지 않고 베이스의 `stockOf`·`pointOf`·`couponUseOf`·`markerOf` 로 직접 확인한다
- 컨트롤러: `@WebMvcTest` + `@MockkBean` + MockMvc Kotlin DSL (`mockMvc.get("/x").andExpect { ... }`)
- 서비스 단위: MockK
- `IntegrationTest` 하위 클래스에 `@MockkBean`·`@TestConfiguration`·`@DynamicPropertySource` 를 더하면 스프링 컨텍스트를 새로 띄워 느려진다. 꼭 필요할 때만 쓴다
