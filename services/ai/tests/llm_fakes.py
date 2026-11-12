"""가짜 Claude: 정해 둔 도구 호출을 차례로 돌려준다. 키·네트워크 없이 CI에서 돈다."""

from __future__ import annotations

from typing import Any

from seatsac_ai.llm.client import LlmUnavailable, ToolCall, Usage


def tool(name: str, **input: Any) -> ToolCall:
    use_id = f"toolu_{name}"
    return ToolCall(
        tool_use_id=use_id,
        name=name,
        input=input,
        content=[{"type": "tool_use", "id": use_id, "name": name, "input": input}],
        usage=Usage(input=400, output=60, cache_read=3800),
    )


class ScriptedLlm:
    def __init__(self, *replies: ToolCall | Exception) -> None:
        self._replies = list(replies)
        self.requests: list[dict[str, Any]] = []

    async def call(self, system, tools, messages, timeout) -> ToolCall:
        self.requests.append(
            {"system": system, "tools": tools, "messages": list(messages), "timeout": timeout}
        )
        if not self._replies:
            raise LlmUnavailable("OVERLOADED")
        reply = self._replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply
