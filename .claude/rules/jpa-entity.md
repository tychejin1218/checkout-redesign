---
paths:
  - "src/main/kotlin/**/domain/entity/**/*.kt"
  - "src/main/resources/db/new/**/*.sql"
---
# JPA Entity (신규 DB)

- `data class` 금지 (equals/hashCode/toString이 연관관계·프록시와 충돌)
- `allOpen`·`plugin.jpa`가 build.gradle.kts에 설정되어 있으므로 `open`/기본 생성자를 직접 쓰지 않는다
- 주문처럼 상태 전이가 경합하는 엔티티는 프로퍼티를 `val` 로 두고 상태를 Repository 의 조건부 UPDATE(`OrderRepository.transition`)로만 바꾼다. 엔티티를 고쳐 저장하면 다른 쪽의 전이를 덮어쓴다
- 그 밖의 상태 변경은 `var ... protected set` + 의미 있는 메서드
- 자동 증가 PK 는 `var id: Long? = null` + `protected set`. 주문번호처럼 애플리케이션이 만드는 PK 는 `val`
- 연관관계는 `@ManyToOne(fetch = FetchType.LAZY)`. 필요 없는 양방향 매핑(`@OneToMany`)은 만들지 않는다
- 상태 값은 enum + `@Enumerated(EnumType.STRING)`. DDL 의 `CHECK` 와 값을 맞춘다
- Hibernate 는 DDL 을 실행하지 않는다(`ddl-auto: none`). 테이블은 `resources/db/new/new_db.sql` 에 추가하고, 컬럼마다 `--` 주석으로 뜻을 적는다
- 테이블·컬럼 이름은 소문자 snake_case 로 쓴다(레거시 `TB_*` 와 구분)
- 시간은 UTC. `CheckoutApplication.main`이 JVM 기본 시간대를 UTC 로 고정한다
- JPQL 예약어와 겹치는 이름은 피한다(`Order` 대신 `CheckoutOrder`)
- 복합 PK 는 `@EmbeddedId` + `equals`/`hashCode` 를 직접 구현한 `@Embeddable` 클래스(`OrderItemId`)
- 일시 컬럼은 epoch millis(`INTEGER`, `Long`)로 둔다. SQLite 에는 날짜 타입이 없어 드라이버·방언의 변환에 기대지 않는다
- PK 를 애플리케이션이 만드는 엔티티(`CheckoutOrder`, `OrderItem`)의 INSERT 는 `EntityManager.persist` 로 한다. `Repository.save` 는 merge 로 동작해 PK 가 같은 기존 행을 덮어쓴다
- 신규 테이블끼리의 참조는 DDL 에 `REFERENCES` 로 선언한다. 신규 DB 는 `foreign_keys` 가 켜져 있다. 참조되는 테이블을 스크립트에서 먼저 만든다(초기화가 만든 순서의 역순으로 지운다). 레거시 테이블을 가리키는 컬럼(`member_no`, `goods_cd`, `coupon_no`)은 DB 가 달라 외래 키를 걸 수 없다
