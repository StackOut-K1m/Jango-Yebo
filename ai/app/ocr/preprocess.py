"""이미지 전처리 — EXIF 회전 보정 → 장변 리사이즈 → JPEG 재인코딩.

§6.4: 전처리 후 장변 1,600px 내외. 원본 4,000px 을 그대로 보내면 이미지 토큰만 3배가 되고
정확도는 거의 오르지 않는다.

긴 영수증 타일링(선택, `prepare_evidence_images(..., tile_tall=True)`)
- 영수증은 폭 900~1,400px 에 높이 3,000px 안팎이라 장변 1,600 기준으로 줄이면 폭이 절반이 된다.
  Claude API 도 장변 1,568px 을 넘는 이미지를 서버에서 다시 줄이므로 `long_edge` 를 올려도 소용없다.
- 대신 세로로 긴 이미지를 가로세로비 ≤ `max_aspect` 인 타일로 자르고(겹침 포함) 타일마다 장변
  리사이즈를 적용하면 글자 해상도가 올라간다. 비용은 타일 수만큼 이미지 토큰이 늘어난다.
- 실측(KORIE val 50장): 축소 비율 중앙값 0.50, 46장이 0.6 미만. 품목명 오독은 소수라 기본은 꺼 둔다.

여백 절단(선택, `crop_margins=True`)
- 촬영 이미지는 종이 바깥 배경이 넓다. 배경을 잘라내면 같은 장변 예산이 글자에 돌아간다.
- 밝은 종이 / 어두운 배경을 Otsu 로 갈라 종이의 바깥 사각형만 남긴다. 감지가 어긋나면
  (남는 면적이 너무 작으면) 원본을 그대로 쓴다 — 잘라서 잃는 것이 더 크다.
- **절단만으로는 토큰이 오히려 는다.** 배경을 없애면 화면이 정사각형에 가까워지고, 장변 고정에서
  토큰은 짧은 변/긴 변 비율에 비례하기 때문이다. 실측(mobile-sim 50장, 2026-09-08):

      절단 없음 1600 : 토큰 1,394 · 종이 폭 609px (기준)
      절단 + 1600    : 토큰 +6.9% · 글자 +14.7%   ← 정확도용
      절단 + 1400    : 토큰 −18.2% · 글자 +0.4%   ← 같은 글자 크기로 더 싸다 (권장)
      절단 + 1200    : 토큰 −39.9% · 글자 −13.9%

  그래서 절단은 `IMAGE_LONG_EDGE` 를 함께 낮출 때 비용이 준다. 둘 중 무엇을 고르든 정확도는
  실제 모델로 확인한 뒤 켠다.
- 흰 배경 스캔(KORIE 원본)은 종이가 화면을 채워 50장 중 1장만 절단됐다. 켜도 손해가 없다.

기울기 보정·이진화는 OCR_PIPELINE 공급자를 붙일 때 experiments/ 에서 효과를 확인한 뒤 넣는다 —
VLM 경로에는 오히려 해로울 수 있다.
"""

from __future__ import annotations

import io
import math
from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from app.common.errors import AppError, ErrorCode

TILE_MAX_ASPECT = 1.5  # 타일 높이/폭 상한. 1.0 이면 정사각형 타일(해상도 최대, 토큰 최대)
TILE_OVERLAP = 0.06  # 타일 사이 겹침(타일 높이 대비). 경계에 걸린 행이 한 타일에는 온전히 들어간다
CROP_MIN_AREA_RATIO = 0.15  # 감지된 종이가 이보다 작으면 오검지로 보고 절단하지 않는다
CROP_PADDING = 8  # 잘라낸 사각형 바깥으로 남기는 여유(px) — 글자가 경계에 붙는 것을 막는다


@dataclass(frozen=True)
class PreparedImage:
    data: bytes
    media_type: str
    width: int
    height: int
    scale: float  # 원본 대비 배율. boundingBox 를 원본 좌표로 되돌릴 때 쓴다
    # 타일링일 때 원본에서 잘라낸 세로 구간(픽셀). 전체 이미지면 (0, 원본 높이)
    tile_index: int = 0
    tile_count: int = 1
    origin_y: int = 0
    cropped: bool = False  # 여백 절단이 실제로 적용됐나 (진단·좌표 환산용)


def _decode(raw: bytes) -> np.ndarray:
    try:
        with Image.open(io.BytesIO(raw)) as image:
            # 휴대폰 촬영 이미지는 EXIF 로만 방향이 기록된다. 픽셀을 실제로 돌려 둔다.
            transposed = ImageOps.exif_transpose(image) or image
            return cv2.cvtColor(np.asarray(transposed.convert("RGB")), cv2.COLOR_RGB2BGR)
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise AppError(ErrorCode.VALIDATION_FAILED, "이미지를 해석할 수 없습니다.") from exc


