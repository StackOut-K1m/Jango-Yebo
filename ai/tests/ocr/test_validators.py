from datetime import date

from app.ocr.schemas import EvidenceExtraction, ExtractedItem
from app.ocr.validators import (
    check_amounts,
    check_issued_date,
    check_item_alignment,
    check_items_sum,
    check_vat_ratio,
    is_valid_business_number,
    normalize_business_number,
    validate_extraction,
)


def test_business_number_checksum():
    assert is_valid_business_number("124-81-00998") is True
    assert is_valid_business_number("2208162517") is True
    # 명세 13.2 예시 값 — 체크섬이 맞지 않는다. 그대로 쓰면 review 로 빠진다
    assert is_valid_business_number("123-45-67890") is False
    assert is_valid_business_number("12345") is False
    assert is_valid_business_number(None) is None


def test_normalize_business_number():
    assert normalize_business_number("1248100998") == "124-81-00998"
    assert normalize_business_number("124 81 00998") == "124-81-00998"
    assert normalize_business_number("124-81") is None


def test_amount_checks():
    # 세금계산서형: 공급가액 + 세액 = 합계
    assert check_amounts(141_818, 14_182, 156_000) is True
    assert check_amounts(141_818, 14_182, 156_001) is False
    assert check_amounts(None, 14_182, 156_000) is None
    # 마트형: 과세물품 + 부가세 + 면세물품 = 결제금액 (홈플러스 IMG00020 실측값)
    assert check_amounts(None, 3_124, 44_350, taxable=31_236, tax_free=9_990) is True
    assert check_amounts(None, 3_124, 44_350, taxable=31_236) is False  # 면세 줄을 빼먹으면 실패
    assert check_amounts(None, 1_845, 20_300, taxable=18_455, tax_free=0) is True  # 면세 0 인쇄
    # 과세 줄이 있으면 공급가 줄보다 우선한다 (파생값이 아닌 인쇄값으로만 검산)
    assert check_amounts(99, 1_845, 20_300, taxable=18_455) is True


def test_items_sum_with_discounts():
    items = [
        ExtractedItem(raw_name="a", amount=116_000),
        ExtractedItem(raw_name="b", amount=40_000),
    ]
    assert check_items_sum(items, 156_000) is True
    assert check_items_sum(items, 156_009) is True  # 절사 허용 오차 안
    assert check_items_sum(items, 200_000) is False
    assert check_items_sum([], 156_000) is None
    # 에누리: 행에 붙은 할인(순액) 또는 할인 총액으로 맞으면 통과
    discounted = [
        ExtractedItem(raw_name="캐슈너트", amount=59_940, discount_amount=29_970),
        ExtractedItem(raw_name="소스", amount=8_780, discount_amount=4_390),
    ]
    assert check_items_sum(discounted, 34_360) is True
    plain = [i.model_copy(update={"discount_amount": None}) for i in discounted]
    assert check_items_sum(plain, 34_360, discount_total=34_360) is True
    assert check_items_sum(plain, 34_360) is False
    assert check_items_sum(discounted, 68_720) is False
    # 세금계산서형: 행 금액이 공급가액이라 Σ품목 = 공급가액 (≠ 합계) 이면 통과
    rows = [
        ExtractedItem(raw_name="원두", amount=1_170_000),
        ExtractedItem(raw_name="컵", amount=66_700),
    ]
    assert check_items_sum(rows, 1_360_370, supply=1_236_700) is True
    # 공급가액 줄이 인쇄되지 않은 양식도 통과한다 — Σ품목 1,236,700 x 1.1 = 합계 1,360,370.
    # 이 단정은 원래 False 였는데 그게 버그를 고정하고 있었다: 합성 거래명세서 15장 중 9장이
    # 이 양식이라 **예측이 라벨과 완전히 같은데도** 불일치로 걸렸다 (2026-09-09).
    assert check_items_sum(rows, 1_360_370) is True
    # 세율로도 설명되지 않는 차이는 그대로 불일치다
    assert check_items_sum(rows, 1_500_000) is False


def test_date_check():
    today = date(2026, 9, 7)
    assert check_issued_date(date(2026, 8, 30), today=today) is True
    assert check_issued_date(date(2026, 9, 8), today=today) is True  # 내일까지 허용
    assert check_issued_date(date(2026, 9, 9), today=today) is False
    assert check_issued_date(date(1999, 12, 31), today=today) is False
    assert check_issued_date(None) is None


