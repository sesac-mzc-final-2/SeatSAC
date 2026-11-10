"""Claude API 호출 (ADR-83 · claude-sonnet-5-5).

- 한 번 부를 때마다 도구 하나를 반드시 고르게 한다(tool_choice=any)
- 시간 상한은 부르는 쪽이 정한다: min(15초, 요청 기한까지 남은 시간)
- 실패는 계약의 503 AI_LLM_UNAVAILABLE 사유로 바꾼다
  (TIMEOUT·OVERLOADED·RATE_LIMITED·SPEND_LIMIT·CIRCUIT_OPEN)
- 서킷: 연속 5번 실패하면 30초 동안 부르지 않고 바로 CIRCUIT_OPEN
  (장애 때 사용자마다 25초씩 기다리게 하지 않는다)
- SDK 자체 재시도는 끈다(max_retries=0). 재시도는 기한 안에서 오케스트레이터가 정한다

테스트·CI 는 같은 모양의 가짜(`tests/llm_fakes.py`)를 쓴다 — 키 없이 돈다.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Protocol

import anthropic

from seatsac_ai.common.metrics import AI_LLM_CALL_SECONDS

log = logging.getLogger(__name__)

_FAILURES_TO_OPEN = 5
_OPEN_SECONDS = 30.0


class LlmUnavailable(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason  # 계약 AI_LLM_UNAVAILABLE details[].reason


@dataclass
class Usage:
    input: int = 0
    output: int = 0
    cache_read: int = 0
    cache_write: int = 0

    def add(self, other: Usage) -> None:
        self.input += other.input
        self.output += other.output
        self.cache_read += other.cache_read
        self.cache_write += other.cache_write


@dataclass
class ToolCall:
    tool_use_id: str
    name: str
    input: dict[str, Any]
    content: list[dict[str, Any]] = field(default_factory=list)  # 다음 호출에 assistant로 되돌려 줌
    usage: Usage = field(default_factory=Usage)


class LlmClient(Protocol):
    async def call(
        self,
        system: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        messages: list[dict[str, Any]],
        timeout: float,
    ) -> ToolCall: ...


class AnthropicClient:
    def __init__(self, api_key: str | None, model: str, max_tokens: int = 1024) -> None:
        self._client = anthropic.AsyncAnthropic(api_key=api_key, max_retries=0) if api_key else None
        if self._client is None:
            # 기동 때 한 번만 error. 요청마다는 warning(로그가 시끄럽지 않게)
            log.error("ANTHROPIC_API_KEY가 없다 — Claude가 필요한 질문은 503 CIRCUIT_OPEN")
        self._model = model
        self._max_tokens = max_tokens
        self._failures = 0
        self._open_until = 0.0

    async def call(self, system, tools, messages, timeout) -> ToolCall:
        if self._client is None:
            log.warning("ANTHROPIC_API_KEY가 없어 Claude를 부를 수 없다")
            raise LlmUnavailable("CIRCUIT_OPEN")
        if time.monotonic() < self._open_until:
            raise LlmUnavailable("CIRCUIT_OPEN")
        started = time.perf_counter()
        result = "ok"
        try:
            response = await self._client.messages.create(
                model=self._model,
                max_tokens=self._max_tokens,
                system=system,
                tools=tools,
                tool_choice={"type": "any"},
                messages=messages,
                timeout=timeout,
            )
        except anthropic.APIError as exc:
            reason, result = _classify(exc)
            self._record_failure()
            log.warning("Claude 호출 실패: %s (%s)", reason, type(exc).__name__)
            raise LlmUnavailable(reason) from exc
        finally:
            AI_LLM_CALL_SECONDS.labels(result).observe(time.perf_counter() - started)
        self._failures = 0
        return _to_tool_call(response)

    def _record_failure(self) -> None:
        self._failures += 1
        if self._failures >= _FAILURES_TO_OPEN:
            self._open_until = time.monotonic() + _OPEN_SECONDS
            self._failures = 0


def _classify(exc: anthropic.APIError) -> tuple[str, str]:
    """(계약 reason, 지표 result)"""
    if isinstance(exc, anthropic.APITimeoutError):
        return "TIMEOUT", "timeout"
    if isinstance(exc, anthropic.EnforcedSpendLimitReachedError):
        return "SPEND_LIMIT", "error"
    if isinstance(exc, anthropic.RateLimitError):
        return "RATE_LIMITED", "rate_limited"
    if isinstance(exc, anthropic.OverloadedError):
        return "OVERLOADED", "overloaded"
    return "OVERLOADED", "error"  # 그 밖의 공급자 오류(5xx·연결·인증)


def _to_tool_call(response: Any) -> ToolCall:
    u = response.usage
    usage = Usage(
        input=u.input_tokens or 0,
        output=u.output_tokens or 0,
        cache_read=u.cache_read_input_tokens or 0,
        cache_write=u.cache_creation_input_tokens or 0,
    )
    content = [block.model_dump(exclude_none=True) for block in response.content]
    tool = next((b for b in response.content if b.type == "tool_use"), None)
    if tool is None:  # tool_choice=any 라 거의 없지만, 오면 범위 밖으로 다룬다
        return ToolCall("", "out_of_scope", {"reason": "no tool"}, content, usage)
    return ToolCall(tool.id, tool.name, dict(tool.input), content, usage)
