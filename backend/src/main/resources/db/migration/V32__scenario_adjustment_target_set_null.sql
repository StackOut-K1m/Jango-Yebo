-- =============================================================================
-- 시나리오 조정의 대상 외래키를 ON DELETE SET NULL 로 (API 11.2 · ERD v1.18)
--
-- V1 은 fk_sa_planned·fk_sa_pattern 에 ON DELETE 절을 두지 않았다(NO ACTION). 시나리오
-- 구현이 없는 동안은 scenario_adjustment 에 행을 만드는 경로 자체가 없어 드러나지 않았지만,
-- 저장된 시나리오가 예정 거래를 가리키는 순간 그 예정 거래를 지우는 모든 경로가 외래키
-- 위반으로 막힌다. 전용 예외 핸들러가 없어 사용자에게는 500 으로 나간다.
--
--   · 사용자 예정 거래 삭제        PlannedTransactionController.delete (API 9.4)
--   · 패턴 수정·중지의 회차 재생성  RecurringPatternService.update → deleteRegenerableOf
--   · 영업일 달력 변경              RecurringPatternService.refreshPlanned → deleteRegenerable
--   · 대출 동기화 재생성            LoanPlannedGenerator.regenerate → deleteRegenerableLoansOf
--   · 계좌 삭제 purge               ForecastAccountPurge → deleteOfAccount · deleteByAccountId
--
-- 마지막 것이 특히 나쁘다 — 계좌 하나를 지우려다 purge 전체가 되감긴다.
--
-- CASCADE 가 아니라 SET NULL 인 이유: 조정 행을 함께 지우면 저장한 시나리오에서 파라미터가
-- 소리 없이 사라진다. 사용자는 「정산 지연 + 지출일 변경」으로 저장했는데 다음에 열면 지연만
-- 남아 있고, 숫자가 달라진 이유를 어디에서도 설명할 수 없다. 대상만 비우고 details 에 남긴
-- 원래 id 로 「대상이 삭제되어 반영하지 않았다」를 warnings 로 말하는 쪽이 맞다.
--
-- 지우는 데이터가 없다. 이 시점까지 행을 만드는 경로가 없었으므로 백필도 정리도 없이
-- 제약만 다시 건다(V29 가 ck_sa_type 을 다시 걸 때와 같은 사정이다).
-- =============================================================================

ALTER TABLE forecast.scenario_adjustment
    DROP CONSTRAINT fk_sa_planned,
    DROP CONSTRAINT fk_sa_pattern;

ALTER TABLE forecast.scenario_adjustment
    ADD CONSTRAINT fk_sa_planned FOREIGN KEY (target_planned_transaction_id)
        REFERENCES forecast.planned_transaction (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_sa_pattern FOREIGN KEY (target_pattern_id)
        REFERENCES forecast.recurring_pattern (id) ON DELETE SET NULL;

-- 예정 거래 벌크 삭제(패턴 회차 재생성·달력 변경)마다 PostgreSQL 이 참조 무결성을 확인하려
-- 이 표를 훑는다. 행이 적은 표지만 인덱스가 없으면 그때마다 순차 스캔이고, 재생성은 패턴
-- 개수만큼 반복된다. 참조가 있는 행만 담는 부분 인덱스로 둔다 — 대부분의 조정은 대상이 없다.
CREATE INDEX ix_sa_target_planned ON forecast.scenario_adjustment (target_planned_transaction_id)
    WHERE target_planned_transaction_id IS NOT NULL;

CREATE INDEX ix_sa_target_pattern ON forecast.scenario_adjustment (target_pattern_id)
    WHERE target_pattern_id IS NOT NULL;
