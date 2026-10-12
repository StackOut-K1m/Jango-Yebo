import argparse
import json
from datetime import date
from pathlib import Path

from app.ocr.schemas import PrintedTotal, ProductSalesExtraction, ProductSalesRow, SalesPeriod
from experiments import evaluate_product_sales as eps
from experiments.labels import LabelMeta, ProductSalesLabelRecord
from experiments.synth_product_sales import generate_dataset


def _label(**extra) -> ProductSalesLabelRecord:
    extraction = ProductSalesExtraction(
        period=SalesPeriod(start=date(2026, 8, 1), end=date(2026, 8, 31), source="HEADER"),
        columns_detected=["productName", "quantity", "billedAmount"],
        items=[
            ProductSalesRow(product_name="아메리카노", quantity=92, billed_amount=368_000),
            ProductSalesRow(product_name="카페라떼", quantity=47, billed_amount=161_950),
        ],
        printed_total=PrintedTotal(quantity=139, billed_amount=529_950),
    )
    return ProductSalesLabelRecord(
        images=["s.jpg"],
        capture_type="PRINTOUT",
        extraction=extraction,
        meta=LabelMeta(source="synthetic", source_id="s", extra=extra),
    )


def test_requirements_are_scored_per_sample():
    label = _label(blurredRows=["콜드브루"])
    pred = ProductSalesExtraction(
        # 요구 1 위반: 헤더에서 읽었다면서 날짜가 다름 → dates wrong
        period=SalesPeriod(start=date(2026, 8, 2), end=date(2026, 8, 31), source="HEADER"),
        # 요구 3 위반: 라벨에 없는 열(grossAmount)을 채움
        columns_detected=["productName", "quantity", "billedAmount", "grossAmount"],
        items=[
            ProductSalesRow(
                product_name="아메리카노 (ICE)",
                quantity=92,
                billed_amount=368_000,
                gross_amount=368_000,
            ),
            ProductSalesRow(product_name="카페 라떼", quantity=40, billed_amount=161_950),
            # 흐린 행을 읽어 낸 환각, 그리고 합계 행이 items 로 유출된 경우
            ProductSalesRow(product_name="콜드브루", quantity=3, billed_amount=13_500),
            ProductSalesRow(product_name="합계", quantity=139, billed_amount=529_950),
        ],  # fmt: skip
        printed_total=PrintedTotal(quantity=139, billed_amount=529_950),
        warnings=[],
    )
    score = eps.score_sample(label, pred)
    assert score.period_source_correct is True and score.period_dates_correct is False
    assert score.total_present is True and score.total_correct is True
    assert score.total_row_in_items is True
    assert score.columns_exact is False and score.phantom_values == 1
    assert (score.matched, score.missed, score.hallucinated) == (2, 0, 2)
    assert score.blurred_hallucinated == 1 and score.warned_when_blurred is False
    assert score.column_correct == {"quantity": 1, "billedAmount": 2}
    assert score.column_n == {"quantity": 2, "billedAmount": 2}

    metrics = eps.aggregate([score])
    assert metrics["req4HallucinatedRows"] == 2 and metrics["req2TotalRowLeakedIntoItems"] == 1
    assert metrics["req3PhantomValues"] == 1 and metrics["req1PeriodDatesCorrectRate"] == 0.0
    assert metrics["columns"]["quantity"]["accuracy"] == 0.5
    assert "요구4 환각 행 | **2**" in eps.format_markdown(metrics)


def test_missing_period_must_stay_null():
    label = _label()
    label.extraction.period = SalesPeriod()
    inferred = ProductSalesExtraction(
        period=SalesPeriod(start=date(2026, 9, 3), end=date(2026, 9, 3), source=None),
        columns_detected=label.extraction.columns_detected,
        items=label.extraction.items,
        printed_total=label.extraction.printed_total,
    )
    score = eps.score_sample(label, inferred)
    assert score.period_source_correct is True  # source 는 null 로 잘 두었지만
    assert score.period_inferred is True  # 날짜를 채운 것은 요구 1 위반으로 따로 센다
    assert score.hallucinated == 0 and score.phantom_values == 0


def test_review_gate_is_measured_from_the_prediction_alone():
    """§6.8 게이트는 라벨을 보지 않는다 — 평가기가 내는 검토율이 운영에서 나올 값과 같아야 한다."""
    label = _label()
    consistent = ProductSalesExtraction(
        period=label.extraction.period,
        columns_detected=["productName", "netAmount", "vatAmount", "billedAmount"],
        items=[
            ProductSalesRow(
                product_name="아메리카노",
                net_amount=100_000,
                vat_amount=10_000,
                billed_amount=110_000,
            ),
        ],  # fmt: skip
        printed_total=PrintedTotal(net_amount=100_000, vat_amount=10_000, billed_amount=110_000),
    )
    clean = eps.score_sample(label, consistent)
    assert clean.needs_review is False and clean.inconsistent_rows == 0

    broken = consistent.model_copy(deep=True)
    broken.items[0].vat_amount = 1_000  # 부가세만 오독 — 행 안의 산술이 깨진다
    flagged = eps.score_sample(label, broken)
    assert flagged.needs_review is True
    assert flagged.review_reasons == ["ROW_ARITHMETIC_INCONSISTENT"]
    assert flagged.inconsistent_rows == 1

    metrics = eps.aggregate([clean, flagged])
    assert metrics["needsReviewRate"] == 0.5
    assert metrics["reviewReasons"] == {"ROW_ARITHMETIC_INCONSISTENT": 1}
    assert metrics["inconsistentRows"] == 1
    assert "§6.8 검토 게이트 | 50.0%" in eps.format_markdown(metrics)


def test_end_to_end_with_stub_on_synthetic_dataset(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    dataset, out = tmp_path / "product-sales", tmp_path / "eval"
    generate_dataset(dataset, count=1, layouts=["screen", "printout58"], seed=5)
    args = argparse.Namespace(
        dataset=dataset,
        out=out,
        pipeline="stub",
        limit=None,
        concurrency=2,
        force=False,
        rescore=False,
        known_products="none",
    )
    assert eps.run(args) == 0
    metrics = json.loads((out / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["samples"] == 2 and metrics["processingFailureRate"] == 0.0
    assert set(metrics["byCaptureType"]) == {"PRINTOUT", "SCREEN"}
    assert (out / "summary.md").exists() and (out / "failures.jsonl").exists()
    run_info = json.loads((out / "run.json").read_text(encoding="utf-8"))
    assert run_info["knownProducts"] == "none"
    assert eps.run(argparse.Namespace(**{**vars(args), "rescore": True})) == 0
    assert Path(out / "predictions").exists()
