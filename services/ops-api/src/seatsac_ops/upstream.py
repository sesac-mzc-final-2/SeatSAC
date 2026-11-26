"""ai·coupon 내부 API 프록시 (OPS-23·OPS-24, ops.openapi.yaml).

연결 1초 · 응답 3초, 재시도 없음(docs/contracts.md 내부 호출 표). 2xx·400은 하위 서비스 응답을
**그대로** 돌려주고
(검증은 하위 서비스가 한다), 그 밖(연결 실패·시간 초과·5xx·예상 못 한 코드)은 503
SERVICE_UNAVAILABLE upstream {ai|coupon}.
X-Trace-Id는 그대로 넘긴다. coupon에는 감사용으로 X-User-Id(토큰 sub)도
넘긴다(coupon.internal.openapi.yaml).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

import httpx

from seatsac_ops.common.errors import upstream_unavailable
from seatsac_ops.common.trace import TRACE_ID_HEADER, USER_ID_HEADER, current_trace_id
from seatsac_ops.config import Settings

log = logging.getLogger(__name__)

PASS_THROUGH = {200, 201, 400}


@dataclass
class UpstreamResponse:
    status: int
    body: Any


class UpstreamClient:
    def __init__(
        self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        timeout = httpx.Timeout(
            settings.services_read_timeout, connect=settings.services_connect_timeout
        )
        self._ai = httpx.AsyncClient(
            base_url=settings.services_ai_url, timeout=timeout, transport=transport
        )
        self._coupon = httpx.AsyncClient(
            base_url=settings.services_coupon_url, timeout=timeout, transport=transport
        )

    async def aclose(self) -> None:
        await self._ai.aclose()
        await self._coupon.aclose()

    async def unmet_demand(self, params: dict[str, str]) -> UpstreamResponse:
        return await self._send(
            self._ai, "ai", "GET", "/internal/ai/unmet-demand/stats", params=params
        )

    async def create_coupon_event(self, body: Any, user_id: str) -> UpstreamResponse:
        return await self._send(
            self._coupon, "coupon", "POST", "/internal/coupon-events", json=body, user_id=user_id
        )

    async def list_coupon_events(self, params: dict[str, str]) -> UpstreamResponse:
        return await self._send(
            self._coupon, "coupon", "GET", "/internal/coupon-events", params=params
        )

    async def _send(
        self,
        client: httpx.AsyncClient,
        name: str,
        method: str,
        path: str,
        user_id: str | None = None,
        **kwargs: Any,
    ) -> UpstreamResponse:
        headers = {TRACE_ID_HEADER: current_trace_id()}
        if user_id:
            headers[USER_ID_HEADER] = user_id
        try:
            response = await client.request(method, path, headers=headers, **kwargs)
        except httpx.TimeoutException as exc:
            raise upstream_unavailable(name, "TIMEOUT") from exc
        except httpx.HTTPError as exc:
            raise upstream_unavailable(name, "CONNECT_ERROR") from exc
        if response.status_code not in PASS_THROUGH:
            log.warning("%s 내부 API %s %s → %s", name, method, path, response.status_code)
            raise upstream_unavailable(name, f"HTTP_{response.status_code}")
        try:
            body = response.json()
        except ValueError as exc:
            raise upstream_unavailable(name, "INVALID_RESPONSE") from exc
        return UpstreamResponse(response.status_code, body)
