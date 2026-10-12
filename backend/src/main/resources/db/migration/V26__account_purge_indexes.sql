-- =============================================================================
-- 계좌 삭제가 거래를 되짚을 때 쓰는 인덱스 (API 7.2 purge)
--
-- 계좌를 지우려면 그 계좌 거래를 가리키는 자식 행부터 지워야 한다. 술어가 전부
-- `transaction_id IN (SELECT id FROM core.transaction WHERE account_id = ?)` 인데,
-- **세 표에 transaction_id 로 시작하는 인덱스가 없어 매번 전체 스캔을 탄다.**
--
--   settlement_transaction      uk_st_pair  (settlement_id, transaction_id)      — 선두가 아니다
--   recurring_pattern_transaction uk_rpt_pair (recurring_pattern_id, transaction_id) — 선두가 아니다
--   loan_repayment              uk_lr_due / ix_lr_loan_status                    — loan_id 선두, 없음
--
-- 복합 인덱스는 선두 컬럼으로만 탐색에 쓰이므로 뒤에 있는 transaction_id 는 도움이 안 된다.
-- transaction_evidence 는 uk_te_pair 가 (transaction_id, evidence_id) 라 선두가 맞고,
-- transaction_classification_history 는 ix_tch_transaction 이 이미 있다 — 둘은 건드리지 않는다.
--
-- **삭제 때문에만 만드는 것이 아니다.** 셋 다 역방향 조회가 실제로 있는 자리다.
-- "이 입금이 어느 정산에 배분됐나", "이 거래가 어느 패턴의 근거인가", "이 출금이 어느 회차였나" —
-- 지금은 그 질문도 전부 전체 스캔이다. 삭제가 없었어도 언젠가 필요했을 인덱스다.
--
-- loan_repayment 는 부분 인덱스다. transaction_id 는 상환이 실제로 일어난 회차에만 채워지고
-- 예정 회차는 전부 NULL 이라, 조건을 달면 인덱스가 훨씬 작아진다.
-- =============================================================================

CREATE INDEX ix_st_transaction  ON core.settlement_transaction (transaction_id);
CREATE INDEX ix_rpt_transaction ON forecast.recurring_pattern_transaction (transaction_id);
CREATE INDEX ix_lr_transaction  ON core.loan_repayment (transaction_id)
    WHERE transaction_id IS NOT NULL;
