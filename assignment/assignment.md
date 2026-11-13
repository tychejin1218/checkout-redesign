# 장바구니-주문-결제 흐름 재설계 과제 (BE)

## 안내

- AI 도구 사용은 제한하지 않습니다.
- 이 과제는 코드 완성도가 아니라 설계와 판단을 평가합니다.
- 요구사항은 의도적으로 열려 있습니다.

---

## 1. 배경

오래 운영되어 온 커머스 서비스의 **장바구니 → 주문 → 결제** 흐름을 신규 시스템으로 점진 전환하려 합니다.

이 흐름을 떠받치는 시스템은 10년 넘게 손을 거치며 하나의 레거시로 굳어졌습니다. 장바구니·상품·재고·가격·주문·결제·포인트가 한 덩어리로 얽혀 있고, 설계 문서와 당시 개발자는 남아 있지 않습니다.

- 서비스는 멈출 수 없습니다.
- 세일·오픈 시점에 동일 상품으로 주문이 몰립니다.
- 상품 가격은 운영 중 수시로 바뀝니다.
- 하나의 결제에 외부 PG, 제휴 포인트, 내부 포인트, 쿠폰 등이 함께 사용될 수 있습니다.
- 전환 기간 동안 레거시 DB와 신규 시스템은 공존합니다.

---

## 2. 레거시 DB 스키마

현재 운영 중인 레거시 통합 DB의 핵심 테이블입니다. (아래 스키마와 초기 데이터는 실행 가능한 `legacy_db.sql` 파일로도 함께 제공됩니다.)

```sql
CREATE TABLE TB_MEMBER (
  MBR_NO     INTEGER PRIMARY KEY,   -- 회원번호
  MBR_ID     TEXT,
  MBR_NM     TEXT,
  POINT_AMT  INTEGER DEFAULT 0,     -- 보유 포인트
  USE_YN     TEXT DEFAULT 'Y',
  REG_DT     TEXT                   -- 'YYYYMMDDHHMISS'
);

CREATE TABLE TB_GOODS (
  GOODS_CD   TEXT PRIMARY KEY,      -- 상품코드
  GOODS_NM   TEXT,
  SALE_PRC   INTEGER,              -- 판매가
  STOCK_QTY  INTEGER,              -- 재고수량
  SALE_STAT  TEXT,                 -- '01' 판매중 / '02' 품절 / '03' 판매중지
  REG_DT     TEXT,
  UPD_DT     TEXT
);

CREATE TABLE TB_CART (
  CART_SEQ   INTEGER PRIMARY KEY,
  MBR_NO     INTEGER,
  GOODS_CD   TEXT,
  CART_QTY   INTEGER,
  REG_DT     TEXT
);

CREATE TABLE TB_ORDER (
  ORD_NO     TEXT PRIMARY KEY,      -- 주문번호
  MBR_NO     INTEGER,
  ORD_AMT    INTEGER,              -- 주문 상품 금액 합계
  DC_AMT     INTEGER,              -- 총 할인 금액
  PAY_AMT    INTEGER,              -- 최종 결제 금액
  ORD_STAT   TEXT,                 -- '00' 접수 / '10' 결제대기 / '20' 결제완료 / '90' 취소
  ORD_DT     TEXT,
  UPD_DT     TEXT
);

CREATE TABLE TB_PAY (
  PAY_SEQ        INTEGER PRIMARY KEY,
  ORD_NO         TEXT,
  PAY_STAT       TEXT,             -- 'N' / 'Y' / 'C'(취소)
  PAY_MTHD       TEXT,             -- 대표 결제수단 코드
  PG_CORP        TEXT,             -- PG사
  PG_AMT         INTEGER,
  PG_APPROVE_NO  TEXT,             -- PG 승인번호
  CARD_NO        TEXT,
  VBANK_BANK     TEXT,             -- 가상계좌 은행
  VBANK_NO       TEXT,
  VBANK_DT       TEXT,
  PNT_AMT        INTEGER,          -- 내부 포인트 사용액
  MILE_CORP      TEXT,             -- 제휴 포인트 제휴사
  MILE_AMT       INTEGER,          -- 제휴 포인트 사용액
  MILE_APPROVE_NO TEXT,
  CPN_NO         TEXT,             -- 사용 쿠폰번호
  CPN_AMT        INTEGER,          -- 쿠폰 할인액
  PAY_DT         TEXT,
  UPD_DT         TEXT
);

CREATE TABLE TB_ORDER_ITEM (
  ORD_NO     TEXT,
  ORD_SEQ    INTEGER,
  GOODS_CD   TEXT,
  ORD_QTY    INTEGER,
  ORD_PRC    INTEGER,              -- 주문 시점 단가
  PRIMARY KEY (ORD_NO, ORD_SEQ)
);

CREATE TABLE TB_COUPON (
  CPN_NO     TEXT PRIMARY KEY,     -- 쿠폰번호
  MBR_NO     INTEGER,
  CPN_NM     TEXT,
  DC_AMT     INTEGER,             -- 할인 금액
  USE_YN     TEXT DEFAULT 'N',    -- 사용 여부
  USE_ORD_NO TEXT,                -- 사용된 주문번호
  REG_DT     TEXT
);
```