def test_validate_extraction_collects_issues_and_review_reasons():
    extraction = EvidenceExtraction(
        supplier_name="가게",
        supplier_business_no="123-45-67890",
        issued_date=date(2026, 8, 30),
        supply_amount=100,
        vat_amount=10,
        total_amount=111,
        items=[ExtractedItem(raw_name="x", amount=50)],
    )
    report = validate_extraction(extraction, today=date(2026, 9, 7))
    assert report.amounts_consistent is False
    assert report.items_sum_consistent is False
    assert report.business_no_valid is False
    assert report.date_valid is True
    assert report.needs_review is True
    assert report.review_reasons == [
        "AMOUNTS_INCONSISTENT",
        "ITEMS_SUM_INCONSISTENT",
        "BUSINESS_NO_INVALID",
    ]
    assert len(report.issues) == 3


def test_review_reasons_from_missing_fields_not_from_none_checks():
    # 검산할 값이 없는(None) 것은 사유가 아니다. 필수 필드 누락은 사유다.
    sparse = EvidenceExtraction(
        supplier_name=None,
        issued_date=None,
        total_amount=None,
        field_confidence={"supplierName": 0.4, "totalAmount": 0.9},
    )
    report = validate_extraction(sparse, today=date(2026, 9, 7))
    assert report.amounts_consistent is None and report.items_sum_consistent is None
    # 신뢰도는 기본적으로 게이트에 들어가지 않는다 — 0.4 여도 LOW_CONFIDENCE 가 없다
    assert report.review_reasons == ["TOTAL_MISSING", "DATE_MISSING", "SUPPLIER_MISSING"]


def test_low_confidence_is_a_reason_only_when_a_threshold_is_given():
    """test 359장에서 신뢰도는 변별력이 약했다(정밀도 31%) — 게이트에서 빼고 옵션으로 남긴다."""
    unsure = EvidenceExtraction(
        supplier_name="가게",
        issued_date=date(2026, 8, 30),
        taxable_amount=100,
        vat_amount=10,
        tax_free_amount=0,
        total_amount=110,
        items=[ExtractedItem(raw_name="x", amount=110)],
        field_confidence={"supplierName": 0.4, "totalAmount": 0.99},
    )
    off = validate_extraction(unsure, today=date(2026, 9, 7))
    assert off.needs_review is False and off.review_reasons == []

    on = validate_extraction(unsure, today=date(2026, 9, 7), low_confidence=0.7)
    assert on.needs_review is True and on.review_reasons == ["LOW_CONFIDENCE"]
    assert "supplierName" in on.issues[-1]


def test_mart_receipt_with_tax_free_and_discounts_passes_all_checks():
    # 홈플러스 IMG00020 실측: 과세 31,236 + 부가세 3,124 + 면세 9,990 = 결제 44,350, 행별 에누리
    clean = EvidenceExtraction(
        supplier_name="홈플러스 청주성안점",
        issued_date=date(2026, 8, 30),
        taxable_amount=31_236,
        vat_amount=3_124,
        tax_free_amount=9_990,
        discount_amount=44_350,
        total_amount=44_350,
        items=[
            ExtractedItem(raw_name="토마토스파게티소스", amount=8_780, discount_amount=4_390),
            ExtractedItem(raw_name="흰강낭콩", amount=19_980, discount_amount=9_990),
            ExtractedItem(raw_name="직수입구운캐슈너트", amount=59_940, discount_amount=29_970),
        ],
        field_confidence={"supplierName": 0.95},
    )
    report = validate_extraction(clean, today=date(2026, 9, 7))
    assert report.amounts_consistent is True and report.items_sum_consistent is True
    assert report.needs_review is False and report.review_reasons == []


def test_vat_ratio_check():
    # 정상: 부가세는 과세분의 10%
    assert check_vat_ratio(22_050, taxable=220_500) is True
    assert check_vat_ratio(14_182, supply=141_818) is True  # 반올림 차이 허용
    # 영세율·면세 계산서는 세액 0 이 정상 — 판정하지 않는다
    assert check_vat_ratio(0, supply=1_000_000) is None
    assert check_vat_ratio(None, taxable=100) is None
    # 과세분이 인쇄되지 않았으면 합계에서 유도한다 (11 × 부가세 = 합계 - 면세)
    assert check_vat_ratio(22_050, tax_free=528_000, total=770_550) is True
    assert check_vat_ratio(220_500, tax_free=22_050, total=770_550) is False
    # 아무 근거도 없으면 판정 불가
    assert check_vat_ratio(1_000) is None


