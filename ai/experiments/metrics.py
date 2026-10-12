"""증빙 추출 채점 — 예측(`EvidenceExtraction`) vs 라벨(`LabelRecord`). API 호출 없는 순수 함수.

지표(리뷰에서 합의한 목록)
- Processing Failure Rate / Schema Valid Rate
- Field Accuracy: supplierName(유사도 ≥ 0.9 또는 포함 매칭)·issuedDate·supplierBusinessNo(숫자)
- Amount Accuracy: supplyAmount·vatAmount·totalAmount (exact. totalAmount 는 할인 규칙 참고)
- Line-item Precision / Recall / F1 (행 매칭 = 이름 유사도 ≥ 0.8, 1:1 탐욕 매칭)
- Hallucinated Item Rate (라벨에 없는 예측 행 / 예측 행), 환각 행이 있는 영수증 수
- Business No. Exact Match 와 Checksum Pass 는 **별개** — 틀리게 읽은 번호도 체크섬을 통과할 수 있다
- Amount Reconciliation Rate (validators.check_amounts — 과세 + 부가세 + 면세 = 합계)
- 신뢰도 보정: 맞은 필드 vs 틀린 필드의 평균 fieldConfidence
- 라벨 교정(overrides) 건수 — 원천 라벨의 오류율

채점 규칙 (SCORING_RULES_VERSION)
- v1: 상호명 유사도 ≥ 0.9, 금액 exact, 검산 = 공급가 + 부가세 = 합계
- v2 (2026-09-07, KORIE val 50장 실측 반영):
  · 상호명은 유사도 외에 **포함 매칭** — 라벨(로고·브랜드 `Homeplus 홈플러스`)이 예측(매장명 줄
    `홈플러스 청주성안점`)에 들어 있으면 정답. 영문 브랜드는 한글 별칭으로 바꿔 본다
    (`Emart`→`이마트`). 유사도(WRatio)는 부분 일치도 높게 치므로 짧은 예측에 관대하다
  · totalAmount 는 라벨이 **할인 전 총합계**여도 `예측 합계 + 예측 할인` 과 같으면 정답
    (KORIE `Total` 은 할인 전 값, 우리 계약의 totalAmount 는 결제금액). `via = "gross"` 로 표시
  · 검산은 validators v2 (과세·면세 줄 반영)

라벨 규칙
- `meta.extra.itemsComplete = false`(KORIE 처럼 품목이 일부만 라벨된 경우) → 그 영수증은 품목
  **재현율만** 센다. 정밀도·환각률은 완전 라벨(합성 POS·CORD)에서만 의미가 있다
- `meta.unlabeledFields` 의 필드 → 분모에서 제외(판정 불가)
- `meta.unreadableFields` 의 필드 → exact match 제외. 예측이 null 이 아니고 신뢰도가 임계값
  이상이면 "과신"
- 라벨이 null(없음)인 필드 → 예측도 null 이어야 정답
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from statistics import mean
from typing import Any

from rapidfuzz import fuzz

from app.ocr.normalization import normalize_name
from app.ocr.schemas import EvidenceExtraction, ExtractedItem
from app.ocr.validators import is_valid_business_number, validate_extraction
from experiments.labels import LabelRecord

SCORING_RULES_VERSION = "scoring/v2"

SCALAR_FIELDS = (
    "supplierName",
    "supplierBusinessNo",
    "issuedDate",
    "supplyAmount",
    "vatAmount",
    "totalAmount",
)
AMOUNT_FIELDS = {"supplyAmount", "vatAmount", "totalAmount"}
NAME_SIMILARITY_THRESHOLD = 0.9
ITEM_NAME_THRESHOLD = 0.8

# 영수증 로고(영문)와 매장명 줄(한글)이 다른 브랜드. 평가용 별칭 — 운영 코드에는 쓰지 않는다.
BRAND_ALIASES = {
    "emart": "이마트",
    "e-mart": "이마트",
    "homeplus": "홈플러스",
    "lottemart": "롯데마트",
    "daiso": "다이소",
    "costco": "코스트코",
    "starbucks": "스타벅스",
    "oliveyoung": "올리브영",
}
_CORP_MARKS = re.compile(r"\(주\)|주\)|㈜|\(유\)|유\)|주식회사")
_NAME_PUNCT = re.compile(r"[\s_\-·.,/()\[\]]+")


def name_similarity(a: str | None, b: str | None) -> float:
    if not a or not b:
        return 0.0
    return fuzz.WRatio(_squash(a), _squash(b)) / 100


def _squash(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def _brand_key(text: str) -> str:
    """비교용 키 — 소문자·공백·구두점·법인 표기 제거, 영문 브랜드는 한글 별칭으로."""
    lowered = _CORP_MARKS.sub("", text.lower())
    for alias, korean in BRAND_ALIASES.items():
        lowered = lowered.replace(alias, korean)
    return _NAME_PUNCT.sub("", lowered)


def supplier_names_match(gt: str, pred: str) -> tuple[bool, str | None]:
    """(정답 여부, 근거). 근거: "similarity" | "contains" | None."""
    if name_similarity(gt, pred) >= NAME_SIMILARITY_THRESHOLD:
        return True, "similarity"
    gt_key, pred_key = _brand_key(gt), _brand_key(pred)
    if len(gt_key) >= 2 and gt_key in pred_key:
        return True, "contains"
    # 라벨이 "Homeplus 홈플러스" 처럼 로고 + 브랜드라면 브랜드 토큰이 예측에 있으면 된다.
    # 브랜드는 첫 토큰(또는 별칭표의 브랜드)만 본다 — 지점명 토큰("청주점")으로 맞추면 다른 브랜드의
    # 같은 지점이 정답이 되어 버린다.
    tokens = [_brand_key(token) for token in re.split(r"[\s_\-/]+", gt) if token]
    brands = {tokens[0]} | {t for t in tokens if t in BRAND_ALIASES.values()} if tokens else set()
    if any(len(token) >= 3 and token in pred_key for token in brands):
        return True, "contains"
    return False, None


def _digits(text: str | None) -> str | None:
    return re.sub(r"\D", "", text) if text else None


def _field_value(extraction: EvidenceExtraction, key: str) -> Any:
    return getattr(extraction, _snake(key))


def _snake(key: str) -> str:
    return re.sub(r"([A-Z])", lambda m: "_" + m[1].lower(), key)


def values_equal(key: str, gt: Any, pred: Any) -> bool:
    if gt is None or pred is None:
        return gt is None and pred is None
    if key == "supplierName":
        return supplier_names_match(str(gt), str(pred))[0]
    if key == "supplierBusinessNo":
        return _digits(str(gt)) == _digits(str(pred))
    if isinstance(gt, date) or isinstance(pred, date):
        return gt == pred
    return int(gt) == int(pred)


def total_matches(gt: int | None, prediction: EvidenceExtraction) -> tuple[bool, str | None]:
    """합계 채점. 라벨이 할인 전 총합계면 `예측 합계 + 예측 할인` 으로도 맞춰 본다."""
    pred = prediction.total_amount
    if values_equal("totalAmount", gt, pred):
        return True, None
    discount = prediction.discount_amount
    if gt is not None and pred is not None and discount and gt == pred + discount:
        return True, "gross"
    return False, None


@dataclass
class FieldOutcome:
    correct: bool | None  # None = 판정 불가 (unlabeled / unreadable)
    predicted: bool  # 예측이 null 이 아닌가
    confidence: float | None
    overconfident: bool = False  # unreadable 인데 높은 신뢰도로 값을 냄
    via: str | None = None  # 정답으로 친 근거가 exact 가 아닐 때 ("gross", "contains")


@dataclass
class ItemMatch:
    matched: list[tuple[ExtractedItem, ExtractedItem]] = field(default_factory=list)
    missed: list[ExtractedItem] = field(default_factory=list)  # 라벨에는 있는데 예측에 없음
    hallucinated: list[ExtractedItem] = field(default_factory=list)  # 예측에만 있음

    @property
    def precision(self) -> float | None:
        predicted = len(self.matched) + len(self.hallucinated)
        return len(self.matched) / predicted if predicted else None

    @property
    def recall(self) -> float | None:
        labeled = len(self.matched) + len(self.missed)
        return len(self.matched) / labeled if labeled else None


def match_items(
    labeled: list[ExtractedItem],
    predicted: list[ExtractedItem],
    *,
    threshold: float = ITEM_NAME_THRESHOLD,
) -> ItemMatch:
    """이름 유사도로 1:1 탐욕 매칭. 금액은 매칭 조건이 아니라 매칭 후 따로 채점한다."""
    candidates: list[tuple[float, int, int]] = []
    for i, gt in enumerate(labeled):
        for j, pred in enumerate(predicted):
            score = fuzz.WRatio(normalize_name(gt.raw_name), normalize_name(pred.raw_name)) / 100
            if score >= threshold:
                candidates.append((score, i, j))
    candidates.sort(key=lambda c: -c[0])
    used_gt: set[int] = set()
    used_pred: set[int] = set()
    result = ItemMatch()
    for _, i, j in candidates:
        if i in used_gt or j in used_pred:
            continue
        used_gt.add(i)
        used_pred.add(j)
        result.matched.append((labeled[i], predicted[j]))
    result.missed = [gt for i, gt in enumerate(labeled) if i not in used_gt]
    result.hallucinated = [p for j, p in enumerate(predicted) if j not in used_pred]
    return result


@dataclass
class ReceiptScore:
    source_id: str
    failed: bool
    fields: dict[str, FieldOutcome] = field(default_factory=dict)
    items: ItemMatch | None = None
    items_complete: bool = True  # False 면 예측에만 있는 행을 환각으로 세지 않는다
    item_amount_correct: int = 0
    item_quantity_correct: int = 0
    item_name_exact: int = 0  # 매칭된 행 중 정규화 후 이름이 완전히 같은 행 (오독 감지용)
    amount_reconciled: bool | None = None
    checksum_valid: bool | None = None
    needs_review: bool | None = None
    validation_issues: list[str] = field(default_factory=list)
    overridden_fields: list[str] = field(default_factory=list)  # 라벨 교정이 들어간 필드


def score_receipt(
    label: LabelRecord,
    prediction: EvidenceExtraction | None,
    *,
    confidence_threshold: float = 0.85,
) -> ReceiptScore:
    score = ReceiptScore(
        source_id=label.meta.source_id,
        failed=prediction is None,
        overridden_fields=list(label.meta.extra.get("overrides", [])),
    )
    if prediction is None:
        return score
    unlabeled = set(label.meta.unlabeled_fields)
    unreadable = set(label.meta.unreadable_fields)
    for key in SCALAR_FIELDS:
        pred_value = _field_value(prediction, key)
        confidence = prediction.field_confidence.get(key)
        if key in unlabeled:
            score.fields[key] = FieldOutcome(None, pred_value is not None, confidence)
            continue
        if key in unreadable:
            overconfident = pred_value is not None and (confidence or 0.0) >= confidence_threshold
            score.fields[key] = FieldOutcome(
                None, pred_value is not None, confidence, overconfident
            )
            continue
        gt_value = _field_value(label.extraction, key)
        via: str | None = None
        if key == "totalAmount":
            correct, via = total_matches(gt_value, prediction)
        elif key == "supplierName" and gt_value is not None and pred_value is not None:
            correct, via = supplier_names_match(str(gt_value), str(pred_value))
            via = None if via == "similarity" else via
        else:
            correct = values_equal(key, gt_value, pred_value)
        score.fields[key] = FieldOutcome(correct, pred_value is not None, confidence, via=via)

    if "items" not in unlabeled:
        score.items = match_items(label.extraction.items, prediction.items)
        score.items_complete = bool(label.meta.extra.get("itemsComplete", True))
        for gt, pred in score.items.matched:
            if gt.amount is not None and pred.amount == gt.amount:
                score.item_amount_correct += 1
            if gt.quantity is not None and pred.quantity == gt.quantity:
                score.item_quantity_correct += 1
            if normalize_name(gt.raw_name) == normalize_name(pred.raw_name):
                score.item_name_exact += 1

    report = validate_extraction(prediction)
    score.amount_reconciled = report.amounts_consistent
    score.needs_review = report.needs_review
    score.validation_issues = report.issues
    if prediction.supplier_business_no is not None:
        score.checksum_valid = is_valid_business_number(prediction.supplier_business_no)
    return score


def _rate(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def aggregate(scores: list[ReceiptScore]) -> dict[str, Any]:
    total = len(scores)
    failed = sum(s.failed for s in scores)
    fields: dict[str, Any] = {}
    for key in SCALAR_FIELDS:
        outcomes = [s.fields[key] for s in scores if not s.failed and key in s.fields]
        judged = [o for o in outcomes if o.correct is not None]
        correct = [o for o in judged if o.correct]
        wrong = [o for o in judged if not o.correct]
        conf_ok = [o.confidence for o in correct if o.confidence is not None]
        conf_bad = [o.confidence for o in wrong if o.confidence is not None]
        fields[key] = {
            "n": len(judged),
            "correct": len(correct),
            "accuracy": _rate(len(correct), len(judged)),
            "excluded": len(outcomes) - len(judged),
            "overconfident": sum(o.overconfident for o in outcomes),
            "correctVia": _via_counts(correct),
            "meanConfidenceCorrect": round(mean(conf_ok), 3) if conf_ok else None,
            "meanConfidenceWrong": round(mean(conf_bad), 3) if conf_bad else None,
        }

    item_scores = [s for s in scores if s.items is not None]
    complete = [s for s in item_scores if s.items_complete]
    matched = sum(len(s.items.matched) for s in item_scores)  # type: ignore[union-attr]
    missed = sum(len(s.items.missed) for s in item_scores)  # type: ignore[union-attr]
    # 정밀도·환각은 품목이 전부 라벨된 영수증에서만 — 부분 라벨에서는 진짜 행이 환각으로 잡힌다
    matched_complete = sum(len(s.items.matched) for s in complete)  # type: ignore[union-attr]
    hallucinated = sum(len(s.items.hallucinated) for s in complete)  # type: ignore[union-attr]
    recall = _rate(matched, matched + missed)
    precision = _rate(matched_complete, matched_complete + hallucinated) if complete else None
    f1 = (
        round(2 * precision * recall / (precision + recall), 4)
        if precision is not None and recall is not None and (precision + recall) > 0
        else None
    )
    items = {
        "receipts": len(item_scores),
        "itemsCompleteReceipts": len(complete),
        "labeledRows": matched + missed,
        "matched": matched,
        "missed": missed,
        "recall": recall,
        "precision": precision,
        "f1": f1,
        "hallucinated": hallucinated if complete else None,
        "hallucinatedRowRate": (
            _rate(hallucinated, matched_complete + hallucinated) if complete else None
        ),
        "receiptsWithHallucination": (
            sum(1 for s in complete if s.items and s.items.hallucinated) if complete else None
        ),
        "amountAccuracyOnMatched": _rate(sum(s.item_amount_correct for s in item_scores), matched),
        "quantityAccuracyOnMatched": _rate(
            sum(s.item_quantity_correct for s in item_scores), matched
        ),
        "nameExactOnMatched": _rate(sum(s.item_name_exact for s in item_scores), matched),
    }

    reconciled = [s.amount_reconciled for s in scores if s.amount_reconciled is not None]
    checksum = [s.checksum_valid for s in scores if s.checksum_valid is not None]
    reviewed = [s for s in scores if s.needs_review is not None]
    overridden = [s for s in scores if s.overridden_fields]
    override_fields: dict[str, int] = {}
    for s in overridden:
        for key in s.overridden_fields:
            override_fields[key] = override_fields.get(key, 0) + 1
    return {
        "scoringRules": SCORING_RULES_VERSION,
        "receipts": total,
        "processingFailureRate": _rate(failed, total),
        "schemaValidRate": _rate(total - failed, total),
        "fields": fields,
        "items": items,
        "amountReconciliationRate": _rate(sum(reconciled), len(reconciled)),
        "amountReconciliationN": len(reconciled),
        "needsReviewRate": _rate(sum(bool(s.needs_review) for s in reviewed), len(reviewed)),
        "businessNoChecksumPassRate": _rate(sum(checksum), len(checksum)),
        "businessNoPredictedN": len(checksum),
        "validationIssueCounts": _issue_counts(scores),
        "labelOverrides": {"receipts": len(overridden), "fields": override_fields},
    }


def _via_counts(outcomes: list[FieldOutcome]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for outcome in outcomes:
        if outcome.via:
            counts[outcome.via] = counts.get(outcome.via, 0) + 1
    return counts


def _issue_counts(scores: list[ReceiptScore]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for score in scores:
        for issue in score.validation_issues:
            counts[issue] = counts.get(issue, 0) + 1
    return dict(sorted(counts.items(), key=lambda kv: -kv[1]))


def format_markdown(metrics: dict[str, Any]) -> str:
    """사람이 읽는 요약표. 숫자는 metrics.json 이 정본."""
    failure = _pct(metrics["processingFailureRate"])
    lines = [
        f"| 영수증 | {metrics['receipts']} | 처리 실패율 | {failure} |",
        "| --- | --- | --- | --- |",
    ]
    for key, value in metrics["fields"].items():
        extra = f" (과신 {value['overconfident']})" if value["overconfident"] else ""
        via = value.get("correctVia") or {}
        if via:
            extra += " (" + ", ".join(f"{k} {v}" for k, v in via.items()) + ")"
        confidence = f"{value['meanConfidenceCorrect']}/{value['meanConfidenceWrong']}"
        lines.append(
            f"| {key} | {_pct(value['accuracy'])} | n={value['n']} | "
            f"conf 맞음/틀림 {confidence}{extra} |"
        )
    items = metrics["items"]
    halluc = "-" if items["hallucinated"] is None else str(items["hallucinated"])
    halluc_receipts = (
        "-" if items["receiptsWithHallucination"] is None else items["receiptsWithHallucination"]
    )
    lines.append(
        f"| 품목 행 P/R/F1 | {_pct(items['precision'])} / {_pct(items['recall'])} / "
        f"{_pct(items['f1'])} | 환각 행 | {halluc} "
        f"({_pct(items['hallucinatedRowRate'])}, 영수증 {halluc_receipts}건, "
        f"완전 라벨 {items['itemsCompleteReceipts']}장 기준) |"
    )
    lines.append(
        f"| 품목 금액/수량/이름 exact | {_pct(items['amountAccuracyOnMatched'])} / "
        f"{_pct(items['quantityAccuracyOnMatched'])} / {_pct(items.get('nameExactOnMatched'))} | "
        f"검산 일치율 | {_pct(metrics['amountReconciliationRate'])} "
        f"(n={metrics['amountReconciliationN']}) |"
    )
    overrides = metrics.get("labelOverrides") or {"receipts": 0, "fields": {}}
    override_text = ", ".join(f"{k} {v}" for k, v in overrides["fields"].items()) or "-"
    lines.append(
        f"| 사업자번호 체크섬 통과 | {_pct(metrics['businessNoChecksumPassRate'])} "
        f"(예측 {metrics['businessNoPredictedN']}건) | needsReview 비율 | "
        f"{_pct(metrics.get('needsReviewRate'))} |"
    )
    lines.append(
        f"| 라벨 교정 | {overrides['receipts']}장 ({override_text}) | 채점 규칙 | "
        f"{metrics.get('scoringRules', 'scoring/v1')} |"
    )
    return "\n".join(lines)


def _pct(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:.1f}%"
