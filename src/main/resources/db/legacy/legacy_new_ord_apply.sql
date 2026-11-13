-- ============================================================
-- 레거시 DB 에 신규 시스템이 추가하는 테이블 (기존 TB_* 는 바꾸지 않는다)
-- legacy_db.sql 은 과제 원본의 사본이라 고치지 않고, 추가분은 이 파일에 둔다.
-- ============================================================

-- 신규 주문의 자원(재고·쿠폰·포인트) 차감이 레거시에 반영됐는지를 남기는 반영 기록.
-- 차감·반환과 같은 레거시 트랜잭션에서 기록한다 (DESIGN.md 5장).
CREATE TABLE TB_NEW_ORD_APPLY (
  ORD_NO      TEXT PRIMARY KEY,     -- 신규 주문번호
  APPLY_STAT  TEXT NOT NULL         -- 'A' 차감 적용됨 / 'R' 반환됨 / 'X' 적용 거부(선점)
              CHECK (APPLY_STAT IN ('A', 'R', 'X')),
  APPLY_DT    TEXT NOT NULL,        -- 'YYYYMMDDHHMISS'
  UPD_DT      TEXT NOT NULL
);
