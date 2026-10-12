-- =============================================================================
-- 거래 카테고리 시드 (API 8.1 — 입금 7종 + 출금 16종 = 23종)
--
-- 이 값 집합은 세 곳의 단일 기준이다:
--   1. 규칙 기반 분류(P0-02)의 결과 도메인
--   2. LLM 분류(P1-01) 요청의 allowedCategories (API 20.3)
--   3. 프론트 카테고리 선택 UI
--
-- 변경 비용
--   싸다 : 추가 / active=false 비활성 / name 개명  → 배포 없이 UPDATE·INSERT
--   비싸다: code 변경 (규칙셋·few-shot·분류 이력이 code 에 묶인다)
--          분할·병합 (기존 거래 재분류 마이그레이션 필요)
-- 그래서 넓고 얕게 잡는다. 세분화가 필요해지면 하위 카테고리를 나중에 붙인다.
-- =============================================================================

INSERT INTO core.transaction_category (code, name, flow_type, sort_order) VALUES
    -- 입금 (7)
    ('CARD_SETTLEMENT',     '카드 정산금',        'DEPOSIT',    10),
    ('PLATFORM_SETTLEMENT', '플랫폼 정산금',      'DEPOSIT',    20),
    ('CASH_SALES',          '현금 매출',          'DEPOSIT',    30),
    ('TRANSFER_SALES',      '계좌이체 매출',      'DEPOSIT',    40),
    ('LOAN_DISBURSEMENT',   '대출 실행',          'DEPOSIT',    50),
    ('OWNER_FUNDING',       '대표자 자금 투입',   'DEPOSIT',    60),
    ('OTHER_INCOME',        '기타 입금',          'DEPOSIT',    70),
    -- 출금 (16)
    ('RENT',                '임차료',             'WITHDRAWAL', 110),
    ('PAYROLL',             '급여',               'WITHDRAWAL', 120),
    ('SOCIAL_INSURANCE',    '4대보험',            'WITHDRAWAL', 130),
    ('MATERIALS',           '재료비',             'WITHDRAWAL', 140),
    ('UTILITIES',           '공과금',             'WITHDRAWAL', 150),
    ('TELECOM',             '통신비',             'WITHDRAWAL', 160),
    ('ADVERTISING',         '광고비',             'WITHDRAWAL', 170),
    ('PLATFORM_FEE',        '플랫폼·카드 수수료', 'WITHDRAWAL', 180),
    ('INSURANCE',           '보험료',             'WITHDRAWAL', 190),
    ('SUBSCRIPTION',        '구독료',             'WITHDRAWAL', 200),
    ('LOAN_REPAYMENT',      '대출 상환',          'WITHDRAWAL', 210),
    ('TAX',                 '세금·공과',          'WITHDRAWAL', 220),
    ('SUPPLIES',            '소모품',             'WITHDRAWAL', 230),
    ('EQUIPMENT',           '설비·비품',          'WITHDRAWAL', 240),
    ('PERSONAL',            '개인 사용',          'WITHDRAWAL', 250),
    ('OTHER_EXPENSE',       '기타 지출',          'WITHDRAWAL', 260);