def crop_to_content(array: np.ndarray) -> tuple[np.ndarray, bool]:
    """종이(밝은 영역) 바깥 배경을 잘라낸다. (잘린 배열, 적용됐나).

    감지에 실패하거나 남는 면적이 `CROP_MIN_AREA_RATIO` 미만이면 원본을 그대로 돌려준다 —
    잘못 자르면 합계 줄이나 상호가 날아가고, 그 손해가 토큰 절감보다 크다.
    """
    height, width = array.shape[:2]
    if height < 32 or width < 32:
        return array, False
    gray = cv2.cvtColor(array, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    _, mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    points = cv2.findNonZero(mask)
    if points is None:
        return array, False
    x, y, w, h = cv2.boundingRect(points)
    if w * h < CROP_MIN_AREA_RATIO * width * height:
        return array, False
    x0 = max(0, x - CROP_PADDING)
    y0 = max(0, y - CROP_PADDING)
    x1 = min(width, x + w + CROP_PADDING)
    y1 = min(height, y + h + CROP_PADDING)
    if (x1 - x0, y1 - y0) == (width, height):
        return array, False  # 잘릴 게 없었다 (흰 배경 스캔)
    return array[y0:y1, x0:x1], True


def _encode(
    array: np.ndarray,
    long_edge: int,
    jpeg_quality: int,
    *,
    tile_index: int = 0,
    tile_count: int = 1,
    origin_y: int = 0,
    cropped: bool = False,
) -> PreparedImage:
    height, width = array.shape[:2]
    scale = 1.0
    longest = max(height, width)
    if longest > long_edge:
        scale = long_edge / longest
        size = (max(1, round(width * scale)), max(1, round(height * scale)))
        array = cv2.resize(array, size, interpolation=cv2.INTER_AREA)

    encoded, buffer = cv2.imencode(".jpg", array, [cv2.IMWRITE_JPEG_QUALITY, jpeg_quality])
    if not encoded:
        raise AppError(ErrorCode.INTERNAL_ERROR, "이미지 인코딩에 실패했습니다.")
    return PreparedImage(
        data=buffer.tobytes(),
        media_type="image/jpeg",
        width=array.shape[1],
        height=array.shape[0],
        scale=scale,
        tile_index=tile_index,
        tile_count=tile_count,
        origin_y=origin_y,
        cropped=cropped,
    )


def prepare_image(
    raw: bytes,
    long_edge: int = 1600,
    jpeg_quality: int = 90,
    *,
    crop_margins: bool = False,
) -> PreparedImage:
    array = _decode(raw)
    cropped = False
    if crop_margins:
        array, cropped = crop_to_content(array)
    return _encode(array, long_edge, jpeg_quality, cropped=cropped)


def tile_bounds(
    height: int, width: int, *, max_aspect: float = TILE_MAX_ASPECT, overlap: float = TILE_OVERLAP
) -> list[tuple[int, int]]:
    """세로로 긴 이미지를 (y0, y1) 구간 목록으로 나눈다. 비율이 상한 안이면 한 구간."""
    if width <= 0 or height <= 0 or height / width <= max_aspect:
        return [(0, height)]
    count = math.ceil(height / (width * max_aspect))
    step = height / count
    margin = int(step * overlap)
    bounds = []
    for index in range(count):
        y0 = max(0, int(index * step) - margin)
        y1 = min(height, int((index + 1) * step) + margin)
        bounds.append((y0, y1))
    return bounds


def prepare_evidence_images(
    raw: bytes,
    long_edge: int = 1600,
    jpeg_quality: int = 90,
    *,
    tile_tall: bool = False,
    max_aspect: float = TILE_MAX_ASPECT,
    overlap: float = TILE_OVERLAP,
    crop_margins: bool = False,
) -> list[PreparedImage]:
    """증빙 한 장 → 모델에 보낼 이미지 목록. 타일링을 끄면 한 장(`prepare_image` 와 같다).

    여백 절단은 타일을 나누기 **전에** 한 번 한다 — 종이 영역을 먼저 잡아야 타일 비율이 맞는다.
    """
    array = _decode(raw)
    cropped = False
    if crop_margins:
        array, cropped = crop_to_content(array)
    if not tile_tall:
        return [_encode(array, long_edge, jpeg_quality, cropped=cropped)]
    height, width = array.shape[:2]
    bounds = tile_bounds(height, width, max_aspect=max_aspect, overlap=overlap)
    return [
        _encode(
            array[y0:y1],
            long_edge,
            jpeg_quality,
            tile_index=index,
            tile_count=len(bounds),
            origin_y=y0,
            cropped=cropped,
        )
        for index, (y0, y1) in enumerate(bounds)
    ]
