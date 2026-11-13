## 프로젝트 개요

"장바구니-주문-결제 흐름 재설계" 과제 프로젝트. 과제 원문은 `assignment/assignment.md`, 분석은 `assignment/analysis.md`, 설계는 `DESIGN.md`, 설계 결정 기록은 `adr/`.
`../kotlin-springboot-boilerplate` 를 기준으로 구성했고, 코드는 Kotlin 공식 코딩 컨벤션과 Spring 공식 Kotlin 가이드를 따른다.

- Kotlin 2.3.21 (Spring Boot BOM의 `kotlin.version`과 맞춤) / Java 21 / Spring Boot 4.1.2 / Gradle 9.3.0
- 저장소는 SQLite3 파일 2개: 신규 DB(Spring Data JPA), 레거시 DB(MyBatis). Docker 등 별도 인프라 없음

## 작업 규칙

- **사용자가 보낸 프롬프트는 작업을 시작하기 전에 원문 그대로 `prompts/used_prompts.md` 에 추가한다.** 요약하거나 고치지 않는다. 선택지 버튼으로 답한 것도 어떤 질문에 대한 답인지와 함께 적는다. 프롬프트 누락은 과제 실격 사유다
- `assignment/` 의 원본(`legacy_db.sql`, PDF)은 수정하지 않는다. 실행용 사본은 `src/main/resources/db/legacy/legacy_db.sql` 이며 원본과 내용이 같아야 한다
- 제출한 코드는 `./gradlew test` 한 줄로 실행돼야 한다. 실행되지 않으면 과제 실격이다
- `src/main` 이나 `src/test` 의 코드를 바꾸면 응답을 끝내기 전에 `sync-docs` 스킬(`.claude/skills/sync-docs`)로 `DESIGN.md` 와 `docs/order-flow.md` 에 반영한다. 빠뜨리면 Stop 훅(`.claude/hooks/docs-sync-check.sh`)이 알린다

## 빌드 & 실행

```bash
./gradlew build          # 컴파일 + ktlint + detekt + 테스트
./gradlew ktlintFormat   # 포맷 자동 정렬 (코드 수정 후 항상 실행)
./gradlew test           # SQLite 파일만 사용. Docker 불필요
./gradlew bootRun        # :9091, data/new.db · data/legacy.db 를 새로 만든다
```

## 아키텍처

```
Controller → Service ─┬→ Repository (Spring Data JPA) → 신규 DB   (data/new.db)
                      └→ Mapper     (MyBatis XML)      → 레거시 DB (data/legacy.db)
```

- 기능 패키지: `com.example.checkout.{기능}/{controller,service,dto}`
- 신규 DB: Entity 는 `domain/entity/`, Repository 는 `domain/repository/`. 스키마는 `resources/db/new/new_db.sql`
- 레거시 DB: Mapper 인터페이스는 `legacy/mapper/`, 행을 담는 클래스는 `legacy/model/`, 서비스가 쓰는 포트는 `legacy/resource/`, SQL 은 `resources/mapper/legacy/*.xml`. 스키마는 `resources/db/legacy/legacy_db.sql`(원본 사본) + `legacy_new_ord_apply.sql`(추가 테이블)
- DataSource 와 트랜잭션 매니저는 DB 마다 따로 있다(`config/NewDataSourceConfig`, `config/LegacyDataSourceConfig`)
- 트랜잭션은 `@NewTransactional`(신규 DB) 또는 `@LegacyTransactional`(레거시 DB)로 연다. **`@Transactional` 을 그대로 쓰지 않는다.** 어느 DB 의 트랜잭션인지 항상 드러낸다
- 두 DB 를 하나의 트랜잭션으로 묶지 않는다. 한 메서드에서 두 DB 를 바꾸면 그 메서드의 트랜잭션에 속한 DB 만 롤백된다(`TransactionManagerSeparationTest`). DB 간 쓰기는 `DESIGN.md` 5장의 절차를 따른다(`order/service/OrderService`, `OrderCancelService`, `OrderRecoveryService`). SQLite `ATTACH`, JTA, `ChainedTransactionManager` 는 쓰지 않는다
- 신규 시스템과 신규 DB 는 UTC, 레거시 DB 에 쓰는 일시는 한국 시간이다(`legacy/LegacyTime`, `DESIGN.md` 4.6)
- ERD 는 `docs/erd/new.md`, `docs/erd/legacy.md`. 스키마를 바꾸면 함께 고친다
- 두 DB 파일은 애플리케이션이 시작할 때마다 스크립트로 새로 만든다(`config/SqliteDatabaseInitializer`). `.db` 파일은 커밋하지 않는다

