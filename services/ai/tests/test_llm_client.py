"""Claude 호출 래퍼: 요청 모양 · 오류 → 계약 사유 · 서킷. 실제 API는 부르지 않는다."""

import asyncio
from types import SimpleNamespace

import anthropic
import httpx
import pytest

from seatsac_ai.llm.client import AnthropicClient, LlmUnavailable
from seatsac_ai.llm.prompt import system_blocks
from seatsac_ai.llm.tools import tool_definitions

REQ = httpx.Request("POST", "https://api.anthropic.com/v1/messages")


def status_error(cls, code):
    return cls("x", response=httpx.Response(code, request=REQ), body=None)


def fake_response():
    block = SimpleNamespace(
        type="tool_use", id="toolu_1", name="run_sql", input={"sql": "SELECT 1", "explanation": "x"}
    )
    block.model_dump = lambda exclude_none=True: {"type": "tool_use", "id": "toolu_1"}
    usage = SimpleNamespace(
        input_tokens=10,
        output_tokens=5,
        cache_read_input_tokens=3000,
        cache_creation_input_tokens=0,
    )
    return SimpleNamespace(content=[block], usage=usage)


class FakeMessages:
    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.kwargs = None

    async def create(self, **kwargs):
        self.kwargs = kwargs
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def client_with(*outcomes):
    client = AnthropicClient("sk-ant-test", "claude-sonnet-5-5")
    messages = FakeMessages(*outcomes)
    client._client = SimpleNamespace(messages=messages)
    return client, messages


def call(client):
    return asyncio.run(
        client.call(system_blocks(True), tool_definitions(), [{"role": "user", "content": "q"}], 7)
    )


def test_request_forces_a_tool_and_passes_timeout():
    client, messages = client_with(fake_response())
    result = call(client)
    assert messages.kwargs["model"] == "claude-sonnet-5-5"
    assert messages.kwargs["tool_choice"] == {"type": "any"}
    assert messages.kwargs["timeout"] == 7
    assert result.name == "run_sql" and result.tool_use_id == "toolu_1"
    assert result.usage.cache_read == 3000


@pytest.mark.parametrize(
    ("error", "reason"),
    [
        (anthropic.APITimeoutError(request=REQ), "TIMEOUT"),
        (status_error(anthropic.RateLimitError, 429), "RATE_LIMITED"),
        (status_error(anthropic.OverloadedError, 529), "OVERLOADED"),
        (status_error(anthropic.InternalServerError, 500), "OVERLOADED"),
    ],
)
def test_errors_map_to_contract_reasons(error, reason):
    client, _ = client_with(error)
    with pytest.raises(LlmUnavailable) as caught:
        call(client)
    assert caught.value.reason == reason


def test_circuit_opens_after_five_failures():
    client, messages = client_with(*[status_error(anthropic.InternalServerError, 500)] * 5)
    for _ in range(5):
        with pytest.raises(LlmUnavailable):
            call(client)
    with pytest.raises(LlmUnavailable) as caught:
        call(client)  # 여섯 번째는 부르지도 않는다
    assert caught.value.reason == "CIRCUIT_OPEN" and messages.outcomes == []


def test_missing_key_never_calls_out():
    with pytest.raises(LlmUnavailable) as caught:
        call(AnthropicClient(None, "claude-sonnet-5-5"))
    assert caught.value.reason == "CIRCUIT_OPEN"


def test_preset_tool_lists_only_real_preset_ids():
    run_preset = tool_definitions()[0]
    ids = run_preset["input_schema"]["properties"]["presetId"]["enum"]
    assert len(ids) == 12 and "today_after_time" in ids
