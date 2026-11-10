"""공개 API(CloudFront `PUBLIC_API_BASE_URL`) 호출 담당. 스냅샷 적재 전용.

RDS·내부 API가 아니라 공개 API만 부른다(ADR-84) — 사이트에서 누구나 보는 값이고 인증이 필요 없다
(catalog 계약 `security: []`). 응답 모양은 catalog 계약
(contracts/openapi/catalog.openapi.yaml)과 서비스 DTO를 따른다.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import date
from typing import Any

import httpx

log = logging.getLogger(__name__)

# 시간 제한: 연결 3초 · 응답 5초. 느린 API 하나가 적재 전체를 붙잡지 않게 한다.
_TIMEOUT = httpx.Timeout(5.0, connect=3.0)
_ATTEMPTS = 2  # 연결 오류·5xx는 1회만 더 시도한다
_RETRY_DELAY_SECONDS = 0.5
# /movies 최대 size. 기본(20)보다 영화가 많을 수 있어 올리고, 모든 쪽을 읽는다
_PAGE_SIZE = 50
_MAX_PAGES = 20  # 무한 반복 방지


class SnapshotFetchError(RuntimeError):
    """공개 API 호출이 끝내 실패했다. 적재기는 이번 갱신을 버리고 이전 스냅샷을 유지한다."""


def _field(body: Any, key: str, kind: type, path: str) -> Any:
    """계약상 필수인 칸을 꺼낸다. 없거나 모양이 다르면 실패(빈 목록 성공으로 보지 않는다)."""
    value = body.get(key) if isinstance(body, dict) else None
    if not isinstance(value, kind) or isinstance(value, bool):
        raise SnapshotFetchError(f"GET {path}: '{key}' 없음 또는 형식 오류(계약 위반)")
    return value


class PublicApiClient:
    def __init__(self, base_url: str, http: httpx.AsyncClient | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._http = http or httpx.AsyncClient(
            timeout=_TIMEOUT, headers={"User-Agent": "seatsac-ai"}
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get_json(self, path: str, params: dict[str, Any] | None = None) -> Any:
        url = f"{self._base}{path}"
        last: Exception | None = None
        for attempt in range(1, _ATTEMPTS + 1):
            try:
                response = await self._http.get(url, params=params)
            except httpx.TransportError as exc:  # 연결·시간 초과 등
                last = exc
            else:
                if response.status_code < 400:
                    try:
                        return response.json()
                    except ValueError as exc:
                        raise SnapshotFetchError(f"GET {path}: JSON이 아님") from exc
                if response.status_code < 500:
                    # 4xx는 다시 해도 같다(없는 ID·잘못된 요청). 바로 실패.
                    raise SnapshotFetchError(f"GET {path}: HTTP {response.status_code}")
                last = SnapshotFetchError(f"GET {path}: HTTP {response.status_code}")
            if attempt < _ATTEMPTS:
                await asyncio.sleep(_RETRY_DELAY_SECONDS)
        raise SnapshotFetchError(f"GET {path} 실패: {last!r}") from last

    async def list_movies(self) -> list[dict[str, Any]]:
        """`GET /movies?status=NOW_SHOWING`의 모든 쪽(요약 목록)."""
        items: list[dict[str, Any]] = []
        for page in range(_MAX_PAGES):
            body = await self._get_json(
                "/movies", {"status": "NOW_SHOWING", "page": page, "size": _PAGE_SIZE}
            )
            items.extend(_field(body, "content", list, "/movies"))
            if page + 1 >= _field(body, "totalPages", int, "/movies"):
                return items
        log.warning("movies: %d쪽에서 멈춤(상한). 이후 영화는 스냅샷에 없다", _MAX_PAGES)
        return items

    async def get_movie(self, movie_id: int) -> dict[str, Any]:
        body = await self._get_json(f"/movies/{movie_id}")
        if not isinstance(body, dict):
            raise SnapshotFetchError(f"GET /movies/{movie_id}: 객체가 아님")
        return body

    async def list_theaters(self) -> list[dict[str, Any]]:
        body = await self._get_json("/theaters", {"hasLayout": "true"})
        return list(_field(body, "items", list, "/theaters"))

    async def list_screenings(self, day: date, theater_id: int) -> list[dict[str, Any]]:
        body = await self._get_json(
            "/screenings", {"date": day.isoformat(), "theaterId": theater_id}
        )
        return list(_field(body, "items", list, "/screenings"))
