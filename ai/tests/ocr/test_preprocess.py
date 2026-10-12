import io

import pytest
from PIL import Image

from app.common.errors import AppError
from app.ocr.preprocess import prepare_image


def _png(width: int, height: int) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (width, height), "white").save(buffer, format="PNG")
    return buffer.getvalue()


def test_long_edge_is_capped_and_reencoded_as_jpeg():
    prepared = prepare_image(_png(4000, 3000), long_edge=1600)
    assert (prepared.width, prepared.height) == (1600, 1200)
    assert prepared.media_type == "image/jpeg"
    assert prepared.scale == pytest.approx(0.4)
    assert prepared.data[:2] == b"\xff\xd8"  # JPEG SOI


def test_small_image_is_not_upscaled():
    prepared = prepare_image(_png(800, 600), long_edge=1600)
    assert (prepared.width, prepared.height) == (800, 600)
    assert prepared.scale == 1.0


def test_exif_orientation_is_applied():
    buffer = io.BytesIO()
    image = Image.new("RGB", (400, 200), "white")
    exif = image.getexif()
    exif[0x0112] = 6  # 90도 회전 — 휴대폰 세로 촬영
    image.save(buffer, format="JPEG", exif=exif.tobytes())

    prepared = prepare_image(buffer.getvalue())
    assert (prepared.width, prepared.height) == (200, 400)


def test_garbage_bytes_are_rejected_as_validation_error():
    with pytest.raises(AppError) as excinfo:
        prepare_image(b"not an image")
    assert excinfo.value.status == 400


def test_tall_receipt_is_tiled_with_overlap_only_when_enabled():
    from app.ocr.preprocess import prepare_evidence_images, tile_bounds

    raw = _png(1000, 3000)  # KORIE 스캔과 같은 비율 — 장변 1600 이면 폭이 533 으로 줄어든다
    single = prepare_evidence_images(raw, long_edge=1600)
    assert len(single) == 1 and single[0].scale == pytest.approx(1600 / 3000)

    tiles = prepare_evidence_images(raw, long_edge=1600, tile_tall=True, max_aspect=1.5)
    assert [t.tile_index for t in tiles] == [0, 1] and all(t.tile_count == 2 for t in tiles)
    assert tiles[0].origin_y == 0 and tiles[1].origin_y < 1500  # 겹침만큼 위에서 시작
    assert all(t.scale == pytest.approx(1.0) for t in tiles)  # 1000x~1590 → 장변 1600 안
    assert all(t.width == 1000 for t in tiles)

    bounds = tile_bounds(3000, 1000, max_aspect=1.5, overlap=0.06)
    assert bounds[0][0] == 0 and bounds[-1][1] == 3000
    assert bounds[0][1] > bounds[1][0]  # 인접 타일이 겹친다
    assert tile_bounds(1200, 1000, max_aspect=1.5) == [(0, 1200)]  # 비율 안이면 한 장


def _paper_on_dark(width: int, height: int, paper: tuple[int, int, int, int]) -> bytes:
    """어두운 배경 위에 밝은 종이가 놓인 촬영 이미지를 흉내낸다. paper = (x0, y0, x1, y1)."""
    import numpy as np

    canvas = np.full((height, width, 3), 40, dtype=np.uint8)
    x0, y0, x1, y1 = paper
    canvas[y0:y1, x0:x1] = 245
    buffer = io.BytesIO()
    Image.fromarray(canvas).save(buffer, format="PNG")
    return buffer.getvalue()


def test_crop_removes_background_around_the_paper():
    from app.ocr.preprocess import CROP_PADDING, crop_to_content

    raw = _paper_on_dark(600, 400, (100, 50, 500, 350))
    prepared = prepare_image(raw, long_edge=1600, crop_margins=True)
    assert prepared.cropped is True
    # 종이 400x300 + 여백 8px 양쪽
    assert prepared.width == 400 + 2 * CROP_PADDING
    assert prepared.height == 300 + 2 * CROP_PADDING

    # 끄면 원본 그대로
    assert prepare_image(raw, long_edge=1600).width == 600
    assert prepare_image(raw, long_edge=1600).cropped is False

    import cv2
    import numpy as np

    array = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    _, applied = crop_to_content(array)
    assert applied is True


def test_crop_is_a_no_op_on_white_background_scans():
    # KORIE 원본처럼 종이가 화면을 채우면 잘릴 게 없다 — 원본을 그대로 쓴다
    prepared = prepare_image(_png(900, 1200), long_edge=1600, crop_margins=True)
    assert prepared.cropped is False
    assert (prepared.width, prepared.height) == (900, 1200)


def test_crop_gives_up_when_detection_looks_wrong():
    # 밝은 영역이 화면의 15% 미만이면 오검지로 보고 자르지 않는다 (합계 줄을 날리는 것이 더 큰 손해)
    import cv2
    import numpy as np

    from app.ocr.preprocess import crop_to_content

    raw = _paper_on_dark(600, 400, (10, 10, 50, 50))  # 40x40 = 0.7%
    array = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
    cropped, applied = crop_to_content(array)
    assert applied is False and cropped.shape[:2] == (400, 600)


def test_crop_runs_before_tiling_so_tile_ratio_follows_the_paper():
    from app.ocr.preprocess import prepare_evidence_images, tile_bounds

    raw = _paper_on_dark(800, 1800, (300, 100, 500, 1700))  # 200x1600 세로 종이
    tiles = prepare_evidence_images(raw, long_edge=1600, tile_tall=True, crop_margins=True)
    assert all(t.cropped for t in tiles)
    # 종이(216x1616)를 기준으로 타일이 나뉜다. 배경까지 포함했다면 800 폭 기준이라 타일 수가 달랐다
    assert len(tiles) == len(tile_bounds(1616, 216, max_aspect=1.5))
    assert all(t.width == 216 for t in tiles)
