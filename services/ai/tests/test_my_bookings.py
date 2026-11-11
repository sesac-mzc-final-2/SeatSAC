"""'내 예매' (3.4.10).

공개 API를 사용자 토큰 그대로 부르고, Claude·캐시 없이 템플릿 문장으로 답한다.
"""

import asyncio
from datetime import datetime

import fakeredis
import httpx
import pytest

from seatsac_ai.bookings import MyBookingsClient, is_personal
from seatsac_ai.cache.store import ChatCache, MemoryIndex
from seatsac_ai.common.errors import ApiError
from seatsac_ai.config import Settings
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.snapshot.mapper import KST

from .llm_fakes import ScriptedLlm
from .sql_fixtures import loaded_store

NOW = datetime(2026, 11, 17, 19, 43, tzinfo=KST)
TOKEN = "Bearer user-access-token"


def booking(bid, status, starts_at, seats=("J7", "J8"), screening=True):
    return {
        "bookingId": bid,
        "status": status,
        "seatLabels": list(seats),
        "seatCount": len(seats),
        "amount": 15000 * len(seats),
        "createdAt": "2026-11-10T10:00:00+09:00",
        "confirmedAt": None,
        "canceledAt": None,
        "screening": {
            "screeningId": 1,
            "movieTitle": "별의 항해",
            "theaterName": "건대입구",
            "auditoriumName": "6관",
            "startsAt": starts_at,
            "endsAt": starts_at,
        }
        if screening
        else None,
    }


