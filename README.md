# checkout-redesign

"장바구니-주문-결제 흐름 재설계" 과제 프로젝트.

| 문서 | 내용 |
|---|---|
| [DESIGN.md](DESIGN.md) | 문제 정의, 전체 구조, 레거시/신규 DB 분리 결정과 근거, 가정 |
| [adr/](adr/README.md) | 핵심 설계 결정 5건 (맥락 / 고려한 대안 / 선택과 근거 / 버린 이유와 trade-off) |
| [docs/erd/](docs/erd/new.md) | 신규 DB·레거시 DB ERD |
| [docs/order-flow.md](docs/order-flow.md) | 주문 요청부터 RESERVED 까지 거치는 클래스·메서드와 단계별 테스트 |
| [assignment/assignment.md](assignment/assignment.md) | 과제 원문 |
| [assignment/analysis.md](assignment/analysis.md) | 과제와 레거시 DB 분석 |
| [AI_REFLECTION.md](AI_REFLECTION.md) | AI에 맡긴 것과 직접 판단한 것, 거부·수정한 제안과 이유 |
| [prompts/used_prompts.md](prompts/used_prompts.md) | 사용한 AI 프롬프트 |

## 실행 방법

```bash
./gradlew test
```

- 필요한 것은 JDK 17 이상뿐이다. Docker, DB 서버 등 별도 인프라는 쓰지 않는다
- 컴파일과 테스트는 JDK 21 로 한다. JDK 21 이 없으면 Gradle 이 자동으로 내려받는다(처음 한 번, 인터넷 필요)
- 테스트 결과는 `build/reports/tests/test/index.html`

```bash
./gradlew build     # 컴파일 + ktlint + detekt + 테스트
./gradlew bootRun   # 애플리케이션 기동 (:9091)
```

## 저장소

SQLite3 파일 2개를 쓴다. 두 파일은 DataSource 와 트랜잭션 매니저가 따로이며, 하나의 트랜잭션으로 묶이지 않는다.

| | 신규 DB | 레거시 DB |
|---|---|---|
| 접근 | Spring Data JPA | MyBatis |
| 스키마·초기 데이터 | `src/main/resources/db/new/new_db.sql` | `src/main/resources/db/legacy/legacy_db.sql` (`assignment/legacy_db.sql` 의 사본) |
| 파일 (bootRun) | `data/new.db` | `data/legacy.db` |
| 파일 (test) | `build/test-db/new.db` | `build/test-db/legacy.db` |

DB 파일은 저장소에 포함하지 않는다. 애플리케이션과 테스트가 시작할 때 위 스크립트로 새로 만들고, 테스트는 매번 그 초기 상태에서 시작한다.

## 핵심 구현

가장 위험하다고 판단한 지점은 **레거시 DB와 신규 DB에 걸친 쓰기가 한쪽만 반영되는 것**이다(DESIGN.md 1.3, 5장).
주문 한 건은 신규 DB의 주문 기록과 레거시 DB의 재고·쿠폰·포인트 차감으로 나뉘고, 두 DB는 하나의 트랜잭션으로 묶이지 않는다.

```
① [신규 DB]   주문을 PENDING 으로 기록
② [레거시 DB] 반영 기록(TB_NEW_ORD_APPLY) + 재고·쿠폰·포인트 조건부 차감 (한 트랜잭션)
③ [신규 DB]   주문을 RESERVED 또는 FAILED 로 변경
```

어느 단계 사이에서 프로세스가 죽어도 복구 작업자가 레거시의 반영 기록을 보고 주문을 끝까지 진행하거나 실패로 끝낸다.

| 코드 | 역할 |
|---|---|
| `order/service/OrderService` | 주문 생성 (위 세 단계) |
| `order/service/OrderCancelService` | 결제 실패·만료 시 자원 반환 |
| `order/service/OrderRecoveryService` | 중간 상태에 머문 주문 복구 |
| `legacy/resource/LegacyResourceAdapter` | 레거시 자원의 확보·반환 (주문번호 기준 멱등) |

| 테스트 | 확인하는 것 |
|---|---|
| `OrderCrashRecoveryTest` | 각 단계 사이에서 중단한 뒤 복구하면 주문과 레거시 자원이 일치한다 |
| `OrderConcurrencyTest` | 재고 3개에 20건 동시 주문, 같은 멱등 키·쿠폰·취소의 동시 요청, 복구와 지연된 차감의 경합 |
| `LegacyResourceAdapterTest` | 확보·반환을 반복해도 레거시 자원은 한 번만 바뀐다 |
| `OrderPlacementTest` | 정상 주문, 재고·포인트 부족 시 전체 롤백, 금액·가격 변동 |
| `OrderRequestValidationTest` | 주문 기록 전의 요청 검증: 품목, 쿠폰, 포인트가 올바르지 않으면 거절 |
| `OrderCancelTest` | 취소와 미결제 만료 시 자원이 한 번만 돌아온다 |
| `TransactionManagerSeparationTest` | 두 DB의 트랜잭션이 독립이다 |

구현하지 않은 것과 SQLite 환경의 한계는 DESIGN.md 10장에 있다.
