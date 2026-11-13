---
paths:
  - "src/main/kotlin/**/domain/repository/**/*.kt"
  - "src/main/kotlin/**/legacy/**/*.kt"
  - "src/main/kotlin/**/service/**/*.kt"
  - "src/main/resources/mapper/**/*.xml"
  - "src/main/resources/db/**/*.sql"
---
# 데이터 접근 (JPA · MyBatis · SQLite)

## 어느 DB 를 무엇으로

- 신규 DB(장바구니·주문·결제)는 Spring Data JPA. Repository 는 `domain/repository/`. 단건은 nullable 반환(`fun findByOrderNo(orderNo: String): CheckoutOrder?`)
- 레거시 DB(`TB_*`)는 MyBatis. Mapper 인터페이스는 `legacy/mapper/` 에 `@Mapper` 로 두고, SQL 은 `resources/mapper/legacy/{Mapper 이름}.xml` 에 쓴다. 어노테이션 SQL(`@Select`)은 쓰지 않는다
- 레거시 테이블에 JPA Entity 를 만들지 않고, 신규 테이블을 MyBatis 로 읽지 않는다
- 레거시 조회 결과는 `legacy/model/` 의 `data class` 로 받는다. 컬럼은 `map-underscore-to-camel-case` 로 매핑된다(`STOCK_QTY` → `stockQty`)
- Mapper 파라미터는 `@Param` 없이 이름으로 쓴다(`#{goodsCd}`, `javaParameters = true`). `${}` 문자열 치환은 쓰지 않는다
- 서비스는 레거시 Mapper 를 직접 쓰지 않는다. 재고·포인트·쿠폰은 `legacy/resource/LegacyResourcePort`, 회원 조회는 `legacy/resource/LegacyMemberPort` 를 거친다. 포트의 변경 연산은 주문번호 기준으로 멱등해야 한다

## 트랜잭션

- 신규 DB 는 `@NewTransactional`, 레거시 DB 는 `@LegacyTransactional`. `@Transactional` 을 그대로 쓰지 않는다
- 한 메서드에 두 어노테이션을 함께 붙이지 않는다. 두 DB 를 바꾸는 흐름은 DB 별 메서드로 나누고, 순서와 복구는 `DESIGN.md` 5장을 따른다
- 트랜잭션 어노테이션이 붙은 메서드를 같은 클래스 안에서 호출하면 프록시를 거치지 않아 트랜잭션이 열리지 않는다. 다른 빈으로 나눈다
- 트랜잭션 안에서 외부 호출(PG 등)을 하지 않는다
- 예외 없이 롤백하고 결과를 돌려줘야 하면(조건부 UPDATE 가 0 을 돌려준 경우) 어노테이션 대신 해당 DB 의 트랜잭션 매니저로 만든 `TransactionTemplate` 과 `setRollbackOnly()` 를 쓴다(`LegacyResourceAdapter`)
- Spring Data JPA Repository 의 메서드는 신규 DB 트랜잭션(`newTransactionManager`, 별칭 `transactionManager`)으로 실행된다

## SQLite

- 행 잠금이 없다. `SELECT ... FOR UPDATE` 는 문법 오류다. 쓰기는 DB 파일 단위로 직렬화된다
- 두 DataSource 모두 트랜잭션을 `BEGIN IMMEDIATE` 로 시작한다(`transaction_mode=IMMEDIATE`). 한 DB 파일의 트랜잭션은 한 번에 하나만 실행되고 나머지는 `busy_timeout` 만큼 기다린다. 트랜잭션은 짧게 유지한다
- 수량·잔액·상태 변경은 읽고 계산해서 덮어쓰지 않고 조건부 UPDATE 로 한다. 성공 여부는 영향 행 수(반환값 `Int`)로 판정한다
  ```sql
  UPDATE TB_GOODS SET STOCK_QTY = STOCK_QTY - #{qty}
   WHERE GOODS_CD = #{goodsCd} AND STOCK_QTY >= #{qty}
  ```
- 상태 전이도 같은 방식이다(`WHERE status = '이전 상태'`). JPA 에서는 `@Modifying @Query` 로 쓰고 반환값을 확인한다
- 타입이 느슨하다. 신규 테이블에는 `NOT NULL`·`CHECK`·`UNIQUE` 를 직접 적는다. 금액과 수량은 `INTEGER`
- 신규 DB 의 시각은 UTC epoch millis 다. 레거시의 일시는 `'YYYYMMDDHHMISS'` 문자열(`TEXT`)이고 한국 시간으로 가정한다. 레거시 테이블에 쓰는 일시와 주문번호의 날짜는 `legacy/LegacyTime` 으로 변환한다. 다른 곳에서 직접 포맷하지 않는다
- `ATTACH` 로 두 파일을 한 트랜잭션에 묶지 않는다
- 스키마를 바꾸면 `resources/db/new/new_db.sql` 을 고친다. `resources/db/legacy/legacy_db.sql` 은 `assignment/legacy_db.sql` 의 사본이므로 고치지 않는다. 레거시에 추가하는 테이블은 별도 스크립트(`legacy_new_ord_apply.sql`)에 두고 `checkout.datasource.legacy.init-scripts` 에 등록한다