def bookings_api(content=None, status=200, seen=None, total=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        if status != 200:
            return httpx.Response(status, json={"code": "X"})
        return httpx.Response(200, json={"content": content or [], "page": 0, "size": 20,
                                         "totalElements": total if total is not None
                                         else len(content or []),
                                         "totalPages": 1})  # fmt: skip

    return MyBookingsClient(
        "https://example.test/api", httpx.AsyncClient(transport=httpx.MockTransport(handler))
    )


def make(client, llm=None, cache=None):
    return ChatService(
        loaded_store(), llm or ScriptedLlm(), Settings(_env_file=None),
        clock=lambda: NOW, cache=cache, bookings=client,
    )  # fmt: skip


def ask(service, question="내 예매 보여줘", authorization=TOKEN, user="user-1"):
    return asyncio.run(service.answer(question, now=NOW, user_id=user, authorization=authorization))


def test_calls_public_api_with_the_users_own_token_and_no_claude():
    seen = []
    llm = ScriptedLlm()
    body = ask(
        make(
            bookings_api([booking(9876, "CONFIRMED", "2026-11-18T14:30:00+09:00")], seen=seen), llm
        )
    )
    assert seen[0].url.path == "/api/users/me/bookings"
    assert seen[0].headers["authorization"] == TOKEN
    assert body["path"] == "MY_BOOKINGS" and body["llm"]["calls"] == 0 and llm.requests == []
    assert body["rows"] == [
        [9876, "CONFIRMED", "별의 항해", "건대입구", "6관", "2026-11-18 14:30", "J7, J8", 30000]
    ]
    assert body["answer"].startswith(
        "다가오는 확정 예매가 1건 있습니다. "
        "가장 가까운 예매는 11월 18일 14:30 건대입구 6관 J7, J8입니다."
    )
    assert body["sql"] is None and body["asOf"] == "2026-11-17T19:43:00+09:00"


def test_only_upcoming_confirmed_are_counted_in_the_sentence():
    rows = [
        booking(1, "CONFIRMED", "2026-11-16T10:00:00+09:00"),  # 지난 것
        booking(2, "CANCELED", "2026-11-19T10:00:00+09:00"),
        booking(3, "CONFIRMED", "2026-11-20T10:00:00+09:00"),
        booking(4, "CONFIRMED", "2026-11-18T09:00:00+09:00"),
    ]
    body = ask(make(bookings_api(rows)))
    assert body["rowCount"] == 4
    assert "확정 예매가 2건" in body["answer"] and "11월 18일 09:00" in body["answer"]


def test_no_bookings():
    assert ask(make(bookings_api([])))["answer"].startswith("예매 내역이 없습니다.")
    past = [booking(1, "CANCELED", "2026-11-19T10:00:00+09:00")]
    assert "다가오는 확정 예매가 없습니다" in ask(make(bookings_api(past)))["answer"]


def test_screening_missing_when_catalog_failed():
    body = ask(make(bookings_api([booking(1, "CONFIRMED", "x", screening=False)])))
    assert body["rows"][0][2:6] == [None, None, None, None]


def test_upstream_failure_is_503_service_unavailable():
    with pytest.raises(ApiError) as caught:
        ask(make(bookings_api(status=503)))
    assert caught.value.status == 503 and caught.value.code == "SERVICE_UNAVAILABLE"
    assert caught.value.details[0].value == "user"


def test_expired_token_is_401():
    with pytest.raises(ApiError) as caught:
        ask(make(bookings_api(status=401)))
    assert caught.value.status == 401


def test_missing_token_is_401_without_calling_the_api():
    seen = []
    with pytest.raises(ApiError) as caught:
        ask(make(bookings_api(seen=seen)), authorization=None)
    assert caught.value.status == 401 and seen == []


def test_personal_answers_are_never_cached():
    cache = ChatCache(fakeredis.FakeAsyncRedis(decode_responses=True), ttl=3600, session_ttl=1800,
                      threshold=0.92, index=MemoryIndex())  # fmt: skip
    seen = []
    service = make(bookings_api([], seen=seen), cache=cache)
    ask(service, user="a")
    ask(service, user="b")
    assert len(seen) == 2  # 두 번 다 공개 API를 부른다(다른 사람 답을 재사용하지 않음)
    keys = asyncio.run(cache._v.keys("ai:exact:*"))
    assert keys == []


@pytest.mark.parametrize(
    ("question", "expected"),
    [("내 예매 보여줘", True), ("예매 내역 확인", True), ("내가 예매한 영화 뭐였지", True),
     ("내 티켓", True), ("오늘 예매 가능한 영화", False), ("건대입구 상영표", False),
     # #96 리뷰: 일반 질문을 개인 질문으로 오판하지 않는다
     ("이제 예매할 수 있는 영화 뭐 있어?", False), ("사람들이 많이 예매한 영화", False),
     ("예매 확인 없이 볼 수 있는 영화", False), ("이번 주 예약 확인 가능한 극장", False),
     ("제 예매 좀 봐주세요", True), ("나의 예약", True)],
)  # fmt: skip
def test_personal_question_detection(question, expected):
    assert is_personal(question) is expected


def test_more_than_one_page_is_marked_truncated():
    """#96 리뷰: 첫 페이지(20건)만 읽으므로 totalElements 가 더 크면 truncated 와 안내 문장."""
    items = [booking(i, "CONFIRMED", "2026-11-20 10:00") for i in range(20)]
    body = ask(make(bookings_api(items, total=25)))
    assert body["truncated"] is True and body["rowCount"] == 20
    assert "전체 25건" in body["answer"]
    assert ask(make(bookings_api(items)))["truncated"] is False


def test_my_bookings_is_not_kept_as_session_context():
    """#96 리뷰: SQL 없는 MY_BOOKINGS 는 세션에 남기지 않는다(후속 질문 문맥으로 못 쓴다)."""
    cache = ChatCache(fakeredis.FakeAsyncRedis(decode_responses=True), ttl=3600, session_ttl=1800,
                      threshold=0.92, index=MemoryIndex())  # fmt: skip
    service = make(bookings_api([]), cache=cache)
    body = ask(service)
    keys = asyncio.run(cache._v.keys("ai:session:*"))
    assert body["path"] == "MY_BOOKINGS" and keys == []


def test_expired_token_is_not_counted_as_an_error():
    """#96 리뷰: 401(사용자 토큰 문제)은 ai_chat_total{my_bookings,error}로 세지 않는다."""
    from seatsac_ai.common.metrics import AI_CHAT

    errors = AI_CHAT.labels("my_bookings", "error")
    before = errors._value.get()
    with pytest.raises(ApiError):
        ask(make(bookings_api(status=401)))
    assert errors._value.get() == before
    with pytest.raises(ApiError):
        ask(make(bookings_api(status=500)))
    assert errors._value.get() == before + 1
