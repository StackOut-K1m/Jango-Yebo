"""촬영 열화 합성 — 스캔·렌더 이미지를 휴대폰 촬영처럼 만든다. 라벨은 그대로 둔다.

직접 촬영을 하지 않으므로 KORIE 스캔에 원근·그림자·반사·블러·노이즈·JPEG 를 얹어 강건성을 본다
(mobile-sim). `screen`·`thermal` 프리셋은 합성 매출현황 생성기가 쓴다.
GT 값은 바뀌지 않지만 **좌표(boundingBox)는 무효**가 된다 — 우리 라벨은 좌표를 쓰지 않는다.

    uv run python -X utf8 -m experiments.degrade datasets/receipts/korie/val \\
        --out datasets/receipts/korie-mobile-sim/val --preset medium --seed 42

결과는 원본 벤치마크와 별도 표로 보고한다(섞어 평균내지 않는다).
"""

from __future__ import annotations

import argparse
import zlib
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from experiments.labels import iter_labels, read_manifest, write_label, write_manifest

DEGRADE_VERSION = "degrade/v1"

# 각 값은 강도 상한. 실제 값은 시드로 뽑는다.
PRESETS: dict[str, dict[str, Any]] = {
    "light": {
        "margin": 0.06, "perspective": 0.02, "rotate": 1.5, "shadow": 0.15, "glare": 0.0,
        "blur": 0.6, "noise": 3.0, "jpeg": (80, 92), "fade": 0.0, "curl": 0.0,
    },
    "medium": {
        "margin": 0.10, "perspective": 0.05, "rotate": 3.0, "shadow": 0.30, "glare": 0.25,
        "blur": 1.2, "noise": 6.0, "jpeg": (60, 85), "fade": 0.0, "curl": 0.0,
    },
    "heavy": {
        "margin": 0.14, "perspective": 0.09, "rotate": 5.0, "shadow": 0.45, "glare": 0.45,
        "blur": 2.0, "noise": 10.0, "jpeg": (40, 70), "fade": 0.0, "curl": 0.0,
    },
    # POS 화면 촬영: 반사·약한 원근, 도트 없음
    "screen": {
        "margin": 0.08, "perspective": 0.04, "rotate": 2.0, "shadow": 0.10, "glare": 0.35,
        "blur": 0.8, "noise": 4.0, "jpeg": (65, 88), "fade": 0.0, "curl": 0.0,
    },
    # 감열 인쇄물: 퇴색 띠·종이 곡률·그림자
    "thermal": {
        "margin": 0.12, "perspective": 0.05, "rotate": 3.0, "shadow": 0.35, "glare": 0.10,
        "blur": 0.9, "noise": 7.0, "jpeg": (55, 85), "fade": 0.35, "curl": 0.02,
    },
}  # fmt: skip


def _f32(img: np.ndarray) -> np.ndarray:
    return img.astype(np.float32)


def _u8(img: np.ndarray) -> np.ndarray:
    return np.clip(np.rint(img), 0, 255).astype(np.uint8)


def thermal_fade(img: np.ndarray, strength: float, rng: np.random.Generator) -> np.ndarray:
    """행 단위로 잉크가 옅어진 띠 — 글자를 흰색 쪽으로 섞는다. 종이 위에서만 적용한다."""
    if strength <= 0:
        return img
    height = img.shape[0]
    profile = rng.normal(0, 1, size=height).cumsum()
    profile = (profile - profile.min()) / (np.ptp(profile) or 1.0)  # 0~1, 저주파
    factor = 1.0 - strength * profile  # 1 = 그대로, 낮을수록 옅어짐
    ink = 255.0 - _f32(img)
    return _u8(255.0 - ink * factor[:, None, None])


