"""채팅으로 좌석 잡기(선점) — WBS 3.4.19.

플래그 APP_AI_SEAT_HOLD_ENABLED(기본 켜짐, false 로 끈다).

"오늘 20시 별의 항해 건대입구 J7 J8 잡아줘"
  ① Claude가 도구 hold_seats 로 영화·극장·날짜·시각·좌석 이름만 뽑는다(좌석을 골라 주지 않는다)
  ② 스냅샷에서 회차를 정확히 하나로 좁힌다. 여럿이면 버튼(choices)으로 고르게 한다
  ③ 공개 API(좌석 현황 + 배치도)로 좌석 이름 → seatId 를 찾는다
  ④ 확인 요청(HOLD_CONFIRM): 잡을 내용을 ai Valkey에 '대기 중인 행동'으로 저장(2분) + 확인/취소 버튼
  ⑤ 확인 버튼이 직전 응답의 sessionId 로 오면 Claude 없이 대기 행동을 꺼내(한 번만) POST /seats/hold
  ⑥ 결과(HOLD_RESULT): 예매 요약 또는 오류 코드별 안내. 결제는 화면에서 한다(ai는 bookingId 만 준다)

지키는 것
  - 확인 없이 선점하는 길이 없다: POST /seats/hold 는 SeatHoldFlow._confirmed 에서만 부르고,
    그 함수는 이 사용자·이 세션의 대기 행동(GETDEL — 한 번 쓰면 사라진다)이 있을 때만 실행된다
  - 확인한 것만 잡는다: 확인 요청마다 일회용 확인 ID를 만들어 버튼 message 에 싣는다
    ("좌석 잡기 #k7f2a1"). ID가 대기 행동의 것과 같을 때만 실행한다 — 지난 확인 요청의 버튼·재전송이
    새 확인 요청을 확정하지 못한다. ID 없는 "좌석 잡기" 타이핑은 확인으로 받지 않는다
  - 좌석은 사용자가 말한 것만: Claude가 준 좌석 이름 중 이번 메시지 원문에 있는 것만 쓴다
  - 사용자 토큰은 저장하지 않는다. 확인 요청에 실려 온 Authorization 을 그대로 넘긴다
    (bookings.py 와 같은 길: 공개 주소 PUBLIC_API_BASE_URL — 클러스터 안 주소를 부르지 않는다)
  - 좌석 현황·예매 결과는 Claude에게 보내지 않는다(답은 정해진 문장). 캐시하지 않는다
  - 로그에 좌석 이름·예매 ID·토큰을 남기지 않는다(결과 코드만)
"""

from __future__ import annotations

import logging
import re
import secrets
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

import httpx

from seatsac_ai.cache.store import ChatCache
from seatsac_ai.common.errors import unauthorized
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotStore

log = logging.getLogger(__name__)

HOLD_CONFIRM = "HOLD_CONFIRM"  # 확인 요청(아직 선점하지 않았다)
HOLD_RESULT = "HOLD_RESULT"  # 선점을 시도한 결과(성공·실패)
HOLD_GUIDE = "HOLD_GUIDE"  # 선점하지 않고 안내(회차 고르기·좌석 화면으로·취소·만료)
HOLD_PATHS = (HOLD_CONFIRM, HOLD_RESULT, HOLD_GUIDE)

CONFIRM_MESSAGE = "좌석 잡기"  # 확인 버튼 message 의 앞부분. 뒤에 " #확인ID"가 붙어야 확인이다
CANCEL_MESSAGE = "취소"
_REPLY = re.compile(rf"^({CONFIRM_MESSAGE}|{CANCEL_MESSAGE})(?:\s*#([0-9a-f]{{6}}))?$")

MAX_SEATS = 4  # booking 계약 POST /seats/hold seatIds 1~4
HOLD_MINUTES = 5  # booking 선점 TTL 300초
CLOSE_MINUTES = 5  # 예매 마감: 상영 시작 5분 전부터(booking SCREENING_STARTED)
_CHOICES_MAX = 7  # 계약 ChatResponse.choices maxItems
# 외부 호출 1회: 전체 3초·연결 1초(bookings.py 와 같은 기준). 남은 기한이 더 짧으면 그쪽
_CALL_SECONDS = 3.0
_CONNECT_SECONDS = 1.0
_HOLD_ATTEMPTS = 2  # 시간 초과·연결 오류면 같은 Idempotency-Key 로 한 번만 더
_WEEKDAYS = "월화수목금토일"
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TIME = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
# 질문 원문의 좌석 이름: "J7" · "j 7" · "J열 7번" · "J열 7번, 8번"
# (같은 열의 다음 번호는 '번'이 붙을 때만 잇는다)
_SEAT_RUN = re.compile(
    r"(?<![A-Za-z0-9])([A-Za-z])\s*열?\s*(\d{1,2})(?!\d)\s*번?((?:\s*[,·와과및]?\s*\d{1,2}\s*번)*)"
)
_HOLD_VERB = re.compile(r"잡아|예매해|예약해|선점")

# seatHold.reason — 선점하지 못한 까닭(UPPER_SNAKE). 성공·확인 요청은 null
NOT_CONFIRMED = "NOT_CONFIRMED"  # 확인할 대기 행동이 없다(만료·지난 버튼·버튼 없이 타이핑)
CANCELED = "CANCELED"
SCREENING_NOT_FOUND = "SCREENING_NOT_FOUND"
SCREENING_AMBIGUOUS = "SCREENING_AMBIGUOUS"
SCREENING_STARTED = "SCREENING_STARTED"
SEATS_REQUIRED = "SEATS_REQUIRED"
TOO_MANY_SEATS = "TOO_MANY_SEATS"
SEAT_NOT_FOUND = "SEAT_NOT_FOUND"
SEAT_UNAVAILABLE = "SEAT_UNAVAILABLE"
ACTIVE_HOLD_EXISTS = "ACTIVE_HOLD_EXISTS"
UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE"  # 좌석 현황 확인 실패 · 선점을 보냈으나 결과 모름
HOLD_NOT_SENT = "HOLD_NOT_SENT"  # 선점 요청을 보내지 못했다(좌석은 잡히지 않았다)
HOLD_FAILED = "HOLD_FAILED"
# 우리 키는 확인 요청마다 새로 만든다 — 이 코드가 오면 앞선 시도가 booking 에 닿았다는 뜻(결과 모름)
_IDEMPOTENCY_CODES = ("IDEMPOTENCY_IN_PROGRESS", "IDEMPOTENCY_KEY_CONFLICT")

GO_SCREEN = "좌석 화면에서 직접 골라 주세요."


def seats_in(text: str) -> list[str]:
    """질문 원문에 적힌 좌석 이름(대문자, 적힌 순서). Claude가 준 좌석과 대조하는 기준."""
    found: list[str] = []
    for match in _SEAT_RUN.finditer(text):
        row = match.group(1).upper()
        found.append(row + match.group(2))
        found += [row + number for number in re.findall(r"\d{1,2}", match.group(3))]
    return list(dict.fromkeys(found))


def looks_like_hold(question: str) -> bool:
    """좌석 이름과 잡아 달라는 말이 같이 있다 — 캐시된 조회 답으로 대신하지 않는다(orchestrator)."""
    return bool(seats_in(question) and _HOLD_VERB.search(question))


class BadHoldInput(Exception):
    """Claude가 준 도구 입력의 형식이 틀렸다(날짜·시각). 사유를 붙여 Claude에게 다시 묻는다."""


class Upstream(Exception):
    """공개 API가 답하지 않았다(시간 초과·5xx·모양이 다른 응답)."""


@dataclass(frozen=True)
class HoldReply:
    """오케스트레이터가 ChatResponse 로 바꾸는 답. seat_hold = 응답의 seatHold 칸."""

    path: str
    answer: str
    seat_hold: dict[str, Any]
    choices: list[dict[str, str]] | None = None


@dataclass(frozen=True)
class Seat:
    seat_id: int
    available: bool
    duplicate: bool = False  # 같은 이름의 좌석이 둘 — 어느 것인지 알 수 없어 잡지 않는다


@dataclass(frozen=True)
class HoldOutcome:
    """POST /seats/hold 결과. code = 성공이면 None, 실패면 booking 오류 코드나 우리 코드."""

    code: str | None
    booking: dict[str, Any] = field(default_factory=dict)  # 성공: HoldResult
    details: list[dict[str, Any]] = field(default_factory=list)


