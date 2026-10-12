import pytest

from app.common.errors import AppError
from app.ocr.storage import read_object, resolve_object_key


def test_reads_key_under_root(tmp_path):
    target = tmp_path / "evidences" / "11" / "501.jpg"
    target.parent.mkdir(parents=True)
    target.write_bytes(b"jpeg-bytes")
    assert read_object(tmp_path, "evidences/11/501.jpg") == b"jpeg-bytes"


@pytest.mark.parametrize(
    "key",
    ["../secret.txt", "evidences/../../x", "/etc/passwd", "C:/Windows/win.ini", "", "..\\x"],
)
def test_rejects_keys_outside_root(tmp_path, key):
    with pytest.raises(AppError) as excinfo:
        resolve_object_key(tmp_path, key)
    assert excinfo.value.status == 400


def test_missing_file_is_validation_error(tmp_path):
    with pytest.raises(AppError) as excinfo:
        read_object(tmp_path, "evidences/11/none.jpg")
    assert excinfo.value.detail == {"fileObjectKey": "evidences/11/none.jpg"}