초기 데이터 예시입니다.

```sql
INSERT INTO TB_MEMBER (MBR_NO, MBR_ID, MBR_NM, POINT_AMT, USE_YN, REG_DT) VALUES
(1001, 'hong',  '홍길동', 5000,   'Y', '20231102093000'),
(1002, 'kim',   '김영희', 120000, 'Y', '20240215140500'),
(1003, 'lee',   '이철수', 0,      'Y', '20250710101200');

INSERT INTO TB_GOODS (GOODS_CD, GOODS_NM, SALE_PRC, STOCK_QTY, SALE_STAT, REG_DT, UPD_DT) VALUES
('G001', '자바의 정석',   28000, 50,  '01', '20230101000000', '20260225120000'),
('G002', '코틀린 인 액션', 36000, 3,   '01', '20230101000000', '20260228090000'),
('G003', '리팩터링',      45000, 0,   '02', '20230101000000', '20260220180000'),
('G004', '도메인 주도 설계', 40000, 120, '01', '20230101000000', '20260101000000');

INSERT INTO TB_CART (CART_SEQ, MBR_NO, GOODS_CD, CART_QTY, REG_DT) VALUES
(1, 1001, 'G001', 2, '20260301084500'),
(2, 1001, 'G004', 1, '20260301084700'),
(3, 1002, 'G002', 1, '20260301090000');

INSERT INTO TB_ORDER (ORD_NO, MBR_NO, ORD_AMT, DC_AMT, PAY_AMT, ORD_STAT, ORD_DT, UPD_DT) VALUES
('ORD20260301001', 1001, 96000, 3000, 93000, '20', '20260301091500', '20260301091520'),
('ORD20260301002', 1002, 36000, 0,     36000, '20', '20260301093000', '20260301093010'),
('ORD20260301003', 1003, 80000, 0,     80000, '10', '20260301094500', '20260301094500'),
('ORD20260228009', 1001, 84000, 5000, 79000, '90', '20260228173000', '20260301101000'),
('ORD20260301004', 1002, 28000, 0,     28000, '20', '20260301110000', '20260301110000'),
('ORD20260301005', 1002, 96000, 10000, 86000, '20', '20260301120000', '20260301150000');

INSERT INTO TB_ORDER_ITEM (ORD_NO, ORD_SEQ, GOODS_CD, ORD_QTY, ORD_PRC) VALUES
('ORD20260301001', 1, 'G001', 2, 28000),
('ORD20260301001', 2, 'G004', 1, 40000),
('ORD20260301002', 1, 'G002', 1, 36000),
('ORD20260301003', 1, 'G004', 2, 40000),
('ORD20260228009', 1, 'G001', 3, 28000),
('ORD20260301004', 1, 'G001', 1, 28000),
('ORD20260301005', 1, 'G001', 2, 28000),
('ORD20260301005', 2, 'G004', 1, 40000);

INSERT INTO TB_PAY (PAY_SEQ, ORD_NO, PAY_STAT, PAY_MTHD, PG_CORP, PG_AMT, PG_APPROVE_NO, CARD_NO, VBANK_BANK, VBANK_NO, VBANK_DT, PNT_AMT, MILE_CORP, MILE_AMT, MILE_APPROVE_NO, CPN_NO, CPN_AMT, PAY_DT, UPD_DT) VALUES
(1, 'ORD20260301001', 'Y', 'CARD',  'TOSS', 80000, 'PG20260301001', '123456******1234', NULL, NULL, NULL, 13000, NULL, NULL, NULL, 'CPN001', 3000, '20260301091520', '20260301091520'),
(2, 'ORD20260301002', 'Y', 'CARD',  'NICE', 20000, 'PG20260301002', '400012******5678', NULL, NULL, NULL, 6000, 'OKCASHBAG', 10000, 'M202603010001', NULL, NULL, '20260301093010', '20260301093010'),
(3, 'ORD20260301003', 'N', 'VBANK', 'KCP',  80000, NULL, NULL, '국민은행', '01234567890123', '20260303235959', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '20260301094500'),
(4, 'ORD20260228009', 'C', 'CARD',  'TOSS', 79000, 'PG20260228009', '123456******1234', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'CPN002', 5000, '20260228173010', '20260301101000'),
(5, 'ORD20260301004', 'N', 'CARD',  'TOSS', 28000, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '20260301110000'),
(6, 'ORD20260301005', 'Y', 'CARD',  'TOSS', 79000, 'PG20260301005', '123456******1234', NULL, NULL, NULL, 7000, NULL, NULL, NULL, 'CPN003', 10000, '20260301120010', '20260301120010'),
(7, 'ORD20260301005', 'C', 'CARD',  'TOSS', 35833, 'PGC20260301005', '123456******1234', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '20260301150000', '20260301150000');

INSERT INTO TB_COUPON (CPN_NO, MBR_NO, CPN_NM, DC_AMT, USE_YN, USE_ORD_NO, REG_DT) VALUES
('CPN001', 1001, '3천원 할인', 3000,  'Y', 'ORD20260301001', '20260225000000'),
('CPN002', 1001, '5천원 할인', 5000,  'Y', 'ORD20260228009', '20260220000000'),
('CPN003', 1002, '1만원 할인', 10000, 'Y', 'ORD20260301005', '20260228000000');
```

