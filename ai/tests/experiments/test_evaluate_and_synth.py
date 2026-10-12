import argparse
import json
from datetime import date, datetime
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from app.ocr.schemas import EvidenceExtraction, ExtractedItem
from experiments import evaluate_evidence as ev
from experiments.degrade import degrade, degrade_dataset, stable_seed
from experiments.labels import (
    LabelMeta,
    LabelRecord,
    iter_labels,
    read_label,
    read_manifest,
    read_product_sales_label,
    write_label,
)
from experiments.synth_product_sales import (
    LAYOUTS,
    RenderSpec,
    generate_dataset,
    generate_sample,
    make_rows,
    totals_of,
)


def _stub_like_extraction(**changes) -> EvidenceExtraction:
    extraction = EvidenceExtraction(
        supplier_name="서울우유 광주대리점",
        supplier_business_no="124-81-00998",
        issued_date=date(2026, 8, 30),
        supply_amount=141_818,
        vat_amount=14_182,
        total_amount=156_000,
        items=[
            ExtractedItem(raw_name="서울우유 1L", quantity=40, unit_price=2_900, amount=116_000),
            ExtractedItem(
                raw_name="바나나우유 240ml", quantity=25, unit_price=1_600, amount=40_000
            ),
        ],
    )
    for key, value in changes.items():
        setattr(extraction, key, value)
    return extraction


