"""fileObjectKey → 파일.

워커는 DB 에 닿지 않는다(§8.7 규칙 1). API 서버가 쓰는 업로드 볼륨(`STORAGE_ROOT`)을
read-only 로 마운트해 같은 키를 읽는다. 키는 저장소 밖을 가리킬 수 없다.
"""

from pathlib import Path

from app.common.errors import AppError, ErrorCode


def resolve_object_key(root: Path, key: str) -> Path:
    normalized = key.replace("\\", "/")
    parts = Path(normalized).parts
    if (
        not normalized
        or normalized.startswith("/")
        or ".." in parts
        or any(":" in part for part in parts)
    ):
        raise AppError(ErrorCode.VALIDATION_FAILED, "fileObjectKey 형식이 올바르지 않습니다.")
    root_resolved = root.resolve()
    path = (root_resolved / normalized).resolve()
    if path != root_resolved and root_resolved not in path.parents:
        raise AppError(ErrorCode.VALIDATION_FAILED, "fileObjectKey 가 저장소 밖을 가리킵니다.")
    return path


def read_object(root: Path, key: str) -> bytes:
    path = resolve_object_key(root, key)
    if not path.is_file():
        raise AppError(
            ErrorCode.VALIDATION_FAILED,
            "파일을 찾을 수 없습니다.",
            detail={"fileObjectKey": key},
        )
    return path.read_bytes()
