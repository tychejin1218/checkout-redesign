-- ============================================================
-- 장바구니–주문–결제 과제 · 신규 DB (SQLite3)
-- 애플리케이션과 테스트가 시작할 때 이 스크립트로 DB 를 새로 만든다.
-- ============================================================

-- 연결 확인용
CREATE TABLE app_meta (
  meta_key    TEXT PRIMARY KEY,   -- 항목 이름
  meta_value  TEXT NOT NULL       -- 값
);

INSERT INTO app_meta (meta_key, meta_value) VALUES
('db_name', 'new');

-- 주문. 금액과 품목은 주문 시점의 스냅샷이며 이후 바뀌지 않는다. 바뀌는 것은 status 뿐이다.
CREATE TABLE orders (
  order_no         TEXT PRIMARY KEY,     -- 주문번호 20자. 'N' + 한국 날짜 yyMMdd + 무작위 13자
  member_no        INTEGER NOT NULL,     -- 레거시 TB_MEMBER.MBR_NO
  status           TEXT NOT NULL         -- PENDING 의도 기록 / RESERVED 자원 확보 / PAID 결제 완료
                   CHECK (status IN ('PENDING', 'RESERVED', 'PAID', 'FAILED', 'CANCELING', 'CANCELED')),
                                         -- FAILED 확보 실패 / CANCELING 반환 진행 중 / CANCELED 반환 완료
  fail_reason      TEXT,                 -- FAILED 인 이유
  item_amount      INTEGER NOT NULL CHECK (item_amount >= 0),      -- 상품 금액 합계
  discount_amount  INTEGER NOT NULL CHECK (discount_amount >= 0),  -- 쿠폰 할인
  pay_amount       INTEGER NOT NULL CHECK (pay_amount >= 0),       -- 결제할 금액 (포인트 포함)
  point_amount     INTEGER NOT NULL CHECK (point_amount >= 0),     -- 그중 내부 포인트로 내는 금액
  coupon_no        TEXT,                 -- 사용한 쿠폰 (레거시 TB_COUPON.CPN_NO)
  idempotency_key  TEXT NOT NULL,        -- 같은 요청의 재시도를 한 주문으로 묶는 키. 회원별로 유일하다
  created_at       INTEGER NOT NULL,     -- epoch millis (UTC). 신규 DB 의 시각은 모두 UTC 기준이다
  updated_at       INTEGER NOT NULL,     -- epoch millis (UTC). 상태가 바뀔 때마다 갱신
  CHECK (item_amount - discount_amount = pay_amount),
  CHECK (point_amount <= pay_amount),
  UNIQUE (member_no, idempotency_key)
);

-- 복구 작업자가 중간 상태에 머문 주문을 찾는 조회용
CREATE INDEX idx_orders_status_updated_at ON orders (status, updated_at);

-- 주문 품목. 단가와 상품명은 주문 시점 값이다.
CREATE TABLE order_item (
  order_no    TEXT NOT NULL              -- 주문이 없는 품목은 넣을 수 없다 (PRAGMA foreign_keys = ON)
              REFERENCES orders (order_no),
  seq         INTEGER NOT NULL,          -- 주문 안에서의 순번 (1부터)
  goods_cd    TEXT NOT NULL,             -- 레거시 TB_GOODS.GOODS_CD
  goods_name  TEXT NOT NULL,
  qty         INTEGER NOT NULL CHECK (qty > 0),
  unit_price  INTEGER NOT NULL CHECK (unit_price >= 0),
  PRIMARY KEY (order_no, seq)
);
