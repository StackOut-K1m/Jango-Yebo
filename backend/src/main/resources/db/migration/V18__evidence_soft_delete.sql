-- =============================================================================
-- 증빙 삭제를 흔적으로 남긴다 (13.5 DELETE)
--
-- 행을 지우면 EVIDENCE_CORRECTION 이 같이 사라진다. 그 표는 「모델이 읽은 값을 사장님이
-- 얼마나 고쳤나」를 기록한 것이고, **실사용 정확도를 얻는 유일한 원천**이다(99_구현현황 §3.22).
-- 실험 세트 정확도는 우리가 고른 이미지에 대한 값이라 이것을 대신하지 못한다.
--
-- 반대로 이력만 남기고 증빙을 지우면, 어느 증빙의 이력인지 가리키는 곳이 사라져 떠돈다.
-- 그래서 증빙을 남기고 지워진 것으로 표시한다.
--
-- 파일도 지우지 않는다. 명세 13.5 가 "이미 신고자료 내보내기에 포함된 파일 원본은 아카이브에
-- 남는다"고 정했다 — 내보낸 신고 자료의 근거가 나중에 사라지면 그 자료를 다시 설명할 수 없다.
--
-- 부분 인덱스로 살아 있는 것만 빠르게 고른다. 목록은 사장님이 가장 자주 여는 화면이고,
-- 지워진 증빙은 거기 절대 나오면 안 된다.
-- =============================================================================

ALTER TABLE core.evidence
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by BIGINT;

COMMENT ON COLUMN core.evidence.deleted_at IS
    '지운 시각. NULL 이면 살아 있다. 행과 파일은 지우지 않는다 — 수정 이력이 실사용 정확도의 원천이다';
COMMENT ON COLUMN core.evidence.deleted_by IS
    '지운 사람. 되살릴 때와 "누가 지웠나"를 물을 때 쓴다';

CREATE INDEX ix_evidence_business_alive
    ON core.evidence (business_id, created_at DESC)
 WHERE deleted_at IS NULL;
