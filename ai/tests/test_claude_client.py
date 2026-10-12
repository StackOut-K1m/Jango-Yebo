"""ClaudeClient 의 설정 → SDK 인자 매핑. 네트워크 호출 없음."""

from pydantic import BaseModel, SecretStr

from app.common.claude import FALLBACK_BETA, ClaudeClient
from app.config import Settings


class _Out(BaseModel):
    ok: bool


def _settings(**overrides) -> Settings:
    return Settings(_env_file=None, app_env="local", **overrides)


def test_gateway_base_url_and_bearer_token_are_passed_to_the_sdk():
    client = ClaudeClient(
        _settings(
            anthropic_auth_token=SecretStr("gms-key"),
            anthropic_base_url="https://gms.example.io/gmsapi/api.anthropic.com/",
        )
    )
    kwargs = client.client_kwargs()
    assert kwargs["auth_token"] == "gms-key"
    assert kwargs["base_url"] == "https://gms.example.io/gmsapi/api.anthropic.com"  # 끝 슬래시 제거
    assert "api_key" not in kwargs
    sdk = client._client_or_raise()
    assert str(sdk.base_url).startswith("https://gms.example.io/gmsapi/api.anthropic.com")


def test_without_env_values_the_sdk_resolves_credentials_itself(monkeypatch):
    # 실행 환경(예: Claude Code 세션)이 ANTHROPIC_BASE_URL 을 넣어 둘 수 있다.
    # 테스트는 그와 무관해야 한다.
    for name in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL"):
        monkeypatch.delenv(name, raising=False)
    assert ClaudeClient(_settings()).client_kwargs() == {}


def test_server_fallback_toggle_controls_beta_arguments():
    on = ClaudeClient(_settings(anthropic_api_key=SecretStr("k"))).parse_kwargs(
        model="claude-opus-5",
        messages=[],
        output_format=_Out,
        max_tokens=10,
        effort="low",
        system=None,
    )
    assert on["betas"] == [FALLBACK_BETA] and on["fallbacks"] == "default"
    assert "system" not in on

    off = ClaudeClient(
        _settings(anthropic_api_key=SecretStr("k"), claude_server_fallback=False)
    ).parse_kwargs(
        model="claude-opus-5",
        messages=[],
        output_format=_Out,
        max_tokens=10,
        effort="low",
        system="s",
    )
    assert "betas" not in off and "fallbacks" not in off
    assert off["output_config"] == {"effort": "low"}
    # 기본값은 시스템 프롬프트 캐시 — cache_control 이 붙은 블록 목록으로 보낸다
    assert off["system"] == [{"type": "text", "text": "s", "cache_control": {"type": "ephemeral"}}]


def test_prompt_cache_can_be_disabled():
    plain = ClaudeClient(
        _settings(anthropic_api_key=SecretStr("k"), claude_prompt_cache=False)
    ).parse_kwargs(
        model="claude-opus-4-8",
        messages=[],
        output_format=_Out,
        max_tokens=10,
        effort="high",
        system="s",
    )
    assert plain["system"] == "s"


def _api_error(cls, status: int, message: str):
    import httpx

    request = httpx.Request("POST", "https://gms.example.io/v1/messages")
    response = httpx.Response(status, request=request)
    return cls(message, response=response, body={"message": message})


def test_translate_error_separates_quota_exhaustion_from_bad_credentials():
    import anthropic

    from app.common.claude import FATAL_STAGES, translate_error

    quota = translate_error(
        _api_error(
            anthropic.AuthenticationError,
            401,
            "Error code: 401 - {'message': '[GMS 에러] This GMS key has no token left'}",
        ),
        "claude-opus-4-8",
    )
    assert quota.stage == "quota" and quota.retryable is False
    bad_key = translate_error(
        _api_error(anthropic.AuthenticationError, 401, "invalid x-api-key"), "claude-opus-4-8"
    )
    assert bad_key.stage == "config"
    rate = translate_error(_api_error(anthropic.RateLimitError, 429, "slow down"), "m")
    assert rate.stage == "model" and rate.retryable is True
    server = translate_error(_api_error(anthropic.InternalServerError, 503, "down"), "m")
    assert server.stage == "model" and server.retryable is True
    assert {"config", "quota"} == set(FATAL_STAGES)


def test_unsupported_parameters_are_dropped_after_the_first_400(monkeypatch):
    """Haiku 4.5 는 adaptive thinking·effort 를 받지 않는다 — 400 본문에서 배우고 빼야 한다."""
    import anthropic

    client = ClaudeClient(_settings(anthropic_api_key=SecretStr("k")))
    model = "claude-haiku-4-5-20251001"

    def _status_error(message: str) -> anthropic.APIStatusError:
        import httpx

        request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
        return anthropic.APIStatusError(
            f"Error code: 400 - {{'error': {{'message': '{message}'}}}}",
            response=httpx.Response(400, request=request),
            body=None,
        )

    # 1) thinking 을 먼저 배운다
    assert client._learn_unsupported(
        model, _status_error("adaptive thinking is not supported")
    ) == ("thinking")
    kwargs = client.parse_kwargs(
        model=model, messages=[], output_format=_Out, max_tokens=10, effort="medium", system=None
    )
    assert "thinking" not in kwargs and kwargs["output_config"] == {"effort": "medium"}

    # 2) 이어서 effort 도 배운다
    assert (
        client._learn_unsupported(
            model, _status_error("This model does not support the effort parameter.")
        )
        == "effort"
    )
    kwargs = client.parse_kwargs(
        model=model, messages=[], output_format=_Out, max_tokens=10, effort="medium", system=None
    )
    assert "thinking" not in kwargs and "output_config" not in kwargs

    # 3) **이미 아는 것이어도 이름을 돌려준다.**
    #
    # 전에는 여기서 None 을 돌려줬는데, 그러면 동시에 나간 호출들이 무더기로 죽는다 —
    # 셋이 함께 출발해 셋 다 400 을 받으면 먼저 도착한 하나만 재시도하고 나머지는
    # 「배울 게 없다」로 그대로 실패한다. 같은 파라미터를 빼고 다시 부르면 될 상황인데도.
    #
    # 2026-09-16 실호출에서 3건 중 2건이 이렇게 죽었다. 배포 직후 첫 묶음이 통째로
    # 실패하는 모양이라 --max-structure 200 이면 최대 199건이 FAILED 로 남는다.
    #
    # 무한 재시도는 ②가 막는다 — 기억한 뒤에는 parse_kwargs 가 그 파라미터를 빼고
    # 만들어 같은 400 이 다시 날 수 없다.
    assert (
        client._learn_unsupported(model, _status_error("adaptive thinking is not supported"))
        == "thinking"
    )

    # 3-1) 아는 마커가 하나도 없으면 그때는 None 이다 (재시도하지 않고 올린다)
    assert client._learn_unsupported(model, _status_error("some other bad request")) is None
    # 4) 다른 모델은 영향 없다
    other = client.parse_kwargs(
        model="claude-opus-5",
        messages=[],
        output_format=_Out,
        max_tokens=10,
        effort="high",
        system=None,
    )
    assert other["thinking"] == {"type": "adaptive"} and other["output_config"] == {
        "effort": "high"
    }


def test_status_errors_carry_the_api_message():
    import anthropic
    import httpx

    from app.common.claude import translate_error

    request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    exc = anthropic.APIStatusError(
        "Error code: 400 - adaptive thinking is not supported on this model",
        response=httpx.Response(400, request=request),
        body=None,
    )
    error = translate_error(exc, "claude-haiku-4-5-20251001")
    assert error.stage == "model" and "adaptive thinking" in str(error)
