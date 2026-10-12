import numpy as np

from app.ocr.validators import is_valid_business_number, validate_extraction
from experiments.labels import iter_labels, read_manifest
from experiments.synth_evidence import (
    KIND_DIRS,
    generate_dataset,
    generate_sample,
    ground_truth,
    random_spec,
    valid_business_no,
)


def test_generated_business_numbers_pass_the_checksum():
    rng = np.random.default_rng(1)
    for corporate in (True, False):
        for _ in range(20):
            assert is_valid_business_number(valid_business_no(rng, corporate=corporate)) is True


def test_tax_invoice_ground_truth_is_arithmetically_consistent():
    rng = np.random.default_rng(5)
    spec = random_spec(rng, "TAX_INVOICE")
    gt = ground_truth(spec)
    assert gt.supply_amount == sum(i.amount for i in gt.items)  # 행 금액 = 공급가액
    assert gt.supply_amount + gt.vat_amount == gt.total_amount
    assert all(not r.tax_free for r in spec.rows)
    report = validate_extraction(gt)
    assert report.amounts_consistent is True  # 공급가 + 세액 = 합계 (세금계산서형 검산)
    assert report.business_no_valid is True


def test_cash_receipt_variants_cover_tax_free_and_simple_totals():
    rng = np.random.default_rng(9)
    seen = set()
    for _ in range(40):
        spec = random_spec(rng, "CASH_RECEIPT")
        gt = ground_truth(spec)
        seen.add(spec.variant)
        assert gt.total_amount == sum(i.amount for i in gt.items)
        if spec.variant == "simple":
            assert gt.taxable_amount is None and gt.vat_amount is None
        else:
            assert gt.taxable_amount + gt.vat_amount + gt.tax_free_amount == gt.total_amount
            assert validate_extraction(gt).amounts_consistent is True
        if spec.variant == "tax-free":
            assert gt.tax_free_amount > 0 and any(r.tax_free for r in spec.rows)
    assert seen == {"taxable", "tax-free", "simple"}


def test_generate_dataset_writes_complete_labels_per_kind(tmp_path):
    counts = generate_dataset(tmp_path, count=1, kinds=list(KIND_DIRS), seed=3)
    assert counts == {"TAX_INVOICE": 1, "INVOICE": 1, "CASH_RECEIPT": 1}
    for kind, directory in KIND_DIRS.items():
        labels = list(iter_labels(tmp_path / directory))
        assert len(labels) == 1
        path, label = labels[0]
        assert label.evidence_type == kind
        assert label.meta.extra["itemsComplete"] is True  # 정밀도·환각률을 잴 수 있는 유일한 세트
        assert label.meta.extra["itemAmountBasis"] == (
            "gross" if kind == "CASH_RECEIPT" else "supply"
        )
        assert path.with_name(label.image).exists()
        manifest = read_manifest(tmp_path / directory)
        assert manifest is not None and manifest["kind"] == kind


def test_generate_sample_is_deterministic_for_a_seed(tmp_path):
    rng = np.random.default_rng(7)
    spec = random_spec(rng, "INVOICE")
    first = generate_sample(tmp_path / "a", "s", spec, seed=42)
    second = generate_sample(tmp_path / "b", "s", spec, seed=42)
    assert first.extraction == second.extraction
    assert (tmp_path / "a" / "s.jpg").read_bytes() == (tmp_path / "b" / "s.jpg").read_bytes()
