"""live 경로 스모크 테스트 — 키·모델·구조화 출력이 실제로 동작하는지 이미지로 확인한다.

    uv run --frozen python -X utf8 -m scripts.smoke_vlm receipt.jpg
    uv run --frozen python -X utf8 -m scripts.smoke_vlm p1.jpg p2.jpg --sales PRINTOUT

첫 줄은 증빙 추출(20.4), 둘째 줄은 매출 인쇄물(20.9). `-X utf8` 은 Windows 콘솔에서 한글 출력이
깨지지 않게 한다(없으면 UnicodeEncodeError).

`ANTHROPIC_API_KEY` 가 .env 또는 환경 변수에 있어야 한다. 비용이 발생한다(§7.2 기준 장당 수 센트).
원본 이미지는 출력하지 않는다 — 결과 JSON 과 모델 메타만 찍는다. 결과는 어디에도 저장되지 않는다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from app.common.claude import ClaudeClient
from app.common.local_vlm import LocalVlmClient
from app.config import Settings
from app.ocr.pipelines.base import ExtractionError
from app.ocr.pipelines.claude_vlm import ClaudeVisionPipeline
from app.ocr.pipelines.local_vlm import LocalVlmPipeline
from app.ocr.preprocess import prepare_evidence_images, prepare_image
from app.ocr.schemas import EvidenceExtractionRequest, ProductSalesExtractionRequest
from app.ocr.validators import validate_extraction


async def run(args: argparse.Namespace) -> int:
    settings = Settings()  # .env 를 읽는다
    if args.local or settings.vlm_provider == "local":
        print(f"[경로] 로컬 VLM {settings.local_vlm_model} @ {settings.local_vlm_base_url}")
        pipeline = LocalVlmPipeline(settings, LocalVlmClient(settings))
    else:
        print(f"[경로] Claude {settings.vision_model}")
        pipeline = ClaudeVisionPipeline(settings, ClaudeClient(settings))
    images = [
        prepare_image(
            path.read_bytes(), settings.image_long_edge, crop_margins=settings.image_crop_margins
        )
        for path in args.images
    ]
    for path, image in zip(args.images, images, strict=True):
        crop = " crop" if image.cropped else ""
        print(f"[입력] {path.name}: {image.width}x{image.height} (scale {image.scale:.2f}{crop})")

    try:
        if args.sales:
            request = ProductSalesExtractionRequest(
                job_id=0,
                business_id=0,
                capture_type=args.sales,
                file_object_keys=[path.name for path in args.images],
                known_products=args.known or [],
            )
            extraction, meta = await pipeline.extract_product_sales(images, request)
            result = extraction.model_dump(by_alias=True, mode="json")
        else:
            request = EvidenceExtractionRequest(
                job_id=0,
                evidence_id=0,
                file_object_key=args.images[0].name,
                evidence_type_hint=args.type,
            )
            tiles = prepare_evidence_images(
                args.images[0].read_bytes(),
                settings.image_long_edge,
                tile_tall=args.tiling or settings.evidence_tiling,
                max_aspect=settings.evidence_tile_max_aspect,
                crop_margins=settings.image_crop_margins,
            )
            if len(tiles) > 1:
                sizes = ", ".join(f"{t.width}x{t.height}" for t in tiles)
                print(f"[타일] {len(tiles)}장: {sizes}")
            extraction, meta = await pipeline.extract_evidence(tiles, request)
            result = {
                **extraction.model_dump(by_alias=True, mode="json"),
                "validation": validate_extraction(extraction).model_dump(
                    by_alias=True, mode="json"
                ),
            }
    except ExtractionError as exc:
        print(f"[실패] stage={exc.stage}: {exc}")
        return 1

    print(
        json.dumps(
            {"model": meta.model_dump(by_alias=True), "result": result},
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("images", nargs="+", type=Path, help="이미지 파일 (매출 인쇄물은 여러 장)")
    parser.add_argument("--type", default="RECEIPT", help="evidenceTypeHint (기본 RECEIPT)")
    parser.add_argument(
        "--sales", choices=["SCREEN", "PRINTOUT"], help="매출 인쇄물 모드. 없으면 증빙 추출"
    )
    parser.add_argument("--known", nargs="*", help="knownProducts (매출 인쇄물 모드)")
    parser.add_argument("--tiling", action="store_true", help="긴 영수증 타일링 (증빙 모드)")
    parser.add_argument("--local", action="store_true", help="로컬 VLM(Ollama) 로 강제 (토큰 0)")
    return asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    raise SystemExit(main())