class SeatHoldClient:
    """공개 API(CloudFront → gateway)만 부른다. 좌석 현황·배치도는 인증 없음, 선점은 사용자 토큰."""

    def __init__(self, base_url: str, http: httpx.AsyncClient | None = None) -> None:
        self._base = base_url.rstrip("/")
        self._http = http or httpx.AsyncClient(
            timeout=httpx.Timeout(_CALL_SECONDS, connect=_CONNECT_SECONDS),
            headers={"User-Agent": "seatsac-ai"},
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str, deadline: float, **params: Any) -> dict[str, Any]:
        timeout = _timeout(deadline)
        if timeout is None:
            raise Upstream(f"{path} 남은 시간 없음")
        try:
            response = await self._http.get(f"{self._base}{path}", params=params, timeout=timeout)
            if response.status_code != 200:
                raise Upstream(f"{path} {response.status_code}")
            body = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise Upstream(path) from exc
        if not isinstance(body, dict):
            raise Upstream(path)
        return body

    async def seat_map(
        self, screening_id: int, auditorium_id: int, deadline: float
    ) -> dict[str, Seat]:
        """좌석 이름(대문자) → seatId·지금 비어 있는지. 비활성 좌석은 뺀다(없는 좌석으로 본다).

        배치도는 좌석 현황이 말한 그 배치 버전으로만 읽는다. 버전을 모르거나 받은 배치도의 버전이
        다르면 이름 → seatId 가 어긋날 수 있어 실패로 본다(선점하지 않고 좌석 화면으로 안내).
        """
        snapshot = await self._get(f"/screenings/{screening_id}/seats", deadline)
        version = snapshot.get("layoutVersion")
        if not isinstance(version, int) or isinstance(version, bool):
            raise Upstream("layoutVersion 없음")
        layout = await self._get(f"/auditoriums/{auditorium_id}/layout", deadline, version=version)
        if layout.get("layoutVersion") != version:
            raise Upstream("배치 버전이 다르다")
        try:
            status = {s["seatId"]: s["status"] for s in snapshot["seats"]}
            seats: dict[str, Seat] = {}
            for s in layout["seats"]:
                if not s.get("active"):
                    continue
                label = str(s["seatLabel"]).upper()
                seat_id = s["seatId"]
                if label in seats:
                    seats[label] = Seat(seat_id, False, duplicate=True)
                else:
                    seats[label] = Seat(seat_id, status.get(seat_id) == "AVAILABLE")
            return seats
        except (KeyError, TypeError) as exc:
            raise Upstream("seat map") from exc

    async def hold(
        self,
        authorization: str,
        screening_id: int,
        seat_ids: list[int],
        idempotency_key: str,
        deadline: float,
    ) -> HoldOutcome:
        """선점 1회. 시간 초과·연결 오류면 같은 Idempotency-Key 로 한 번만 더(남은 기한 안에서).

        HOLD_NOT_SENT = 요청이 나가지 않은 것이 확실하다(남은 시간 없음·연결을 맺지 못함).
        UPSTREAM_UNAVAILABLE = 보냈지만 결과를 모른다(선점됐을 수도 있다).
        """
        sent = False
        for _ in range(_HOLD_ATTEMPTS):
            timeout = _timeout(deadline)
            if timeout is None:
                break
            try:
                response = await self._http.post(
                    f"{self._base}/seats/hold",
                    json={"screeningId": screening_id, "seatIds": seat_ids},
                    headers={"Authorization": authorization, "Idempotency-Key": idempotency_key},
                    timeout=timeout,
                )
            except (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout):
                continue  # 연결을 맺지 못했다 — 요청이 나가지 않았다
            except httpx.HTTPError:
                sent = True  # 보낸 뒤 끊겼거나 응답이 늦다 — 같은 키로 다시 물어본다
                continue
            return self._outcome(response)
        return HoldOutcome(UPSTREAM_UNAVAILABLE if sent else HOLD_NOT_SENT)

    @staticmethod
    def _outcome(response: httpx.Response) -> HoldOutcome:
        if response.status_code == 401:
            raise unauthorized("INVALID_TOKEN")
        try:
            body = response.json()
        except ValueError:
            body = None
        if not isinstance(body, dict) or response.status_code >= 500:
            return HoldOutcome(UPSTREAM_UNAVAILABLE)
        if response.status_code == 201:
            if not isinstance(body.get("bookingId"), int):
                return HoldOutcome(UPSTREAM_UNAVAILABLE)
            return HoldOutcome(None, booking=body)
        code = str(body.get("code") or HOLD_FAILED)
        if code in _IDEMPOTENCY_CODES:
            return HoldOutcome(UPSTREAM_UNAVAILABLE)
        details = body.get("details")
        return HoldOutcome(code, details=details if isinstance(details, list) else [])


def _timeout(deadline: float) -> httpx.Timeout | None:
    """전체 3초·연결 1초, 요청 전체 기한까지 남은 시간이 더 짧으면 그쪽. 남은 시간이 없으면 None."""
    left = deadline - time.monotonic()
    if left <= 0:
        return None
    return httpx.Timeout(min(_CALL_SECONDS, left), connect=min(_CONNECT_SECONDS, left))


