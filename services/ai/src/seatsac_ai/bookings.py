"""'내 예매' 질문 (계약 15-2 ② · WBS 3.4.10).

"내 예매 보여줘" → 공개 API `GET /users/me/bookings` 를 **요청의 Authorization 그대로** 부른다
(gateway가 Authorization 을 지우지 않고 넘긴다 — AiRouteConfig).
답은 정해진 문장(템플릿)으로 만든다.

  - Claude에게 보내지 않는다: 개인 예매 정보가 외부 LLM으로 나가지 않는다 · 토큰 0
  - 캐시하지 않는다: 사람마다 답이 다르다
  - 실패하면 503 SERVICE_UNAVAILABLE(details upstream=user), 토큰 문제면 401
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import httpx

from seatsac_ai.common.errors import unauthorized, upstream_unavailable
from seatsac_ai.snapshot.mapper import KST

_TIMEOUT = httpx.Timeout(3.0, connect=1.0)  # 내부 REST 3초와 같은 기준
_PAGE_SIZE = 20

# '내/나의/제/나' 앞에 한글이 붙으면 다른 낱말이다("이제 예매할 수 있는" — #96 리뷰).
# 주어 없는 "예매한 영화"·"예매 확인"은 일반 질문("사람들이 많이 예매한 영화")과 겹쳐 뺀다.
_PERSONAL = re.compile(
    r"(?<![가-힣])(?:내|나의|제)\s*(?:예매|예약|티켓)|(?<![가-힣])나\s*예매|내가\s*(?:예매|예약)"
    r"|예매\s*(?:내역|목록)|예약\s*내역"
)

COLUMNS = [
    {"name": "booking_id", "type": "integer"},
    {"name": "status", "type": "text"},
    {"name": "movie_title", "type": "text"},
    {"name": "theater_name", "type": "text"},
    {"name": "auditorium_name", "type": "text"},
    {"name": "starts_at", "type": "text"},
    {"name": "seat_labels", "type": "text"},
    {"name": "amount", "type": "integer"},
]


@dataclass(frozen=True)
class BookingPage:
    items: list[dict[str, Any]]
    total: int  # totalElements — items 보다 많으면 잘린 것(truncated)

    @property
    def truncated(self) -> bool:
        return self.total > len(self.items)


def is_personal(question: str) -> bool:
    return bool(_PERSONAL.search(question))


class MyBookingsClient:
    def __init__(self, base_url: str, http: httpx.AsyncClient | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._http = http or httpx.AsyncClient(
            timeout=_TIMEOUT, headers={"User-Agent": "seatsac-ai"}
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def fetch(self, authorization: str | None) -> BookingPage:
        if not authorization:
            raise unauthorized("TOKEN_REQUIRED")
        try:
            response = await self._http.get(
                f"{self._base}/users/me/bookings",
                params={"size": _PAGE_SIZE},
                headers={"Authorization": authorization},
            )
        except httpx.HTTPError as exc:
            raise upstream_unavailable("user") from exc
        if response.status_code == 401:
            raise unauthorized("INVALID_TOKEN")
        if response.status_code != 200:
            raise upstream_unavailable("user")
        try:
            page = response.json()
            content = page.get("content")
            total = page.get("totalElements")
        except (ValueError, AttributeError) as exc:
            raise upstream_unavailable("user") from exc
        if not isinstance(content, list):
            raise upstream_unavailable("user")
        # 첫 페이지(createdAt 최신순 20건)만 읽는다. 더 있으면 truncated 로 알린다(#96 리뷰)
        return BookingPage(content, total if isinstance(total, int) else len(content))


def to_rows(bookings: list[dict[str, Any]]) -> list[list[Any]]:
    rows = []
    for b in bookings:
        s = b.get("screening") or {}
        rows.append(
            [
                b.get("bookingId"),
                b.get("status"),
                s.get("movieTitle"),
                s.get("theaterName"),
                s.get("auditoriumName"),
                _kst_minute(s.get("startsAt")),
                ", ".join(b.get("seatLabels") or []),
                b.get("amount"),
            ]
        )
    return rows


def answer_text(rows: list[list[Any]], now: datetime) -> str:
    """다가오는 확정 예매 수 + 가장 가까운 1건. 사람이 읽는 문장만 — 결과 표는 rows 그대로."""
    now_text = f"{now.astimezone(KST):%Y-%m-%d %H:%M}"
    upcoming = sorted(
        (r for r in rows if r[1] == "CONFIRMED" and r[5] and r[5] > now_text),
        key=lambda r: r[5],
    )
    if not upcoming:
        if rows:
            return (
                "다가오는 확정 예매가 없습니다. "
                f"지난·취소 예매를 포함한 {len(rows)}건은 표에 있습니다."
            )
        return "예매 내역이 없습니다."
    first = upcoming[0]
    when = datetime.strptime(first[5], "%Y-%m-%d %H:%M")
    place = " ".join(x for x in (first[3], first[4]) if x)
    seats = f" {first[6]}" if first[6] else ""
    return (
        f"다가오는 확정 예매가 {len(upcoming)}건 있습니다. 가장 가까운 예매는 "
        f"{when.month}월 {when.day}일 {when:%H:%M} {place}{seats}입니다."
    )


def _kst_minute(value: Any) -> str | None:
    if not value:
        return None
    try:
        return f"{datetime.fromisoformat(str(value)).astimezone(KST):%Y-%m-%d %H:%M}"
    except ValueError:
        return None
