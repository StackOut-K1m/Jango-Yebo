-- =============================================================================
-- P1-06 원자재 가격 지표를 범위에서 제외한다 — 예측 옵션에서 토글을 뺀다
--
-- V7 이 이 컬럼을 만들 때 근거는 API 10.7 의 여섯 필드였고, 주석은 "P1-06." 한 줄이었다.
-- 그 P1-06(원자재 가격 지표)이 SRS v1.10 에서 결번 처리돼 기준 자체가 사라졌다.
-- KAMIS·ECOS 수집 배치도, COMMODITY_INDEX·BUSINESS_INGREDIENT 도 만들지 않는다.
--
-- 값을 잃지 않는다. 컬럼은 NOT NULL DEFAULT FALSE 였고 이 토글을 켜도 곡선이 달라지지
-- 않아(ForecastRunWorker 가 appliedOptions 에 늘 false 를 넣었다) 켤 이유가 없었다.
--
-- 남기는 것과 지우는 것 — apply_seasonality(P1-07)·apply_vat_reserve(P1-11) 는 그대로
-- 둔다. 두 기능은 아직 범위 안이고 저장된 의사를 나중에 읽는다. 원자재만 다르다.
-- =============================================================================

ALTER TABLE forecast.forecast_setting
    DROP COLUMN apply_material_index;