---

## 3. 요구사항

1. 장바구니 → 주문 → 결제로 이어지는 흐름을 신규 시스템 관점에서 재설계하십시오.
2. 레거시 DB와 신규 DB를 어떻게 나눌지, 공존 기간 동안 데이터를 어떻게 다룰지 결정하고 근거를 밝히십시오.
3. 설계에서 **해결이 필요하다고 판단한(가장 위험한) 지점** 하나를 골라, **실행 가능한 코드와 테스트 코드**로 구현하여 증명하십시오. 전체를 구현할 필요는 없습니다.

---

## 4. 제출물

- `DESIGN.md` — 문제 정의, 전체 구조, 레거시/신규 DB 분리 결정과 근거, 세운 가정
- **핵심 구현 + 테스트** — 요구사항 3의 **실행 가능한 코드와 테스트 코드**, 실행 방법(`README.md`)
- `adr/` — 핵심 설계 결정 최소 3건: 맥락 / 고려한 대안 / 선택과 근거 / 버린 이유와 trade-off
- `AI_REFLECTION.md` — AI에 맡긴 것과 직접 판단한 것, 거부·수정한 제안과 이유. 사용한 프롬프트는 `prompts/used_prompts.md`로 첨부

---

## 5. 기술 제약

- 언어: **Kotlin** (필수)
- 저장소: **SQLite3** (별도 인프라 없이 실행 가능하도록). 레거시 DB와 신규 DB를 어떻게 표현할지(스키마·분리 방식)는 당신의 설계입니다.
- 프레임워크·라이브러리·프로젝트 구조는 자유
- 제출한 **핵심 구현은 반드시 실행 가능**해야 합니다.

---

## 6. 제출

- 형식: GitHub 레포지토리 또는 ZIP (`DESIGN.md` + 핵심 구현/테스트 + `adr/` + `AI_REFLECTION.md` + `prompts/used_prompts.md`)
- 기한: 제공일로부터 **3일**

**실격 요건** - 제출한 코드는 실행 가능해야 하며, **실행되지 않으면 실격**입니다. - **AI 프롬프트를 포함하여 제출**해야 하며, 제출하지 않으면 실격입니다. - AI 프롬프트는 **Link로 제출할 수 없으며, `used_prompts.md` 만으로 확인 가능**해야 합니다.