def _make_dataset(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for source_id, extraction in (
        ("a", _stub_like_extraction()),
        ("b", _stub_like_extraction(total_amount=150_000, items=[])),
    ):
        Image.new("RGB", (200, 300), "white").save(directory / f"{source_id}.png")
        write_label(
            directory / f"{source_id}.json",
            LabelRecord(
                image=f"{source_id}.png",
                extraction=extraction,
                meta=LabelMeta(source="test", source_id=source_id, split="val"),
            ),
        )
    (directory / "manifest.json").write_text('{"dataset": "fixture"}', encoding="utf-8")


def _args(dataset: Path, out: Path, **overrides) -> argparse.Namespace:
    base = {
        "dataset": dataset,
        "out": out,
        "pipeline": "stub",
        "limit": None,
        "concurrency": 2,
        "force": False,
        "rescore": False,
        "confidence_threshold": 0.85,
        "no_overrides": False,
    }
    base.update(overrides)
    return argparse.Namespace(**base)


# ---------------------------------------------------------------- evaluate_evidence


def test_evaluate_with_stub_pipeline_end_to_end(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)  # Settings() 가 레포의 .env 를 읽지 않게
    dataset, out = tmp_path / "val", tmp_path / "eval"
    _make_dataset(dataset)

    assert ev.run(_args(dataset, out)) == 0

    assert (out / "predictions" / "a.json").exists() and (out / "predictions" / "b.json").exists()
    metrics = json.loads((out / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["receipts"] == 2 and metrics["processingFailureRate"] == 0.0
    # stub 예측은 a 와 같고 b 와는 총액이 다르다
    assert metrics["fields"]["totalAmount"]["correct"] == 1
    assert metrics["items"]["hallucinated"] == 2  # b 라벨은 품목 0개 → stub 의 2개 행이 환각
    run_info = json.loads((out / "run.json").read_text(encoding="utf-8"))
    assert run_info["datasetManifest"] == {"dataset": "fixture"}
    assert run_info["isTestSplit"] is False
    failures = (out / "failures.jsonl").read_text(encoding="utf-8").strip().splitlines()
    assert len(failures) == 1 and json.loads(failures[0])["sourceId"] == "b"

    # 재채점은 저장된 예측만 쓴다
    assert ev.run(_args(dataset, out, rescore=True)) == 0


def test_label_overrides_are_applied_and_counted(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    dataset, out = tmp_path / "val", tmp_path / "eval"
    _make_dataset(dataset)
    # b 의 총액 150,000 이 라벨 오류였다고 치자 — 이미지 근거와 함께 교정
    (dataset / "overrides.json").write_text(
        json.dumps(
            {
                "version": 1,
                "records": {
                    "b": {
                        "fields": {"totalAmount": 156_000},
                        "reason": "영수증 합계 156,000 (이미지 확인)",
                        "verified": "image",
                    }
                },
            }
        ),
        encoding="utf-8",
    )
    assert len(list(iter_labels(dataset))) == 2  # overrides.json 은 라벨로 세지 않는다

    assert ev.run(_args(dataset, out)) == 0
    metrics = json.loads((out / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["fields"]["totalAmount"]["correct"] == 2
    assert metrics["labelOverrides"] == {"receipts": 1, "fields": {"totalAmount": 1}}
    run_info = json.loads((out / "run.json").read_text(encoding="utf-8"))
    assert run_info["labelOverridesApplied"] is True and run_info["scoringRules"] == "scoring/v2"

    # 원천 라벨 그대로 → 다시 틀린다. 그 차이가 라벨 오류율이다
    assert ev.run(_args(dataset, out, rescore=True, no_overrides=True)) == 0
    metrics = json.loads((out / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["fields"]["totalAmount"]["correct"] == 1
    assert metrics["labelOverrides"]["receipts"] == 0


def test_override_can_add_a_label_the_source_did_not_have(tmp_path):
    from experiments.labels import LabelOverride, apply_override

    label = read_label_fixture(tmp_path)
    label.meta.unlabeled_fields = ["supplierBusinessNo"]
    # 원천에 없던 사업자번호를 사람이 라벨링 → 판정 불가 해제. null 을 적으면 "미인쇄 = null 정답"
    added = apply_override(
        label,
        LabelOverride(
            fields={"supplierBusinessNo": "124-81-00998"},
            reason="헤더 확인",
            labeled_fields=["supplierBusinessNo"],
        ),
    )
    assert added.meta.unlabeled_fields == []
    assert added.extraction.supplier_business_no == "124-81-00998"
    assert added.meta.extra["labeledByOverride"] == ["supplierBusinessNo"]

    unreadable = apply_override(
        label,
        LabelOverride(
            fields={"supplierBusinessNo": None},
            reason="흐림",
            labeled_fields=["supplierBusinessNo"],
            unreadable_fields=["supplierBusinessNo"],
        ),
    )
    assert unreadable.meta.unlabeled_fields == []
    assert unreadable.meta.unreadable_fields == ["supplierBusinessNo"]
    assert label.meta.unlabeled_fields == ["supplierBusinessNo"]  # 원본은 그대로


def read_label_fixture(tmp_path: Path) -> LabelRecord:
    dataset = tmp_path / "fixture"
    _make_dataset(dataset)
    return read_label(dataset / "a.json")


def test_fatal_key_errors_abort_the_run_without_caching_failures(tmp_path, monkeypatch):
    from app.ocr.pipelines.base import ExtractionError

    class _DeadKeyPipeline:
        calls = 0

        async def extract_evidence(self, images, request):
            type(self).calls += 1
            raise ExtractionError("quota", "게이트웨이 키의 토큰이 소진되었습니다")

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(ev, "build_pipeline", lambda kind, settings: (_DeadKeyPipeline(), None))
    dataset, out = tmp_path / "val", tmp_path / "eval"
    _make_dataset(dataset)
    assert ev.run(_args(dataset, out, pipeline="vlm", concurrency=1)) == 2  # 예측이 하나도 없다
    assert not list((out / "predictions").glob("*.json"))  # 실패를 캐시에 남기지 않는다
    assert _DeadKeyPipeline.calls == 1  # 첫 실패에서 멈춘다 (동시성 1)


def test_budget_cap_stops_calling_after_the_estimate_reaches_the_limit(tmp_path, monkeypatch):
    from app.ocr.pipelines.stub import StubPipeline

    class _Usage:  # ClaudeClient.usage 흉내 — 한 장에 이미 큰 비용이 든 것으로 본다
        def snapshot(self):
            return {
                "input_tokens": 10_000_000,
                "output_tokens": 0,
                "cache_read_input_tokens": 0,
                "cache_creation_input_tokens": 0,
            }

    class _Client:
        usage = _Usage()
        # 티어링 대응으로 비용은 모델별 누계에서 계산한다 (ClaudeClient 와 같은 인터페이스)
        usage_by_model = {"claude-opus-5": _Usage()}

    monkeypatch.chdir(tmp_path)  # .env 없음 → vision_model 기본값(단가표에 있음)
    monkeypatch.setattr(ev, "build_pipeline", lambda kind, settings: (StubPipeline(), _Client()))
    dataset, out = tmp_path / "val", tmp_path / "eval"
    _make_dataset(dataset)
    args = _args(dataset, out, pipeline="vlm", concurrency=1)
    args.budget_usd = 1.0
    assert ev.run(args) == 0
    assert len(list((out / "predictions").glob("*.json"))) == 1  # 첫 장 뒤 예산 도달 → 멈춤
    run_info = json.loads((out / "run.json").read_text(encoding="utf-8"))
    assert run_info["budgetUsd"] == 1.0 and run_info["receipts"] == 1


def test_evaluate_on_test_split_does_not_write_failures(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    dataset, out = tmp_path / "korie" / "test", tmp_path / "eval-test"
    _make_dataset(dataset)
    assert ev.run(_args(dataset, out)) == 0
    assert not (out / "failures.jsonl").exists()
    assert json.loads((out / "run.json").read_text(encoding="utf-8"))["isTestSplit"] is True


def test_cost_estimate_uses_snapshot_prices():
    usage = {"input_tokens": 1_000_000, "output_tokens": 100_000, "cache_read_input_tokens": 0}
    assert ev.estimate_cost_usd("claude-opus-5", usage) == 7.5
    assert ev.estimate_cost_usd("unknown-model", usage) is None


# ---------------------------------------------------------------- degrade


def test_degrade_is_deterministic_and_keeps_labels(tmp_path):
    canvas = np.full((120, 80, 3), 255, dtype=np.uint8)
    cv2.putText(canvas, "7,400", (5, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 0), 1)
    first, params = degrade(canvas, "medium", seed=123)
    second, _ = degrade(canvas, "medium", seed=123)
    assert np.array_equal(first, second)
    assert first.shape[0] > canvas.shape[0] and first.shape[1] > canvas.shape[1]  # 여백이 붙는다
    assert params["preset"] == "medium" and 60 <= params["jpegQuality"] <= 85

    dataset, out = tmp_path / "val", tmp_path / "mobile-sim"
    _make_dataset(dataset)
    assert degrade_dataset(dataset, out, "light", seed=1) == 2
    label = read_label(out / "a.json")
    assert label.image == "a.jpg" and (out / "a.jpg").exists()
    assert label.meta.source == "test-mobile-sim"
    assert label.extraction.total_amount == 156_000  # GT 는 그대로
    assert label.meta.extra["degrade"]["seed"] == stable_seed(1, "a")
    manifest = read_manifest(out)
    assert manifest is not None and manifest["degrade"]["preset"] == "light"
    assert len(list(iter_labels(out))) == 2  # manifest.json 은 라벨로 세지 않는다


# ---------------------------------------------------------------- synth_product_sales


def test_rows_are_arithmetically_consistent():
    rows = make_rows(np.random.default_rng(3), 12)
    assert len(rows) == 12
    for row in rows:
        assert row.gross == row.quantity * row.unit_price
        assert row.billed == row.gross - row.discount
        assert row.net + row.vat == row.billed
    assert abs(sum(r.share for r in rows) - 1.0) < 0.01


def test_printout58_two_pages_blurred_row_no_period(tmp_path):
    rng = np.random.default_rng(11)
    rows = make_rows(rng, 10)
    spec = RenderSpec(
        layout=LAYOUTS["printout58"],
        rows=rows,
        period=None,
        print_time=datetime(2026, 9, 3, 18, 42),
        store="카페 잔고",
        pages=2,
        overlap=1,
        blur_rows=frozenset({2}),
    )
    record = generate_sample(tmp_path / "printout", "ps-test-000", spec, seed=5)

    assert record.capture_type == "PRINTOUT"
    assert len(record.images) == 2
    assert all((tmp_path / "printout" / name).exists() for name in record.images)
    x = record.extraction
    assert x.columns_detected == ["productName", "quantity", "billedAmount", "share"]
    assert x.period.source is None and x.period.start is None  # 헤더에 기간이 없다
    assert len(x.items) == 9 and rows[2].name not in [i.product_name for i in x.items]
    assert x.items[0].gross_amount is None and x.items[0].vat_amount is None  # 없는 열은 null
    assert x.printed_total is not None
    assert x.printed_total.billed_amount == totals_of(rows)["billedAmount"]  # 합계는 모든 행
    assert x.printed_total.vat_amount is None
    assert record.meta.extra["expectedChecksumMismatch"] is True
    assert record.meta.extra["blurredRows"] == [rows[2].name]
    saved = read_product_sales_label(tmp_path / "printout" / "ps-test-000.json")
    assert saved.extraction.printed_total == x.printed_total


def test_generate_dataset_writes_manifest_and_both_capture_dirs(tmp_path):
    counts = generate_dataset(tmp_path, count=2, layouts=["screen", "printout80"], seed=9)
    assert counts == {"screen": 2, "printout80": 2}
    manifest = read_manifest(tmp_path)
    assert manifest is not None and manifest["counts"] == counts
    screen = read_product_sales_label(next((tmp_path / "screen").glob("*.json")))
    assert screen.capture_type == "SCREEN" and len(screen.extraction.columns_detected) == 8
    if screen.extraction.period.source == "HEADER":
        assert screen.extraction.period.start is not None
    assert (tmp_path / "printout").is_dir()


# ---------------------------------------------------------------- analyze_failures


def test_analyze_failures_buckets_common_error_types(tmp_path, monkeypatch):
    from experiments.analyze_failures import analyze

    monkeypatch.chdir(tmp_path)
    dataset, out = tmp_path / "val", tmp_path / "eval"
    _make_dataset(dataset)
    assert ev.run(_args(dataset, out)) == 0  # stub 예측: a 는 정답, b 는 총액 불일치·품목 누락 없음

    report = analyze(dataset, out)
    assert "# 실패 분석" in report
    assert "| 정답 | 1 |" in report  # 합계: a 정답
    # 합계: b 는 라벨 150,000 vs stub 156,000 이고 stub 의 품목 합이 156,000 이라 이 유형으로 묶인다
    assert "품목 합을 합계로 읽음" in report
    assert "품목 누락" in report


def test_faint_rows_stay_in_ground_truth_but_blurred_rows_do_not(tmp_path):
    """중간 흐림은 **읽히는** 행이라 정답에 남고, 강한 흐림은 빠진다.

    이 구분이 재현 데이터셋의 전제다. 실서버에서 관찰된 것은 "모델이 값을 맞게 읽으면서도
    「흐려서 판독 불가」 경고를 남긴" 경우인데(2026-09-11), 강한 흐림만으로는 그 조건을
    만들 수 없다 — 합성 90표본에서 흐린 행 27개 중 읽어 낸 것이 0개였다.

    둘이 뒤섞이면 경고가 정당한지(읽을 수 없었다) 허위인지(읽었는데 경고했다)를 가릴 수
    없으므로, 한 행에 둘을 겹치지 않는 것도 함께 못 박는다.
    """
    rng = np.random.default_rng(21)
    rows = make_rows(rng, 8)
    spec = RenderSpec(
        layout=LAYOUTS["printout58"],
        rows=rows,
        period=(date(2026, 5, 13), date(2026, 6, 1)),
        print_time=datetime(2026, 6, 5, 10, 48),
        store="골목식당 본점",
        blur_rows=frozenset({1}),
        faint_rows=frozenset({3, 5}),
    )
    record = generate_sample(tmp_path, "ps-faint-000", spec, 21)

    names = {item.product_name for item in record.extraction.items}
    assert rows[3].name in names and rows[5].name in names  # 중간 흐림 — 남는다
    assert rows[1].name not in names  # 강한 흐림 — 빠진다

    extra = record.meta.extra
    assert extra["faintRows"] == sorted([rows[3].name, rows[5].name])
    assert extra["blurredRows"] == [rows[1].name]
    assert not set(extra["faintRows"]) & set(extra["blurredRows"])  # 겹치지 않는다


def test_faint_ratio_picks_rows_outside_the_blurred_ones(tmp_path):
    """`--faint-ratio` 가 강한 흐림과 겹치지 않는 행에서만 고르는지."""
    from experiments.synth_product_sales import random_spec

    seen = 0
    for seed in range(12):
        spec = random_spec(np.random.default_rng(seed), LAYOUTS["printout80"], faint_ratio=0.3)
        assert not (spec.faint_rows & spec.blur_rows), seed
        seen += len(spec.faint_rows)
    assert seen > 0, "비율을 줬는데 중간 흐림이 하나도 안 생겼다"