class SeatHoldFlow:
    def __init__(
        self, store: SnapshotStore, cache: ChatCache, client: SeatHoldClient, confirm_ttl: int
    ) -> None:
        self._store = store
        self._cache = cache
        self._client = client
        self._ttl = confirm_ttl

    # --- ⑤ 확인·취소·회차 고르기 (Claude 없이) ----------------------------------

    async def resume(
        self,
        message: str,
        *,
        user_id: str,
        sent_session_id: str | None,
        session_id: str,
        authorization: str | None,
        now: datetime,
        deadline: float,
    ) -> tuple[HoldReply | None, bool]:
        """대기 중인 행동을 꺼내(지우고) 이번 메시지가 그 답이면 이어서 처리한다.

        돌려주는 것: (답, 보낸 sessionId 에 대기 행동이 있었는지). 답이 None 이면 좌석 잡기의 답이
        아니다 — 대기 행동은 이미 지워졌고(다른 질문이 오면 지운다) 평소처럼 처리한다.
        대기 행동이 있었으면 응답도 보낸 sessionId 를 그대로 쓴다(흐름 동안 바뀌지 않게).
        sent_session_id = 요청이 보낸 sessionId(대기 행동의 키), session_id = 새로 발급된 sessionId.
        """
        reply = _REPLY.fullmatch(message.strip())
        verb, confirm_id = (reply.group(1), reply.group(2)) if reply else (None, None)
        if verb == CONFIRM_MESSAGE and confirm_id is None:
            # 버튼 없이 타이핑한 "좌석 잡기" — 확인으로 받지 않는다. 대기 행동은 건드리지 않는다
            return _guide(
                "좌석을 잡으려면 확인 요청의 「잡기」 버튼을 눌러 주세요. "
                "확인 요청이 없으면 잡을 회차와 좌석을 다시 말해 주세요.",
                NOT_CONFIRMED,
            ), False
        if verb == CONFIRM_MESSAGE and not authorization:
            raise unauthorized("TOKEN_REQUIRED")  # 대기 행동을 꺼내기 전에 — 꺼낸 뒤 401이면 헛소모
        pending = None
        if sent_session_id:
            pending = await self._cache.take_pending(user_id, sent_session_id)
        if pending is not None and (
            pending.get("userId") != user_id or pending.get("expiresAt", 0) <= now.timestamp()
        ):
            pending = (
                None  # 키가 사용자별이라 올 수 없는 값이지만 한 번 더 본다 · TTL이 늦게 지운 경우
            )
        if pending is None or sent_session_id is None:
            if verb is None:
                return None, False
            # 대기 행동 없이 온 확인·취소 — 아무것도 하지 않는다(Claude도 부르지 않는다)
            return _guide(
                "확인할 좌석 요청이 없어요(확인 시간이 지났을 수 있어요). "
                "잡을 회차와 좌석을 다시 말해 주세요.",
                NOT_CONFIRMED,
            ), False
        return await self._answer_pending(
            pending, message.strip(), verb, confirm_id, authorization, user_id, sent_session_id,
            now, deadline,
        ), True  # fmt: skip

    async def _answer_pending(
        self, pending, text, verb, confirm_id, authorization, user_id, session_id, now, deadline
    ) -> HoldReply | None:
        """꺼낸 대기 행동에 대한 답인지 보고 처리한다. session_id = 대기 행동이 있던 sessionId."""
        if verb == CANCEL_MESSAGE:
            return _guide("좌석을 잡지 않았습니다.", CANCELED, pending.get("screeningId"))
        if pending.get("kind") == "CONFIRM" and verb == CONFIRM_MESSAGE:
            if confirm_id != pending.get("confirmId"):
                # 지난 확인 요청의 버튼(또는 그 재전송) — 지금 대기 중인 것을 대신 확정하지 않는다
                return _guide(
                    "지난 확인 요청의 버튼이에요. 좌석을 잡지 않았습니다. "
                    "잡을 회차와 좌석을 다시 말해 주세요.",
                    NOT_CONFIRMED,
                )
            return await self._confirmed(pending, authorization, user_id, session_id, now, deadline)
        if pending.get("kind") == "SCREENING" and verb is None:
            picked = pending["options"].get(text)
            if picked is None:
                return None
            row = self._screening(picked)
            if row is None:
                return _guide("그 회차를 찾지 못했어요. 다시 말해 주세요.", SCREENING_NOT_FOUND)
            return await self._propose(row, pending["seats"], user_id, session_id, now, deadline)
        if verb is not None:
            return _guide(
                "확인할 좌석 요청이 없어요. 잡을 회차와 좌석을 다시 말해 주세요.", NOT_CONFIRMED
            )
        return None

    async def _confirmed(
        self,
        pending: dict[str, Any],
        authorization: str,
        user_id: str,
        session_id: str,
        now: datetime,
        deadline: float,
    ) -> HoldReply:
        """POST /seats/hold 를 부르는 단 하나의 자리. 확인 ID가 맞는 대기 행동이 있어야 온다."""
        screening_id = pending["screeningId"]
        labels = pending["seatLabels"]
        outcome = await self._client.hold(
            authorization, screening_id, pending["seatIds"], pending["idempotencyKey"], deadline
        )
        if outcome.code is not None:
            log.warning("좌석 선점 실패", extra={"fields": {"holdReason": outcome.code}})
        if outcome.code is None:
            booking = outcome.booking
            held = [str(x) for x in booking.get("seatLabels") or labels]
            expires = _iso(booking.get("expiresAt"))
            until = f"{expires:%H:%M}까지 " if expires else f"{HOLD_MINUTES}분 안에 "
            return HoldReply(
                HOLD_RESULT,
                f"{'·'.join(held)} 좌석을 잡았습니다. {until}결제를 마쳐 주세요.",
                _seat_hold(
                    screening_id,
                    booking_id=booking["bookingId"],
                    labels=held,
                    amount=booking.get("amount"),
                    expires_at=booking.get("expiresAt"),
                ),
            )
        if outcome.code == HOLD_NOT_SENT:
            # 요청이 나가지 않았다(좌석은 잡히지 않았다). 남은 확인 시간이 있으면 대기 행동을 되돌려
            # 같은 버튼(같은 확인 ID·같은 Idempotency-Key)으로 다시 누를 수 있게 한다
            left = int(pending["expiresAt"] - now.timestamp())
            if left > 0 and await self._cache.put_pending(user_id, session_id, pending, left):
                return HoldReply(
                    HOLD_RESULT,
                    "선점 요청을 보내지 못했어요(좌석은 잡히지 않았습니다). "
                    "잠시 뒤 아래 버튼을 다시 눌러 주세요.",
                    _seat_hold(
                        screening_id,
                        labels=labels,
                        reason=HOLD_NOT_SENT,
                        confirm_expires_at=_at(pending["expiresAt"]),
                    ),
                    _buttons(labels, pending["confirmId"]),
                )
            answer = f"선점 요청을 보내지 못했어요(좌석은 잡히지 않았습니다). {GO_SCREEN}"
            return _result(answer, HOLD_NOT_SENT, screening_id, labels)
        if outcome.code == SEAT_UNAVAILABLE:
            answer = f"방금 다른 분이 먼저 잡은 좌석이 있어요. {GO_SCREEN}"
            return _result(answer, SEAT_UNAVAILABLE, screening_id, labels)
        if outcome.code == ACTIVE_HOLD_EXISTS:
            found = {d.get("field"): d.get("value") for d in outcome.details if isinstance(d, dict)}
            booking_id = found.get("bookingId")
            return HoldReply(
                HOLD_RESULT,
                "이미 진행 중인 예매(선점)가 있어요. 그 예매를 먼저 결제하거나 취소해 주세요.",
                _seat_hold(
                    screening_id,
                    booking_id=booking_id if isinstance(booking_id, int) else None,
                    expires_at=found.get("expiresAt"),
                    reason=ACTIVE_HOLD_EXISTS,
                ),
            )
        if outcome.code == SCREENING_STARTED:
            return _result("예매가 마감된 회차입니다(상영 시작 5분 전까지).", SCREENING_STARTED)
        if outcome.code == UPSTREAM_UNAVAILABLE:
            # 보냈으나 결과를 모른다(선점됐을 수도 있다). 더 시도하지 않고 화면에서 확인하게 한다
            answer = (
                "선점 요청은 보냈지만 결과를 확인하지 못했어요. 좌석이 잡혔을 수도 있으니 "
                "좌석 화면이나 마이페이지 예매 내역에서 확인해 주세요."
            )
            return _result(answer, UPSTREAM_UNAVAILABLE, screening_id, labels)
        # VALIDATION_FAILED·NOT_FOUND 등 — 사용자가 고칠 수 없는 것
        return _result(f"좌석을 잡지 못했습니다. {GO_SCREEN}", HOLD_FAILED, screening_id, labels)

    # --- ②③④ 도구 hold_seats ---------------------------------------------------

    async def start(
        self,
        raw: dict[str, Any],
        *,
        question: str,
        user_id: str,
        session_id: str,
        now: datetime,
        deadline: float,
    ) -> HoldReply:
        """Claude가 뽑은 영화·극장·날짜·시각·좌석 이름 → 확인 요청(또는 안내). 선점하지 않는다.

        question = 이번 사용자 메시지 원문. 좌석은 Claude가 준 것 중 원문에 적힌 것만 쓴다
        (Claude가 지어냈거나 주입 문장에 끌려 넣은 좌석을 버린다).
        """
        day, clock = _text(raw.get("date")), _text(raw.get("time"))
        if day and not _DATE.fullmatch(day):
            raise BadHoldInput(f"date 형식이 틀렸다(YYYY-MM-DD): {day}")
        if clock and not _TIME.fullmatch(clock):
            raise BadHoldInput(f"time 형식이 틀렸다(HH:MM): {clock}")
        movie, theater = _text(raw.get("movie")), _text(raw.get("theater"))
        given = raw.get("seats")
        said = seats_in(question)
        from_claude = [_seat_name(s) for s in (given if isinstance(given, list) else [])]
        seats = [s for s in dict.fromkeys(from_claude) if s in said]
        if len(seats) > MAX_SEATS:
            return _guide(
                f"한 번에 최대 {MAX_SEATS}석까지 잡을 수 있어요. 좌석을 줄여서 다시 말해 주세요.",
                TOO_MANY_SEATS,
            )
        # 최소 조건: (영화 또는 극장) + (날짜 또는 시각). 없으면 회차를 짐작으로 좁히지 않는다
        missing = [
            word
            for word, have in (("영화나 극장", movie or theater), ("날짜나 시각", day or clock))
            if not have
        ]
        if missing:
            return _guide(
                f"어느 회차인지 알 수 있게 {'·'.join(missing)}을(를) 함께 말해 주세요. "
                "(예: 오늘 20시 건대입구 J7 J8 잡아줘)",
                SCREENING_AMBIGUOUS,
                labels=seats,
            )
        closes = _closes(now)
        rows, miss = self._find(movie, theater, day, clock, closes)
        if miss:
            return _guide(miss, SCREENING_NOT_FOUND)
        if not rows:
            if self._find(movie, theater, day, clock, None)[0]:
                return _guide(
                    "이미 시작했거나 예매가 마감된 회차입니다(상영 시작 5분 전까지).",
                    SCREENING_STARTED,
                )
            return _guide(
                "조건에 맞는 회차를 찾지 못했어요. 영화·극장·날짜·시각을 다시 말해 주세요.",
                SCREENING_NOT_FOUND,
            )
        if len(rows) > _CHOICES_MAX:
            return _guide(
                "조건에 맞는 회차가 많아요. 영화·극장·날짜·시각을 더 알려 주세요.",
                SCREENING_AMBIGUOUS,
                labels=seats,
            )
        if len(rows) > 1:
            choices = [{"label": _label(r), "message": _label(r)} for r in rows]
            stored = await self._cache.put_pending(
                user_id,
                session_id,
                {
                    "kind": "SCREENING",
                    "userId": user_id,
                    "seats": seats,
                    "options": {
                        c["message"]: r["screening_id"] for c, r in zip(choices, rows, strict=True)
                    },
                    "expiresAt": now.timestamp() + self._ttl,
                },
                self._ttl,
            )
            if not stored:
                # 고른 답을 기억할 수 없으면 버튼을 내지 않는다
                return _guide(
                    "조건에 맞는 회차가 여럿이에요. 영화·극장·날짜·시각을 더 알려 주세요.",
                    SCREENING_AMBIGUOUS,
                    labels=seats,
                )
            return HoldReply(
                HOLD_GUIDE,
                "어느 회차인가요? 골라 주세요.",
                _seat_hold(None, labels=seats, reason=SCREENING_AMBIGUOUS),
                choices,
            )
        return await self._propose(rows[0], seats, user_id, session_id, now, deadline)

    async def _propose(
        self, row: dict[str, Any], seats: list[str], user_id: str, session_id: str, now, deadline
    ) -> HoldReply:
        """회차 하나 + 좌석 이름 → 좌석을 확인하고 확인 요청을 만든다(대기 행동 저장)."""
        screening_id = row["screening_id"]
        if row["starts_at"] <= _closes(now):
            # 회차 버튼을 고르는 사이에 마감됐을 수 있다
            return _guide(
                "이미 시작했거나 예매가 마감된 회차입니다(상영 시작 5분 전까지).", SCREENING_STARTED
            )
        if not seats:
            # 좌석은 추천하지 않는다 — 화면에서 고르게 한다
            return _guide(
                f"{_label(row)} 회차입니다. 잡을 좌석 이름을 알 수 없어요. {GO_SCREEN}",
                SEATS_REQUIRED,
                screening_id,
            )
        try:
            seat_map = await self._client.seat_map(screening_id, row["auditorium_id"], deadline)
        except Upstream as exc:
            log.warning("좌석 현황 조회 실패: %s", exc)
            return _guide(
                f"좌석 현황을 지금 확인하지 못했어요. {GO_SCREEN}",
                UPSTREAM_UNAVAILABLE,
                screening_id,
                seats,
            )
        unknown = [s for s in seats if s not in seat_map]
        if unknown:
            return _guide(
                f"{'·'.join(unknown)} 좌석은 이 상영관에 없어요. {GO_SCREEN}",
                SEAT_NOT_FOUND,
                screening_id,
                seats,
            )
        twice = [s for s in seats if seat_map[s].duplicate]
        if twice:
            return _guide(
                f"{'·'.join(twice)} 좌석은 이름이 겹쳐 채팅으로 잡을 수 없어요. {GO_SCREEN}",
                SEAT_NOT_FOUND,
                screening_id,
                seats,
            )
        taken = [s for s in seats if not seat_map[s].available]
        if taken:
            return _guide(
                f"{'·'.join(taken)} 좌석은 이미 다른 분이 잡았어요. {GO_SCREEN}",
                SEAT_UNAVAILABLE,
                screening_id,
                seats,
            )
        confirm_id = secrets.token_hex(3)
        expires_at = now.timestamp() + self._ttl
        stored = await self._cache.put_pending(
            user_id,
            session_id,
            {
                "kind": "CONFIRM",
                "userId": user_id,
                "confirmId": confirm_id,
                "screeningId": screening_id,
                "seatIds": [seat_map[s].seat_id for s in seats],
                "seatLabels": seats,
                "idempotencyKey": str(uuid.uuid4()),
                "expiresAt": expires_at,
            },
            self._ttl,
        )
        if not stored:
            # 확인 단계를 기억할 수 없으면 잡지 않는다
            return _guide(
                f"지금은 채팅으로 좌석을 잡을 수 없어요. {GO_SCREEN}",
                UPSTREAM_UNAVAILABLE,
                screening_id,
                seats,
            )
        minutes = max(1, self._ttl // 60)
        # 금액은 적지 않는다(요금 규칙은 booking 의 것) — 선점 뒤 booking 이 준 금액을 보여 준다
        return HoldReply(
            HOLD_CONFIRM,
            f"{_label(row)} {'·'.join(seats)} — {len(seats)}석. "
            f"이 좌석을 {HOLD_MINUTES}분간 잡을까요? ({minutes}분 안에 눌러 주세요)",
            _seat_hold(screening_id, labels=seats, confirm_expires_at=_at(expires_at)),
            _buttons(seats, confirm_id),
        )

    # --- 스냅샷에서 회차 찾기 ----------------------------------------------------

    _SELECT = (
        "SELECT s.screening_id, s.auditorium_id, s.auditorium_name, s.starts_at, "
        "m.title AS movie, t.name AS theater FROM screening s "
        "JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id "
    )

    def _screening(self, screening_id: int) -> dict[str, Any] | None:
        with self._store.reader() as conn:
            cursor = conn.execute(self._SELECT + "WHERE s.screening_id = ?", (screening_id,))
            names = [c[0] for c in cursor.description]
            row = cursor.fetchone()
        return dict(zip(names, row, strict=True)) if row else None

    def _find(
        self, movie: str, theater: str, day: str, clock: str, closes: str | None
    ) -> tuple[list[dict[str, Any]], str | None]:
        """(조건에 맞는 회차, 이름을 못 찾았을 때의 안내).

        closes 가 있으면 그 시각 뒤에 시작하는(예매할 수 있는) 회차만, 버튼 상한 + 1개까지 —
        마감 조건을 SQL에 둔다(지나간 회차가 많아도 예매할 수 있는 회차를 놓치지 않게).
        None 이면 마감·시작한 것까지 1개만(「마감」과 「없음」을 구분하는 데 쓴다).
        """
        where: list[str] = []
        params: list[Any] = []
        with self._store.reader() as conn:
            for said, table, column, id_column, word in (
                (movie, "movie", "title", "movie_id", "영화"),
                (theater, "theater", "name", "theater_id", "극장"),
            ):
                if not said:
                    continue
                names = conn.execute(f"SELECT {id_column}, {column} FROM {table}").fetchall()  # noqa: S608
                ids = _match(names, said)
                if not ids:
                    return [], f"'{said}' {word}을(를) 지금 상영 정보에서 찾지 못했어요."
                where.append(f"s.{id_column} IN ({', '.join('?' * len(ids))})")
                params += ids
            if day:
                # 자정 넘은 심야 회차(영업일은 전날)도 사용자가 말한 날짜로 찾는다
                where.append("(s.business_date = ? OR substr(s.starts_at, 1, 10) = ?)")
                params += [day, day]
            if clock:
                where.append("substr(s.starts_at, 12, 5) = ?")
                params.append(clock)
            if closes is not None:
                where.append("s.starts_at > ?")
                params.append(closes)
            limit = _CHOICES_MAX + 1 if closes is not None else 1
            sql = self._SELECT + (f"WHERE {' AND '.join(where)} " if where else "")
            cursor = conn.execute(
                sql + f"ORDER BY s.starts_at, s.screening_id LIMIT {limit}", params
            )
            names = [c[0] for c in cursor.description]
            rows = [dict(zip(names, r, strict=True)) for r in cursor.fetchall()]
        return rows, None


def _match(names: list[tuple[int, str]], said: str) -> list[int]:
    """이름 그대로면 그것만, 아니면 일부가 겹치는 것 전부('건대' → '건대입구')."""
    key = re.sub(r"\s+", "", said).lower()
    squashed = [(i, re.sub(r"\s+", "", str(n)).lower()) for i, n in names]
    exact = [i for i, n in squashed if n == key]
    return exact or [i for i, n in squashed if key in n or n in key]


def _text(value: Any) -> str:
    return str(value).strip() if value not in (None, "") else ""


def _seat_name(value: Any) -> str:
    """Claude가 준 좌석 이름을 원문에서 뽑은 것과 같은 모양으로('j 7'·'J열7번' → 'J7')."""
    return re.sub(r"[\s열번]", "", str(value)).upper()


def _closes(now: datetime) -> str:
    """이 시각(스냅샷 starts_at 과 같은 KST 문자열) 뒤에 시작하는 회차만 예매할 수 있다."""
    return f"{now.astimezone(KST) + timedelta(minutes=CLOSE_MINUTES):%Y-%m-%d %H:%M}"


def _iso(value: Any) -> datetime | None:
    try:
        return datetime.fromisoformat(str(value)).astimezone(KST)
    except ValueError:
        return None


def _at(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, KST).isoformat(timespec="seconds")


def _label(row: dict[str, Any]) -> str:
    """'별의 항해 11/17(화) 20:00 건대입구 1관' — 회차 버튼 글자이자 확인 문장의 앞부분."""
    at = datetime.strptime(row["starts_at"], "%Y-%m-%d %H:%M")
    when = f"{at.month}/{at.day}({_WEEKDAYS[at.weekday()]}) {at:%H:%M}"
    place = " ".join(x for x in (row["theater"], row["auditorium_name"]) if x)
    return f"{row['movie']} {when} {place}"


def _buttons(labels: list[str], confirm_id: str) -> list[dict[str, str]]:
    """확인·취소 버튼. 글자에 좌석을 적고, message 에 확인 ID를 싣는다."""
    return [
        {"label": f"{'·'.join(labels)} 잡기", "message": f"{CONFIRM_MESSAGE} #{confirm_id}"},
        {"label": CANCEL_MESSAGE, "message": f"{CANCEL_MESSAGE} #{confirm_id}"},
    ]


def _seat_hold(
    screening_id: int | None,
    *,
    booking_id: int | None = None,
    labels: list[str] | None = None,
    amount: int | None = None,
    expires_at: Any = None,
    confirm_expires_at: str | None = None,
    reason: str | None = None,
) -> dict[str, Any]:
    return {
        "screeningId": screening_id,
        "bookingId": booking_id,
        "seatLabels": labels or [],
        "amount": amount if isinstance(amount, int) else None,
        "expiresAt": expires_at if isinstance(expires_at, str) else None,
        "confirmExpiresAt": confirm_expires_at,
        "reason": reason,
    }


def _guide(
    answer: str, reason: str, screening_id: int | None = None, labels: list[str] | None = None
) -> HoldReply:
    return HoldReply(HOLD_GUIDE, answer, _seat_hold(screening_id, labels=labels, reason=reason))


def _result(
    answer: str, reason: str, screening_id: int | None = None, labels: list[str] | None = None
) -> HoldReply:
    return HoldReply(HOLD_RESULT, answer, _seat_hold(screening_id, labels=labels, reason=reason))
