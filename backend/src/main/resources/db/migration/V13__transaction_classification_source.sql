-- =============================================================================
-- 거래에 「누가 분류했는가」와 「왜 그렇게 봤는가」를 둔다 (API 8.2 classification)
--
-- 명세 8.2 는 목록의 각 행 안에 source 와 reason 을 넣으라고 한다. 두 값의 정본은
-- TRANSACTION_CLASSIFICATION_HISTORY 인데, 목록은 한 번에 20행이라 행마다 최신 이력을
-- 찾으면 이미 조건이 열 개 넘는 목록 쿼리에 조인이 하나 더 붙는다.
--
-- 그래서 쓰기 시점에 두 칸을 채우는 쪽을 택했다. 목록은 사장님이 가장 자주 여는 화면이고,
-- 읽기가 쓰기보다 훨씬 잦다. 값을 쓰는 곳이 Transaction.classifyByLlm 과 classifyByUser
-- 두 곳뿐이라 이력과 어긋날 여지도 작다 (팀 합의 2026-09-14).
--
-- 이력을 대신하지는 않는다. 여기 남는 것은 「지금 상태」 한 줄이고, 「어떻게 바뀌어 왔는가」는
-- 이력에만 있다 — few-shot 원천과 정확도 추적은 그대로 이력을 본다.
-- =============================================================================

ALTER TABLE core.transaction
    ADD COLUMN classification_source VARCHAR(20),
    ADD COLUMN classification_reason TEXT;

COMMENT ON COLUMN core.transaction.classification_source IS
    '마지막으로 분류한 주체. 값 집합은 ck_tch_classifier 와 같다. API 는 LLM 을 AI 로 내보낸다(8.2)';
COMMENT ON COLUMN core.transaction.classification_reason IS
    'LLM 이 준 설명. 사용자가 분류를 바꾸면 비운다 — 남겨 두면 바뀐 분류에 옛 근거가 붙는다';

-- 이력 테이블과 값 집합을 맞춘다. 한쪽만 늘면 조회 결과가 갈린다.
ALTER TABLE core.transaction
    ADD CONSTRAINT ck_tx_classification_source
        CHECK (classification_source IN ('RULE', 'DICTIONARY', 'LLM', 'USER'));

-- 이미 분류된 거래를 이력에서 메운다. 안 하면 그 행들만 영영 source 가 비어, 화면에서
-- 「AI 제안」과 「모름」이 같아 보인다. 거래당 가장 최근 이력 한 줄이 지금 상태다.
-- USER 확정 행은 reason 이 없으므로(사용자는 근거를 쓰지 않는다) 자연히 NULL 이 된다.
UPDATE core.transaction t
   SET classification_source = h.classifier_type,
       classification_reason = h.reason ->> 'text'
  FROM (SELECT DISTINCT ON (transaction_id)
               transaction_id, classifier_type, reason
          FROM core.transaction_classification_history
         ORDER BY transaction_id, created_at DESC, id DESC) h
 WHERE h.transaction_id = t.id;