def curl(img: np.ndarray, amplitude: float, rng: np.random.Generator) -> np.ndarray:
    """종이 곡률 — 세로 위치에 따라 가로로 사인파만큼 밀린다."""
    if amplitude <= 0:
        return img
    height, width = img.shape[:2]
    ys, xs = np.mgrid[0:height, 0:width].astype(np.float32)
    phase = rng.uniform(0, 2 * np.pi)
    cycles = rng.uniform(0.5, 1.5)
    shift = amplitude * width * np.sin(2 * np.pi * cycles * ys / height + phase)
    return cv2.remap(img, xs + shift, ys, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def paste_on_background(img: np.ndarray, margin: float, rng: np.random.Generator) -> np.ndarray:
    """책상 위에 놓인 것처럼 주변 여백을 만든다."""
    height, width = img.shape[:2]
    pad_h, pad_w = int(height * margin), int(width * margin)
    base = rng.uniform(70, 170)
    color = np.array([base * 0.85, base * 0.92, base], dtype=np.float32)  # BGR, 약간 따뜻한 회색
    canvas = np.empty((height + 2 * pad_h, width + 2 * pad_w, 3), dtype=np.float32)
    canvas[:] = color
    gradient = np.linspace(0.9, 1.1, canvas.shape[1], dtype=np.float32)[None, :, None]
    canvas *= gradient
    dy = int(rng.uniform(-pad_h * 0.4, pad_h * 0.4)) if pad_h else 0
    dx = int(rng.uniform(-pad_w * 0.4, pad_w * 0.4)) if pad_w else 0
    y0, x0 = pad_h + dy, pad_w + dx
    canvas[y0 : y0 + height, x0 : x0 + width] = _f32(img)
    return _u8(canvas)


def perspective(img: np.ndarray, strength: float, rng: np.random.Generator) -> np.ndarray:
    if strength <= 0:
        return img
    height, width = img.shape[:2]
    src = np.float32([[0, 0], [width, 0], [width, height], [0, height]])
    jitter = rng.uniform(-strength, strength, size=(4, 2)) * np.float32([width, height])
    matrix = cv2.getPerspectiveTransform(src, src + jitter.astype(np.float32))
    return cv2.warpPerspective(img, matrix, (width, height), borderMode=cv2.BORDER_REPLICATE)


def rotate(img: np.ndarray, max_deg: float, rng: np.random.Generator) -> tuple[np.ndarray, float]:
    if max_deg <= 0:
        return img, 0.0
    angle = float(rng.uniform(-max_deg, max_deg))
    height, width = img.shape[:2]
    matrix = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 1.0)
    return cv2.warpAffine(img, matrix, (width, height), borderMode=cv2.BORDER_REPLICATE), angle


def shadow(img: np.ndarray, strength: float, rng: np.random.Generator) -> np.ndarray:
    """한쪽으로 어두워지는 조명 + 부드러운 그림자 띠."""
    if strength <= 0:
        return img
    height, width = img.shape[:2]
    ys, xs = np.mgrid[0:height, 0:width].astype(np.float32)
    angle = rng.uniform(0, 2 * np.pi)
    proj = (xs * np.cos(angle) + ys * np.sin(angle)) / max(height, width)
    proj = (proj - proj.min()) / (np.ptp(proj) or 1.0)
    mask = 1.0 - strength * proj
    center, width_band = rng.uniform(0.2, 0.8), rng.uniform(0.05, 0.15)
    mask *= 1.0 - strength * 0.6 * np.exp(-((proj - center) ** 2) / (2 * width_band**2))
    return _u8(_f32(img) * mask[:, :, None])


def glare(img: np.ndarray, strength: float, rng: np.random.Generator) -> np.ndarray:
    """반사광 — 밝은 가우시안 덩어리."""
    if strength <= 0:
        return img
    height, width = img.shape[:2]
    ys, xs = np.mgrid[0:height, 0:width].astype(np.float32)
    cy, cx = rng.uniform(0, height), rng.uniform(0, width)
    sigma = rng.uniform(0.15, 0.3) * min(height, width)
    blob = np.exp(-((xs - cx) ** 2 + (ys - cy) ** 2) / (2 * sigma**2))
    return _u8(_f32(img) + strength * 255.0 * blob[:, :, None])


