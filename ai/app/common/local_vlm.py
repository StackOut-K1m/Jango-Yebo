"""로컬 VLM 클라이언트 — Ollama 의 네이티브 `/api/chat` 로 구조화 출력을 받는다.

토큰 비용 없이(사용자 PC 의 GPU) 파이프라인·프롬프트·스키마를 검증하기 위한 개발용 경로다.
정확도는 Claude 보다 낮으므로 최종 수치에는 안 쓴다. run.json 에 모델명이 남아 섞이지 않는다.

Ollama 규약
- POST {base_url}/api/chat, `stream=false`. 이미지는 user 메시지의 `images` 에 base64 문자열 배열로.
- `format` 에 JSON 스키마를 주면 그 스키마로 생성을 제약한다(Ollama ≥ 0.5). Pydantic 모델의
  `model_json_schema()` 를 그대로 넘긴다.
- 응답 `message.content` 는 JSON 문자열. `prompt_eval_count`/`eval_count` 가 토큰 수다.
"""

from __future__ import annotations

import base64
import logging
from types import SimpleNamespace
from typing import Any

import httpx
from pydantic import BaseModel, ValidationError

from app.common.claude import UsageTotals
from app.config import Settings
from app.ocr.preprocess import PreparedImage

log = logging.getLogger(__name__)


class LocalVlmError(Exception):
    """로컬 VLM 호출 실패. stage=config 면 서버·모델 문제(다음도 실패), model 이면 응답 문제."""

    def __init__(self, stage: str, message: str) -> None:
        super().__init__(message)
        self.stage = stage


class LocalVlmClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self.usage = UsageTotals()
        self.usage_by_model: dict[str, UsageTotals] = {}  # ClaudeClient 와 같은 인터페이스

    async def parse(
        self,
        *,
        model: str,
        system: str,
        user_text: str,
        images: list[PreparedImage],
        output_format: type[BaseModel],
        max_tokens: int,
    ) -> Any:
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": system},
                {
                    "role": "user",
                    "content": user_text,
                    "images": [
                        base64.standard_b64encode(image.data).decode("ascii") for image in images
                    ],
                },
            ],
            "format": output_format.model_json_schema(),
            "stream": False,
            # 온도 0 으로 최대한 결정적으로. num_predict 는 출력 토큰 상한.
            "options": {"temperature": 0, "num_predict": max_tokens},
        }
        url = self._settings.local_vlm_base_url.rstrip("/") + "/api/chat"
        try:
            async with httpx.AsyncClient(
                timeout=self._settings.local_vlm_timeout_seconds
            ) as client:
                response = await client.post(url, json=payload)
        except httpx.RequestError as exc:
            raise LocalVlmError(
                "config",
                f"로컬 VLM 서버에 연결할 수 없습니다 ({url}). `ollama serve` 실행·모델 pull 확인.",
            ) from exc

        if response.status_code == 404:
            raise LocalVlmError(
                "config", f"로컬 VLM 모델을 찾을 수 없습니다: {model}. `ollama pull {model}` 필요."
            )
        if response.status_code != 200:
            raise LocalVlmError(
                "model", f"로컬 VLM 호출 실패 (HTTP {response.status_code}): {response.text[:200]}"
            )

        data = response.json()
        content = (data.get("message") or {}).get("content")
        if not content:
            raise LocalVlmError("model", "로컬 VLM 응답에 content 가 없습니다.")
        try:
            parsed = output_format.model_validate_json(content)
        except ValidationError as exc:
            # 로컬 7B 는 format 제약을 100% 지키지 못해 가끔 필드가 어긋난다 — 어느 필드인지 남긴다.
            locs = "; ".join(
                ".".join(str(p) for p in err["loc"]) + f"({err['type']})"
                for err in exc.errors()[:5]
            )
            raise LocalVlmError(
                "model", f"구조화 출력을 스키마로 해석할 수 없습니다: {locs}"
            ) from exc

        counted = SimpleNamespace(
            input_tokens=data.get("prompt_eval_count", 0) or 0,
            output_tokens=data.get("eval_count", 0) or 0,
            cache_read_input_tokens=0,
            cache_creation_input_tokens=0,
        )
        self.usage.add(counted)
        self.usage_by_model.setdefault(data.get("model", model), UsageTotals()).add(counted)
        log.info(
            "local vlm model=%s in=%s out=%s",
            data.get("model", model),
            data.get("prompt_eval_count"),
            data.get("eval_count"),
        )
        return SimpleNamespace(parsed_output=parsed, model=data.get("model", model))
