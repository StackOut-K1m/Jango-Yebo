"""전처리(`app/ocr/preprocess.py`)가 CPU 를 얼마나 쓰는지 잰다 — 티켓 77 후속.

**왜 재나.** 77 에서 자체 호스팅 **모델 호출**만 `compute` 세마포어로 감쌌다(명세 20.1 ④).
전처리는 Claude 경로에서도 도는 **로컬 CPU 연산**이라 같은 세마포어에 넣어야 하는지 물음이
남았다. 실측 없이 정하지 않기로 했으므로 숫자부터 만든다.

**무엇을 믿을 수 있나.** 벽시계는 잰 장비 것이다(아래 값은 22 코어 노트북에서 나왔고 배포
호스트는 4 vCPU 다). 장비를 건너 비교할 수 있는 값은 **CPU 시간(core·s)** 이다 — 한 장에
드는 총 연산량이고, 예측 모델과 다투는 양이 바로 그것이다. `--threads` 로 OpenCV 스레드를
배포와 같게 묶고 벽시계·CPU 를 함께 낸다.

    uv run --frozen python -X utf8 -m experiments.measure_preprocess_cpu --count 40 --threads 4

조건 넷은 운영에서 실제로 도는 조합이다(`app/ocr/router.py`).

| 조건 | 어디서 | 설정 |
| --- | --- | --- |
| `basic` | 매출 인쇄물·일반 (`_prepare_all`) | 장변 1600, 절단 off |
| `evidence` | 증빙 기본 (`_prepare_evidence`) | 위와 같음 (타일 off) |
| `thorough` | 증빙 재추출 `extractionMode=THOROUGH` | 타일 on |
| `crop` | `IMAGE_CROP_MARGINS=true` 를 켤 때 | 절단 on |

`decode` 는 조건이 아니라 셋 모두에 들어 있는 공통 구간이다. 어디가 무거운지 가르려고 잰다.

실측 (2026-09-14, KORIE val 40장 × 3바퀴, OpenCV 스레드 4)

    조건         벽 중앙    CPU/장    코어
    decode         64ms      69ms    1.01
    basic          93ms     121ms    1.20
    evidence       89ms     112ms    1.21
    thorough       96ms     118ms    1.17   (타일 2장)
    crop          164ms     202ms    1.19

    동시 1건 → 코어 1.22 · 동시 4건 → 코어 3.96

읽을 것은 둘이다.

- **평소에는 실질 단일 스레드다**(코어 1.2). 64ms 가 디코드(PIL)라 OpenCV 스레드를 늘려도
  안 빨라진다. 한 장 0.11 core·s 다.
- **동시 4건이 겹치면 4 vCPU 를 다 먹는다.** 다만 한 장 90ms 라 짧고 유계다.

세마포어에 넣을지는 예측 추론이 얼마나 걸리느냐에 달렸고 **그 숫자는 여기 없다** — 90ms 경합이
문제가 되는 길이인지는 예측 담당이 판단한다. 이 도구는 그 판단에 필요한 쪽만 잰다.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import cv2

from app.config import Settings

# 운영과 같은 값을 쓴다. 인스턴스를 만들면 환경 변수를 타므로 필드 기본값만 읽는다 —
# 여기에 숫자를 복제해 두면 `config.py` 가 바뀌었을 때 조용히 딴 것을 재게 된다.
LONG_EDGE: int = Settings.model_fields["image_long_edge"].default
MAX_ASPECT: float = Settings.model_fields["evidence_tile_max_aspect"].default


def _conditions() -> dict[str, Callable[[bytes], Any]]:
    # `_decode` 는 비공개지만 일부러 부른다. 셋에 공통으로 든 구간을 따로 재야
    # "전처리가 무겁다"가 디코드 얘기인지 리사이즈 얘기인지 갈린다.
    from app.ocr.preprocess import _decode, prepare_evidence_images, prepare_image

    return {
        "decode": lambda raw: _decode(raw),
        "basic": lambda raw: prepare_image(raw, LONG_EDGE),
        "evidence": lambda raw: prepare_evidence_images(raw, LONG_EDGE),
        "thorough": lambda raw: prepare_evidence_images(
            raw, LONG_EDGE, tile_tall=True, max_aspect=MAX_ASPECT
        ),
        "crop": lambda raw: prepare_image(raw, LONG_EDGE, crop_margins=True),
    }


def _percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))
    return ordered[index]


def measure(raws: list[bytes], repeat: int) -> dict[str, dict[str, Any]]:
    """조건마다 전체를 `repeat` 바퀴 돌고, 벽시계는 장당·CPU 는 구간 전체로 잰다.

    **CPU 를 장당 재지 않는 이유**: Windows `process_time()` 의 눈금이 15.6ms 라 60ms 짜리
    한 장을 재면 네 칸이다. 구간 전체(수 초)로 재서 호출 수로 나누면 그 오차가 사라진다.
    벽시계는 `perf_counter` 라 눈금이 충분해 장당으로 둔다 — p95 를 봐야 한다.
    """
    out: dict[str, dict[str, Any]] = {}
    for name, fn in _conditions().items():
        fn(raws[0])  # 코덱·룩업테이블을 올린다. 이 한 번은 표본에 넣지 않는다
        walls: list[float] = []
        counts: list[int] = []
        cpu0 = time.process_time()
        block0 = time.perf_counter()
        for _ in range(repeat):
            for raw in raws:
                start = time.perf_counter()
                result = fn(raw)
                walls.append((time.perf_counter() - start) * 1000)
                counts.append(len(result) if isinstance(result, list) else 1)
        block_wall = (time.perf_counter() - block0) * 1000
        block_cpu = (time.process_time() - cpu0) * 1000
        calls = len(walls)
        out[name] = {
            "wallMedianMs": round(statistics.median(walls), 1),
            "wallP95Ms": round(_percentile(walls, 0.95), 1),
            "wallMeanMs": round(block_wall / calls, 1),
            "cpuMeanMs": round(block_cpu / calls, 1),
            # 1.0 이면 한 코어만 쓴다. 2.0 이면 벽시계 1초 동안 두 코어를 먹었다는 뜻이다.
            "coresUsed": round(block_cpu / max(1e-6, block_wall), 2),
            "imagesMedian": statistics.median(counts),
        }
    return out


def measure_concurrent(
    raws: list[bytes], workers: int, condition: str, repeat: int = 3
) -> dict[str, Any]:
    """전처리 여러 장이 **동시에** 들어올 때 코어를 몇 개나 먹는지.

    운영에서 `_prepare_all` 은 장마다 `anyio.to_thread.run_sync` 로 스레드에 던진다. 요청이
    겹치면 전처리도 겹친다 — 예측 모델이 굶는 상황이 있다면 평균이 아니라 여기다.
    """
    fn = _conditions()[condition]
    fn(raws[0])  # 워밍업
    work = raws * repeat  # 한 바퀴는 표본이 적어 흔들린다
    cpu0 = time.process_time()
    wall0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(fn, work))
    wall = (time.perf_counter() - wall0) * 1000
    cpu = (time.process_time() - cpu0) * 1000
    return {
        "workers": workers,
        "condition": condition,
        "images": len(work),
        "wallMs": round(wall, 1),
        "cpuMs": round(cpu, 1),
        "coresUsed": round(cpu / max(1e-6, wall), 2),
        "perImageWallMs": round(wall / len(work), 1),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="전처리 CPU 실측 (티켓 77 후속)")
    parser.add_argument("--images", type=Path, default=Path("datasets/korie/raw/val/images"))
    parser.add_argument("--count", type=int, default=40)
    parser.add_argument("--repeat", type=int, default=3, help="전체를 몇 바퀴 돌지")
    parser.add_argument(
        "--threads",
        type=int,
        default=4,
        help="OpenCV 스레드 상한. 배포 호스트가 4 vCPU 라 기본 4",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=4,
        help="동시에 전처리하는 요청 수. 0 이면 동시 실행은 재지 않는다",
    )
    parser.add_argument("--out", type=Path, default=Path("artifacts/preprocess-cpu.json"))
    args = parser.parse_args()

    cv2.setNumThreads(args.threads)
    paths = sorted(p for p in args.images.glob("*") if p.is_file())[: args.count]
    if not paths:
        raise SystemExit(f"이미지가 없다: {args.images}")

    raws = [path.read_bytes() for path in paths]  # 디스크 읽기는 전처리가 아니다
    result = measure(raws, args.repeat)
    # 1개는 비교 기준이다. 같은 수가 두 번 나오지 않게 묶는다.
    worker_counts = sorted({1, args.concurrency}) if args.concurrency > 0 else []
    concurrent = [
        measure_concurrent(raws, workers, "evidence", args.repeat) for workers in worker_counts
    ]
    payload = {
        "images": len(paths),
        "source": str(args.images),
        "cv2Threads": args.threads,
        "repeat": args.repeat,
        "longEdge": LONG_EDGE,
        "conditions": result,
        "concurrent": concurrent,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"{len(paths)}장 × {args.repeat}바퀴 · OpenCV 스레드 {args.threads} · 장변 {LONG_EDGE}")
    print()
    columns = ("조건", "벽 중앙", "벽 p95", "벽 평균", "CPU 평균", "코어", "장")
    widths = (10, 9, 8, 8, 9, 5, 4)
    print(" ".join(f"{c:>{w}}" for c, w in zip(columns, widths, strict=True)))
    for name, row in result.items():
        print(
            f"{name:<10} {row['wallMedianMs']:>8.1f}ms {row['wallP95Ms']:>7.1f}ms "
            f"{row['wallMeanMs']:>7.1f}ms {row['cpuMeanMs']:>8.1f}ms "
            f"{row['coresUsed']:>5.2f} {row['imagesMedian']:>4.1f}"
        )
    for row in concurrent:
        print()
        print(
            f"동시 {row['workers']}개 · {row['condition']} {row['images']}장 "
            f"→ 벽시계 {row['wallMs']:.0f}ms (장당 {row['perImageWallMs']:.1f}ms) "
            f"· 코어 {row['coresUsed']:.2f}"
        )
    print()
    print(f"기록: {args.out}")


if __name__ == "__main__":
    main()
