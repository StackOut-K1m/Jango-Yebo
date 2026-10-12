"""결과에 반드시 남기는 모델 식별 정보 (기술스택 §8.7 규칙 11, 명세 13.2 `modelName`).

"언제부터 정확도가 떨어졌나"에 답하려면 모든 결과에 모델·프롬프트 버전이 있어야 한다.
"""

from typing import Literal

from app.common.envelope import CamelModel

# 요청의 preferredPipeline(AUTO/OCR_PIPELINE/VLM)과 달리 실제로 사용된 경로를 적는다.
# STUB 은 고정 응답이다 — 운영 데이터에 섞이면 안 되므로 이름을 숨기지 않는다.
Pipeline = Literal["OCR_PIPELINE", "VLM", "RULE", "LLM", "STUB"]


class ModelMeta(CamelModel):
    model_name: str
    model_version: str | None = None
    prompt_version: str | None = None
    pipeline: Pipeline