def blur(img: np.ndarray, sigma_max: float, rng: np.random.Generator) -> np.ndarray:
    if sigma_max <= 0:
        return img
    sigma = float(rng.uniform(0.3, sigma_max))
    out = cv2.GaussianBlur(img, (0, 0), sigma)
    if sigma_max >= 1.0 and rng.random() < 0.5:  # 손떨림
        length = int(rng.integers(3, 8))
        kernel = np.zeros((length, length), dtype=np.float32)
        kernel[length // 2, :] = 1.0 / length
        angle = float(rng.uniform(0, 180))
        matrix = cv2.getRotationMatrix2D((length / 2 - 0.5, length / 2 - 0.5), angle, 1.0)
        kernel = cv2.warpAffine(kernel, matrix, (length, length))
        kernel /= kernel.sum() or 1.0
        out = cv2.filter2D(out, -1, kernel)
    return out


def noise(img: np.ndarray, sigma: float, rng: np.random.Generator) -> np.ndarray:
    if sigma <= 0:
        return img
    return _u8(_f32(img) + rng.normal(0, sigma, size=img.shape).astype(np.float32))


def jpeg(
    img: np.ndarray, quality_range: tuple[int, int], rng: np.random.Generator
) -> tuple[np.ndarray, int]:
    quality = int(rng.integers(quality_range[0], quality_range[1] + 1))
    ok, buffer = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        return img, quality
    return cv2.imdecode(buffer, cv2.IMREAD_COLOR), quality


def degrade(image_bgr: np.ndarray, preset: str, seed: int) -> tuple[np.ndarray, dict[str, Any]]:
    """프리셋과 시드로 결정론적 열화. (이미지, 적용 파라미터) 를 돌려준다."""
    params = PRESETS[preset]
    rng = np.random.default_rng(seed)
    img = image_bgr
    img = thermal_fade(img, params["fade"], rng)  # 종이 위 효과는 배경을 붙이기 전에
    img = curl(img, params["curl"], rng)
    img = paste_on_background(img, params["margin"], rng)
    img = perspective(img, params["perspective"], rng)
    img, angle = rotate(img, params["rotate"], rng)
    img = shadow(img, params["shadow"], rng)
    img = glare(img, params["glare"], rng)
    img = blur(img, params["blur"], rng)
    img = noise(img, params["noise"], rng)
    img, quality = jpeg(img, params["jpeg"], rng)
    applied = {"preset": preset, "seed": seed, "rotateDeg": round(angle, 2), "jpegQuality": quality}
    return img, applied


def stable_seed(base_seed: int, source_id: str) -> int:
    """같은 시드·같은 표본이면 같은 열화가 나오게 한다(재현성)."""
    return (base_seed * 1_000_003 + zlib.crc32(source_id.encode("utf-8"))) % (2**32)


def degrade_dataset(dataset: Path, out: Path, preset: str, seed: int) -> int:
    out.mkdir(parents=True, exist_ok=True)
    count = 0
    for label_path, label in iter_labels(dataset):
        image = cv2.imread(str(label_path.with_name(label.image)), cv2.IMREAD_COLOR)
        if image is None:
            print(f"[건너뜀] 이미지를 읽지 못함: {label.image}")
            continue
        sample_seed = stable_seed(seed, label.meta.source_id)
        degraded, applied = degrade(image, preset, sample_seed)
        target = out / f"{label_path.stem}.jpg"
        cv2.imwrite(str(target), degraded, [cv2.IMWRITE_JPEG_QUALITY, 95])
        copy = label.model_copy(deep=True)
        copy.image = target.name
        copy.meta.source = f"{label.meta.source}-mobile-sim"
        copy.meta.notes = [*label.meta.notes, f"degrade {preset} seed={sample_seed}"]
        copy.meta.extra = {**label.meta.extra, "degrade": applied}
        write_label(target.with_suffix(".json"), copy)
        count += 1
    write_manifest(
        out,
        {
            "dataset": f"{dataset.name}-mobile-sim",
            "sourceDataset": str(dataset),
            "sourceManifest": read_manifest(dataset),
            "degrade": {
                "version": DEGRADE_VERSION,
                "preset": preset,
                "params": PRESETS[preset],
                "seed": seed,
            },
            "generatedAt": datetime.now(UTC).isoformat(timespec="seconds"),
            "count": count,
            "note": "GT 값은 원본과 같다. 좌표(boundingBox)는 무효. 원본 벤치마크와 별도 보고.",
        },
    )
    return count


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("dataset", type=Path, help="원본 라벨 디렉터리 (증빙 라벨)")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--preset", choices=sorted(PRESETS), default="medium")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()
    count = degrade_dataset(args.dataset, args.out, args.preset, args.seed)
    print(f"{count}장 생성 → {args.out} (preset={args.preset}, seed={args.seed})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