## Kotlin 작성 규칙

Java 프로젝트에서 넘어올 때 바뀌는 점:

| Java | 이 프로젝트 |
|---|---|
| Lombok `@Builder`/`@Getter`/`@RequiredArgsConstructor` | 사용하지 않음. 주 생성자 + named argument + 기본값 |
| `XxxDto` outer class + static inner class | `{기능}/dto/XxxDto.kt` 파일 하나에 top-level `data class` 여러 개 |
| `ModelMapper` | 사용하지 않음. Entity → DTO 변환은 확장 함수 `fun CheckoutOrder.toResult()` |
| `Optional` | nullable 타입 + `?:` |
| MyBatis / QueryDSL | 신규 DB 는 Spring Data JPA(`@Query` 포함), 레거시 DB 는 MyBatis(XML 매퍼). QueryDSL·Kotlin JDSL 은 쓰지 않는다 |
| Checkstyle + PMD | ktlint + detekt |
| Mockito / `@MockitoBean` | MockK / springmockk `@MockkBean` |

- 단순 생성은 생성자 + named argument. 값을 계산해서 만들어야 할 때만 `companion object`의 팩토리 함수(`ErrorResponse.of(...)`)
- 의존성 주입은 생성자 주입만 쓰고 프로퍼티는 `private val`
- 컬렉션 반환 타입은 `List`/`Map` (Mutable 금지)
- null 이 올 수 있는 조회 결과는 nullable 타입(`CheckoutOrder?`)으로 받고 서비스에서 `?: throw ApiException(ApiStatus.ORDER_NOT_FOUND)`. `!!` 금지
- 스코프 함수는 객체 설정(`apply`)·null 처리(`?.let`) 정도로만 쓰고 중첩하지 않는다
- 로거: `private val log = LoggerFactory.getLogger(javaClass)`, 메시지는 `{}` placeholder
- 응답 코드(`statusCode`)는 `ApiStatus` enum 이름이다. 접두 규칙(`ORDER_*`, `VALIDATION_*`, `SYSTEM_*` 등)을 따른다
- 예외는 `throw ApiException(ApiStatus.XXX)`. HTTP 상태는 `ApiStatus.httpStatus` 에 묶여 있어 던지는 곳에서 지정하지 않는다
- 컨트롤러 응답: `BaseResponse.ok(data)` / `BaseResponse.ok()` (반환 타입 `BaseResponse<Unit>`)
- 요청 DTO 검증: `@field:` 없이 `@NotBlank(message = "...")` 사용 (`-Xannotation-default-target=param-property`). 메시지는 오류 응답에 그대로 노출된다

## 세부 규칙

`.claude/rules/` 의 규칙은 해당 경로의 파일을 다룰 때 자동으로 읽힌다.

- [데이터 접근 (JPA · MyBatis · SQLite)](.claude/rules/data-access.md): domain/repository, legacy, service, resources/mapper, resources/db
- [JPA Entity](.claude/rules/jpa-entity.md): domain/entity, resources/db/new
- [테스트](.claude/rules/testing.md): src/test

## 커밋

- `git config core.hooksPath .githooks` 를 한 번 해 두면 커밋할 때 `.githooks/pre-commit` 이 ktlint·detekt 를 돌린다. 테스트는 커밋 전에 `./gradlew build` 로 직접 돌린다