def test_shifted_amount_columns_pass_the_sum_but_fail_the_vat_ratio():
    """합성 현금영수증 synth-cash-receipt-009 실측 — 검산 설계의 맹점을 고정한다.

    모델이 과세·면세·부가세를 한 칸씩 밀어 적었다. 덧셈은 교환법칙이 성립하므로
    `과세 + 부가세 + 면세 = 합계` 는 그대로 통과한다 — 세율 관계만 이걸 잡는다.
    """
    shifted = EvidenceExtraction(
        supplier_name="가게",
        issued_date=date(2026, 2, 28),
        taxable_amount=528_000,  # 라벨은 220,500 (면세 값이 여기 왔다)
        tax_free_amount=22_050,  # 라벨은 528,000 (부가세 값이 여기 왔다)
        vat_amount=220_500,  # 라벨은 22,050 (과세 값이 여기 왔다)
        total_amount=770_550,
        items=[ExtractedItem(raw_name="우유", amount=770_550)],
    )
    report = validate_extraction(shifted, today=date(2026, 9, 7))
    assert report.amounts_consistent is True  # 합은 맞는다 — 여기까지가 v1.10 의 한계
    assert report.vat_ratio_consistent is False
    assert report.needs_review is True
    assert report.review_reasons == ["VAT_RATIO_INCONSISTENT"]


def test_item_row_alignment_check():
    def rows(*pairs):
        return [ExtractedItem(raw_name=n, amount=a) for n, a in pairs]

    assert check_item_alignment(rows(("계란 30구", 79_000), ("상추 4kg", 32_000))) is True
    assert check_item_alignment([]) is None
    # ① 표 머리글이 품목 행으로 들어왔다 — 이름 열이 한 칸 밀린 신호
    assert check_item_alignment(rows(("상품명", 79_000), ("계란 30구", 32_000))) is False
    assert check_item_alignment(rows(("금 액", 79_000), ("계란", 32_000))) is False
    # ② 마지막 행만 금액이 없다 — 맨 끝 상품이 숫자를 못 받았다
    assert check_item_alignment(rows(("계란", 79_000), ("생크림 1L", None))) is False
    # 여러 행이 비어 있으면 그냥 판독 실패지 밀림 신호가 아니다
    assert check_item_alignment(rows(("계란", None), ("상추", 32_000), ("생크림", None))) is True


def test_shifted_item_names_pass_the_sum_but_fail_the_alignment_check():
    """합성 현금영수증 synth-cash-receipt-005 실측 — 이름 열만 한 칸 밀렸다.

    금액은 전부 온전해서 Σ품목 = 합계가 그대로 맞는다. 산술로는 못 잡고 구조로만 잡힌다.
    """
    shifted = EvidenceExtraction(
        supplier_name="가게",
        issued_date=date(2026, 8, 30),
        taxable_amount=278_591,
        vat_amount=27_859,
        tax_free_amount=0,
        total_amount=306_450,
        items=[
            ExtractedItem(raw_name="상품명", amount=79_000),  # 머리글이 첫 행 이름이 됐다
            ExtractedItem(raw_name="계란 30구", amount=32_000),
            ExtractedItem(raw_name="상추 4kg", amount=65_340),
            ExtractedItem(raw_name="점보롤 화장지 16롤", amount=114_950),
            ExtractedItem(raw_name="냉동 딸기 1kg", amount=15_160),
            ExtractedItem(raw_name="생크림 1L", amount=None),  # 맨 끝 상품이 숫자를 못 받았다
        ],
    )
    report = validate_extraction(shifted, today=date(2026, 9, 7))
    assert report.amounts_consistent is True  # 합계 검산 통과
    assert report.items_sum_consistent is True  # Σ품목도 통과
    assert report.items_aligned is False  # 구조만 이걸 본다
    assert report.review_reasons == ["ITEM_ROWS_MISALIGNED"]
