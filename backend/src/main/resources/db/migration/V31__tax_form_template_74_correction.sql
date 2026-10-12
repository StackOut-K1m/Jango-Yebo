-- =============================================================================
-- 별지 제74호서식 원문 대조 정정 (P0-12 · API 14.3)
--
-- 번호 주의 — 처음에 V30 으로 잡았다가 develop 이 같은 번호를 서류 발급처 사전(17.4)에
-- 쓰면서 V31 로 옮겼다. 오늘만 번호가 세 번 겹쳤다(V14·V18·V30).
--
-- V27 의 서식 정의는 API 명세 14.3 의 매핑표를 옮긴 것이었다. 2026-09-16 에
-- **관보 서식 원문 두 장을 직접 대조**했다:
--   소득세법 시행규칙 [별지 제74호서식]      간편장부소득금액계산서 (개정 2023.3.20)
--   소득세법 시행규칙 [별지 제74호서식 부표] 총수입금액 및 필요경비명세서 (개정 2025.3.21)
--
-- **일반관리비 15항목의 순서와 번호는 원문과 정확히 일치했다.** 계산되는 소득금액도
-- 바뀌지 않는다. 아래 넷만 고친다.
--
--   1) ㊵ 일반관리비등계 를 **새로 만든다**. 부표의 필요경비 합계는 두 단계다 —
--      ⑰ 매출원가 + ㊵(㉕~㊴의 합계). V27 은 그 중간 행 없이 한 번에 더해서,
--      숫자는 맞지만 **서식에 있는 칸을 채울 값이 없었다**(내보내기에서 빈다).
--   2) ⑮ 라벨을 원문대로 — 「당기 상품매입액 **또는 제조비용(㉔)**」.
--   3) ㉙ 기업업무추진비에 한도 안내 (작성방법 7). 1,200만원(중소기업 3,600만원)을
--      넘으면 별지 제55호서식 두 장을 첨부해야 한다 — 음식점이 넘길 수 있는 금액이다.
--   4) ㉚ 기부금에도 조정명세서 안내 (작성방법 8). V27 은 ㉛ 에만 붙여 뒀는데
--      원문은 기부금·감가상각비를 함께 묶는다.
--
-- **sections 를 통째로 다시 적는다.** 항목 배열 가운데에 칸을 하나 끼워 넣는 일이라
-- jsonb_set 으로 하면 인덱스에 기대게 되고, 나중에 V27 을 손대면 조용히 엉뚱한 자리를
-- 고친다. 서식 정의는 통째로가 원본이다.
--
-- 제조비용란(⑱~㉔)은 **여전히 넣지 않는다**. 제조원가명세서를 쓰는 제조업 전용이고
-- 음식점업은 기재하지 않는다(부표 작성방법 5).
-- =============================================================================

