-- =============================================================================
-- 복식부기 준비 서류 목록을 서식 정의로 (API 14.3 · S15P21C105-260)
--
-- **화면 상수로 두면 두 벌이 된다.** 웹과 앱이 같은 목록을 써야 하는데(화면구성도 1.1 —
-- 앱에 기능 게이팅 없음) 프론트 상수는 워크스페이스마다 따로 산다. 서식에 딸린 정보이므로
-- 서식 정의가 있을 자리다.
--
-- ## sections 에 넣되 모양을 구분한다
--
-- 지금까지 sections 는 **초안의 항목 정의**였다 — [{sectionKey, label, fields:[...]}].
-- 체크리스트를 같은 열에 넣되 `fields` 가 아니라 **`items`** 를 쓰고 `kind` 를 붙인다.
-- 화면이 분기할 근거가 되고, 무엇보다 항목 렌더러가 실수로 집어 가지 못한다.
--
--   kind 없음 · "FIELDS"  → 초안 항목 (기존 서식 넷 중 간편장부)
--   kind = "CHECKLIST"    → 준비 서류 목록 (items)
--
-- ## 초안이 열리지 않는다
--
-- supportsDraft() 는 지원 수준이 DRAFT·DRAFT_OPTIONAL 일 때만 참이다. DOUBLE_ENTRY 는
-- CHECKLIST_ONLY 라 sections 를 채워도 **초안 생성은 그대로 400 이다.** 지원 수준과 칸
-- 정의를 둘 다 보게 한 것이 여기서 값을 한다(V36 직전 커밋).
--
-- ## providedBy — 우리가 주는 것과 사장님이 모아야 하는 것
--
-- 사장님이 알아야 하는 것은 목록 자체가 아니라 **무엇을 직접 챙겨야 하는가**다.
-- EXPORT_PACKAGE 는 준비자료 패키지(14.2)가 이미 담아 주는 것이고, OWNER 는 우리가 못 보는
-- 것이다 — 현금 거래·개인카드·종이 서류가 거기 있다.
--
-- ⚠ 목록 내용은 SRS 1.3(재무제표·조정계산서 범위 밖)과 팀 문서를 근거로 적었다.
-- 프론트 260 작업의 화면 상수와 대조가 필요하다.
-- =============================================================================

UPDATE core.tax_form_template
   SET sections = $json$[
  {
    "sectionKey": "PREPARE_DOCUMENTS",
    "kind": "CHECKLIST",
    "label": "세무대리인에게 전달할 서류",
    "note": "재무제표와 조정계산서는 이 서비스에서 만들지 않습니다. 아래 자료를 모아 전달하시면 됩니다.",
    "items": [
      {
        "itemKey": "BANK_LEDGER",
        "label": "사업용 계좌 거래내역",
        "providedBy": "EXPORT_PACKAGE",
        "note": "준비자료 패키지에 기간별로 담겨 있습니다."
      },
      {
        "itemKey": "EXPENSE_EVIDENCE",
        "label": "매입·경비 증빙",
        "providedBy": "EXPORT_PACKAGE",
        "note": "연결한 증빙은 원본까지 담깁니다. 연결하지 않은 것은 빠집니다."
      },
      {
        "itemKey": "SETTLEMENT_STATEMENT",
        "label": "플랫폼 정산 명세",
        "providedBy": "EXPORT_PACKAGE",
        "note": "대사한 정산은 플랫폼별 표로 담깁니다."
      },
      {
        "itemKey": "LOAN_STATEMENT",
        "label": "대출 잔액·이자 납입 내역",
        "providedBy": "EXPORT_PACKAGE",
        "note": "연동한 대출만 보입니다. 다른 금융기관 대출은 따로 챙겨 주세요."
      },
      {
        "itemKey": "SALES_INVOICE",
        "label": "매출 증빙(세금계산서·카드·현금영수증)",
        "providedBy": "OWNER",
        "note": "홈택스에서 내려받을 수 있습니다."
      },
      {
        "itemKey": "PAYROLL",
        "label": "급여대장·원천징수이행상황신고서",
        "providedBy": "OWNER",
        "note": "직원이 있는 경우에만 필요합니다."
      },
      {
        "itemKey": "FIXED_ASSET",
        "label": "고정자산 목록과 감가상각 명세",
        "providedBy": "OWNER",
        "note": "설비·비품을 산 적이 있으면 챙겨 주세요."
      },
      {
        "itemKey": "INVENTORY",
        "label": "기말 재고 실사 내역",
        "providedBy": "OWNER",
        "note": "재고를 두는 업종에서 필요합니다."
      },
      {
        "itemKey": "CASH_EXPENSE",
        "label": "현금·개인카드로 쓴 경비",
        "providedBy": "OWNER",
        "note": "연결한 계좌 밖의 지출은 이 서비스가 보지 못합니다."
      }
    ]
  }
]$json$::jsonb
 WHERE form_type = 'DOUBLE_ENTRY' AND tax_year = 2026;