UPDATE core.tax_form_template
   SET official_form = '소득세법 시행규칙 별지 제74호서식(간편장부소득금액계산서, 개정 2023.3.20) + 제74호서식 부표(총수입금액 및 필요경비명세서, 개정 2025.3.21). 원문 대조 2026-09-16',
       sections = $json$[
      {
        "sectionKey": "IDENTITY",
        "label": "본표 ①~④ 인적사항",
        "fields": [
          { "fieldKey": "HOME_ADDRESS", "label": "① 주소지",   "source": "USER_SENSITIVE", "sensitive": true },
          { "fieldKey": "PHONE",        "label": "② 전화번호", "source": "AUTO_PROFILE" },
          { "fieldKey": "NAME",         "label": "③ 성명",     "source": "AUTO_PROFILE" },
          { "fieldKey": "BIRTH_DATE",   "label": "④ 생년월일", "source": "USER_SENSITIVE", "sensitive": true }
        ]
      },
      {
        "sectionKey": "BUSINESS",
        "label": "본표·부표 ⑤~⑩ 사업장",
        "fields": [
          { "fieldKey": "BIZ_ADDRESS",   "label": "⑤ 소재지",       "source": "USER_CONFIRM",
            "note": "사업장 프로필에 주소 칸이 없어 사용자가 적는다. 비면 내보내기에 「직접 기재」로 남는다" },
          { "fieldKey": "INDUSTRY",      "label": "⑥ 업종",         "source": "AUTO_PROFILE" },
          { "fieldKey": "INDUSTRY_CODE", "label": "⑦ 주업종코드",   "source": "REFERENCE_HOMETAX",
            "note": "업종코드가 둘 이상이면 주된 것을 적는다(작성방법 2)" },
          { "fieldKey": "BIZ_REG_NO",    "label": "⑧ 사업자등록번호","source": "AUTO_PROFILE" },
          { "fieldKey": "TAX_PERIOD",    "label": "⑨ 과세기간",     "source": "CALCULATED" },
          { "fieldKey": "INCOME_TYPE",   "label": "⑩ 소득종류",     "source": "CALCULATED", "fixedValue": 40,
            "note": "부동산임대소득 30 · 사업소득 40 (작성방법 3)" }
        ]
      },
      {
        "sectionKey": "REVENUE",
        "label": "부표 ⑪~⑬ 장부상 수입금액",
        "fields": [
          { "fieldKey": "SALES",         "label": "⑪ 매출액", "source": "AUTO_SETTLEMENT" },
          { "fieldKey": "OTHER_REVENUE", "label": "⑫ 기타",   "source": "AUTO_TRANSACTION" },
          { "fieldKey": "TOTAL_REVENUE", "label": "⑬ 수입금액 합계 (⑪+⑫)", "source": "CALCULATED",
            "formula": "SALES + OTHER_REVENUE",
            "note": "본표 ⑪ 장부상 수입금액과 같은 값이다(본표 작성방법 4)" }
        ]
      },
      {
        "sectionKey": "COST_OF_SALES",
        "label": "부표 ⑭~⑰ 매출원가",
        "note": "제조비용란(⑱~㉔)은 제조원가명세서를 쓰는 제조업 전용이라 음식점업은 기재하지 않는다(작성방법 5)",
        "fields": [
          { "fieldKey": "OPENING_INVENTORY", "label": "⑭ 기초재고액", "source": "USER_CONFIRM",
            "note": "직전 과세기간 명세서의 기말재고액과 같아야 한다. 기초·기말을 구분하기 어려우면 비워 두고 당기 매입액만 기재한다(작성방법 4)" },
          { "fieldKey": "PURCHASES",         "label": "⑮ 당기 상품매입액 또는 제조비용(㉔)", "source": "AUTO_TRANSACTION" },
          { "fieldKey": "CLOSING_INVENTORY", "label": "⑯ 기말재고액", "source": "USER_CONFIRM",
            "note": "다음 과세기간 명세서의 기초재고액이 된다(작성방법 4)" },
          { "fieldKey": "COST_OF_SALES",     "label": "⑰ 매출원가 (⑭+⑮-⑯)", "source": "CALCULATED",
            "formula": "OPENING_INVENTORY + PURCHASES - CLOSING_INVENTORY" }
        ]
      },
      {
        "sectionKey": "EXPENSE",
        "label": "부표 ㉕~㊵ 일반관리비 등",
        "fields": [
          { "fieldKey": "PAYROLL",       "label": "㉕ 급료",           "source": "AUTO_TRANSACTION" },
          { "fieldKey": "TAX_DUES",      "label": "㉖ 제세공과금",     "source": "AUTO_TRANSACTION",
            "note": "사업장이 부담한 재산세·상공회의소비·협회비 등이다(작성방법 6)" },
          { "fieldKey": "RENT",          "label": "㉗ 임차료",         "source": "AUTO_TRANSACTION" },
          { "fieldKey": "INTEREST",      "label": "㉘ 지급이자",       "source": "AUTO_TRANSACTION" },
          { "fieldKey": "ENTERTAINMENT", "label": "㉙ 기업업무추진비", "source": "USER_CONFIRM",
            "note": "1,200만원(중소기업 3,600만원) × 과세기간 월수/12 를 넘으면 기업업무추진비 조정명세서(1)·(2)(별지 제55호서식)를 첨부해야 한다(작성방법 7)" },
          { "fieldKey": "DONATION",      "label": "㉚ 기부금",         "source": "USER_CONFIRM",
            "note": "필요경비에 산입하면 해당 계정의 조정명세서를 첨부해야 한다(작성방법 8)" },
          { "fieldKey": "DEPRECIATION",  "label": "㉛ 감가상각비",     "source": "USER_CONFIRM",
            "note": "필요경비에 산입하면 해당 계정의 조정명세서를 첨부해야 한다(작성방법 8)" },
          { "fieldKey": "VEHICLE",       "label": "㉜ 차량유지비",     "source": "USER_CONFIRM" },
          { "fieldKey": "COMMISSION",    "label": "㉝ 지급수수료",     "source": "AUTO_TRANSACTION" },
          { "fieldKey": "SUPPLIES",      "label": "㉞ 소모품비",       "source": "AUTO_TRANSACTION" },
          { "fieldKey": "WELFARE",       "label": "㉟ 복리후생비",     "source": "AUTO_TRANSACTION" },
          { "fieldKey": "TRANSPORT",     "label": "㊱ 운반비",         "source": "USER_CONFIRM" },
          { "fieldKey": "ADVERTISING",   "label": "㊲ 광고선전비",     "source": "AUTO_TRANSACTION" },
          { "fieldKey": "TRAVEL",        "label": "㊳ 여비교통비",     "source": "USER_CONFIRM" },
          { "fieldKey": "OTHER_EXPENSE", "label": "㊴ 기타",           "source": "AUTO_TRANSACTION" },
          { "fieldKey": "GENERAL_EXPENSE_TOTAL", "label": "㊵ 일반관리비등계 (㉕~㊴의 합계)", "source": "CALCULATED",
            "formula": "PAYROLL + TAX_DUES + RENT + INTEREST + ENTERTAINMENT + DONATION + DEPRECIATION + VEHICLE + COMMISSION + SUPPLIES + WELFARE + TRANSPORT + ADVERTISING + TRAVEL + OTHER_EXPENSE" },
          { "fieldKey": "TOTAL_EXPENSE", "label": "부표 필요경비 합계 (⑰+㊵) = 본표 ⑮ 장부상 필요경비", "source": "CALCULATED",
            "formula": "COST_OF_SALES + GENERAL_EXPENSE_TOTAL" }
        ]
      },
      {
        "sectionKey": "ADJUSTMENT",
        "label": "본표 ⑫⑬⑯⑰⑳㉑ 세무조정·기부금 (기본값 0)",
        "note": "과목별 소득금액 조정명세서(별지 제48호서식)의 조정금액을 사유별로 합계해 적는다(본표 작성방법 8)",
        "fields": [
          { "fieldKey": "REVENUE_EXCLUDED",   "label": "⑫ 수입금액에서 제외할 금액",        "source": "USER_CONFIRM", "defaultValue": 0 },
          { "fieldKey": "REVENUE_ADDED",      "label": "⑬ 수입금액에 가산할 금액",          "source": "USER_CONFIRM", "defaultValue": 0,
            "note": "간주임대료·판매장려금·국고보조금·보험차익·준비금 환입액 등(본표 작성방법 5)" },
          { "fieldKey": "EXPENSE_EXCLUDED",   "label": "⑯ 필요경비에서 제외할 금액",        "source": "USER_CONFIRM", "defaultValue": 0 },
          { "fieldKey": "EXPENSE_ADDED",      "label": "⑰ 필요경비에 가산할 금액",          "source": "USER_CONFIRM", "defaultValue": 0 },
          { "fieldKey": "DONATION_EXCESS",    "label": "⑳ 기부금 한도초과액",              "source": "USER_CONFIRM", "defaultValue": 0 },
          { "fieldKey": "DONATION_CARRYOVER", "label": "㉑ 기부금이월액 중 필요경비 산입액", "source": "USER_CONFIRM", "defaultValue": 0 }
        ]
      },
      {
        "sectionKey": "INCOME",
        "label": "본표 ⑭⑱⑲㉒ 소득금액",
        "note": "본표 ⑭ 는 사업소득명세서[별지 제40호서식(1)] ⑧ 총수입금액과 같아야 한다(본표 작성방법 6)",
        "fields": [
          { "fieldKey": "ADJUSTED_REVENUE",           "label": "⑭ 세무조정 후 수입금액 (⑪−⑫+⑬)", "source": "CALCULATED",
            "formula": "TOTAL_REVENUE - REVENUE_EXCLUDED + REVENUE_ADDED" },
          { "fieldKey": "ADJUSTED_EXPENSE",           "label": "⑱ 세무조정 후 필요경비 (⑮−⑯+⑰)", "source": "CALCULATED",
            "formula": "TOTAL_EXPENSE - EXPENSE_EXCLUDED + EXPENSE_ADDED" },
          { "fieldKey": "NET_INCOME_BEFORE_DONATION", "label": "⑲ 차가감 소득금액 (⑭−⑱)",        "source": "CALCULATED",
            "formula": "ADJUSTED_REVENUE - ADJUSTED_EXPENSE" },
          { "fieldKey": "NET_INCOME",                 "label": "㉒ 해당 연도 소득금액 (⑲+⑳−㉑)",  "source": "CALCULATED",
            "formula": "NET_INCOME_BEFORE_DONATION + DONATION_EXCESS - DONATION_CARRYOVER" }
        ]
      }
    ]$json$::jsonb
 WHERE form_type = 'SIMPLE_LEDGER' AND tax_year = 2026;
