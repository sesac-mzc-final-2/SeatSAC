"""문맥 이어받기 1단계 (WBS 3.4.22) — 답 아래 「영화 고르기」 버튼이 앞 답의 조건을 잃지 않게.

  "부산본점 내일 영화 뭐해?" → [부활남] 버튼 → 예전: 전 극장·전 날짜 회차 / 지금: 내일·부산본점만

① 답마다 「이 답이 실제로 조회한 날짜·극장·영화」를 세션의 ctx 목록에 값으로 남긴다.
   값은 질문 글자가 아니라 **실행한 SQL의 조건**(sql/conditions.py)에서 뽑고,
   하나로 확정될 때만 쓴다(날짜 2개·OR·범위는 버린다).
   날짜는 절대 날짜라 자정을 넘겨 눌러도 하루가 밀리지 않는다.
② 버튼은 「어느 답에 이어지는지」(그 답의 context.id)를 continueFrom 으로 들고 온다.
   위로 올려 예전 답의 버튼을 눌러도 그 답의 조건을 쓴다(목록 · 최신이 뒤 · 최대 5개).
③ 그 조건 + 새 메시지의 영화 제목으로 내부 SQL 틀(resources/context_templates.yaml)을 고른다.
   문장은 다시 해석하지 않는다 — 제목에 "7일"·"8시"가 있으면 날짜·시각으로 잘못 읽힌다.
   Claude를 부르지 않는다.

이 모듈은 값 다루기만 한다(세션 읽기·SQL 실행·응답은 llm/orchestrator.py).
직접 친 질문은 이 모듈을 타지 않는다 — continueFrom 이 있을 때만이다.
시각: "○시 이후/이전" 하나는 이어받는다("오늘 20시 이후" 답 → [부활남] → 오늘 20시 이후 부활남).
   SQL의 시각 조건이 질문에서 뽑은 시각(또는 프리셋 빈칸 time)·말뜻(이후/이전)과 같을 때만이고
   (confirmed_time), 어긋나면 그 답에는 문맥을 남기지 않는다. 시각은 날짜에 붙은 값이라
   날짜를 이어받을 때만 같이 이어받는다(날짜가 지났으면 시각도 버린다).
한계: 시각 범위 두 개(20시~22시)·start_hour 표현·심야/조조 같은 고정 범위·개수 조건은
   이어받지 않는다(시각 쪽은 그 답에 문맥을 남기지 않는다).
"""

from __future__ import annotations

import re
import secrets
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from functools import lru_cache
from typing import Any

from seatsac_ai.cache.slots import RELATIVE_DAYS, extract, find_name
from seatsac_ai.cache.warm import WarmItem, internal_items
from seatsac_ai.resources import Preset, context_templates
from seatsac_ai.sql.conditions import Conditions, TimeBound

KINDS = ("date", "theater", "movie")
DIRECTIONS = {"after": "이후", "before": "이전"}  # 계약 ChatContext.timeDirection → 화면 글자
# 조건 표시(label)·답 문장 앞부분의 순서: 날짜 · 극장 · 시각 · 영화
_LABEL_ORDER = ("date", "theater", "time", "movie")
_HH_MM = re.compile(r"(?:[01]\d|2[0-3]):[0-5]\d")
MAX_ENTRIES = 5  # 세션에 남기는 답의 조건 수(최신이 뒤)
CONTINUE_FROM_MAX = 64  # 계약 ChatRequest.continueFrom maxLength
_RELATIVE_WORDS = ("오늘", "내일", "모레")
_WEEKDAYS = "월화수목금토일"


@dataclass(frozen=True)
class Plan:
    """이어받아 실행할 것. values = 틀의 빈칸 값, inherited = 앞 답에서 가져온 칸."""

    template: Preset
    values: dict[str, str]
    inherited: tuple[str, ...]
    sentence: str  # 완성 문장("11월 18일 부산본점 부활남 회차") — 세션의 직전 질문으로 남긴다
    warm: WarmItem  # 틀의 캐시 키(ctx …)·항목. 원래의 짧은 메시지 키는 쓰지 않는다

    @property
    def entry_values(self) -> dict[str, str | None]:
        """이 답의 조건(세션 ctx·응답 context 에 남길 값). 시각의 방향은 고른 틀이 정한다."""
        return {**self.values, "timeDirection": self.template.time_direction}


# --- 세션의 ctx 목록 --------------------------------------------------------------


def entries(session: Mapping[str, Any] | None) -> list[dict[str, Any]]:
    """세션에 남은 답의 조건들. 모양이 다른 것(옛 세션·깨진 값)은 버린다."""
    raw = (session or {}).get("ctx")
    if not isinstance(raw, list):
        return []
    return [e for e in raw if isinstance(e, dict) and isinstance(e.get("id"), str)][-MAX_ENTRIES:]


def find(known: Sequence[Mapping[str, Any]], context_id: str | None) -> Mapping[str, Any] | None:
    """continueFrom 이 가리키는 답의 조건. 모르는 값이면 None(오류 아님)."""
    if not context_id:
        return None
    return next((e for e in known if e["id"] == context_id), None)


def new_entry(known: Sequence[Mapping[str, Any]], values: Mapping[str, Any]) -> dict[str, Any]:
    """이번 답의 조건에 짧은 임의 id 를 붙인다(세션 안에서만 뜻이 있다)."""
    taken = {e["id"] for e in known}
    while (context_id := secrets.token_hex(3)) in taken:
        pass
    return {"id": context_id} | {kind: values.get(kind) for kind in KINDS} | _time_of(values)


def _time_of(values: Mapping[str, Any]) -> dict[str, str | None]:
    """시각 칸 둘(time·timeDirection). 모양이 맞고 날짜가 있을 때만 — 아니면 둘 다 None."""
    time, direction = values.get("time"), values.get("timeDirection")
    ok = (
        isinstance(time, str)
        and _HH_MM.fullmatch(time) is not None
        and direction in DIRECTIONS
        and bool(values.get("date"))
    )
    return {"time": time if ok else None, "timeDirection": direction if ok else None}


def appended(known: Sequence[Mapping[str, Any]], entry: Mapping[str, Any]) -> list[dict[str, Any]]:
    return [dict(e) for e in [*known, entry]][-MAX_ENTRIES:]


# --- 실행한 SQL의 조건 → 값 --------------------------------------------------------


def from_conditions(
    conditions: Conditions, theaters: Iterable[tuple[int, str]], movies: Iterable[str]
) -> dict[str, str | None]:
    """조건이 하나로 확정된 칸만 값으로. 날짜는 절대 날짜, 극장·영화는 스냅샷 이름."""
    day = None
    if conditions.dates is not None and len(conditions.dates) == 1:
        day = _iso_day(next(iter(conditions.dates)))
    theater_names = {
        name
        for theater_id, name in theaters
        if any(m.accepts(name) for m in conditions.theaters or ())
        or theater_id in (conditions.theater_ids or ())
    }
    movie_titles = {t for t in movies if any(m.accepts(t) for m in conditions.movies or ())}
    return {
        "date": day,
        "theater": next(iter(theater_names)) if len(theater_names) == 1 else None,
        "movie": next(iter(movie_titles)) if len(movie_titles) == 1 else None,
    }


def confirmed_time(
    bound: TimeBound, said_times: Iterable[str], meaning: Iterable[str]
) -> dict[str, str] | None:
    """SQL의 시각 조건이 「사용자가 말한 ○시 이후/이전」일 때만 값으로. 아니면 None.

    None 이면 그 답에는 문맥을 남기지 않는다(시각을 빼고 남기면 앞 답보다 넓은 답이 된다).
    - said_times: 질문에서 뽑은 시각(cache/slots.py time 슬롯)·프리셋 빈칸 time. SQL의 시각이
      이 중에 없으면 사용자가 말한 시각이 아니다(지금 시각을 조금 다르게 적은 글자일 수 있다)
    - meaning: 질문의 뜻 묶음(slots.meaning_signature). SQL 연산자의 방향(after·before)이 있고
      반대 방향은 없어야 한다 — "20시 이전"을 물었는데 SQL이 >= 면 남기지 않는다
    """
    meaning = set(meaning)
    opposite = "before" if bound.direction == "after" else "after"
    if bound.time not in set(said_times):
        return None
    if bound.direction not in meaning or opposite in meaning:
        return None
    return {"time": bound.time, "timeDirection": bound.direction}


def _iso_day(text: object) -> str | None:
    try:
        return date.fromisoformat(str(text)).isoformat()
    except ValueError:
        return None


# --- 버튼 클릭: 앞 답의 조건 + 새 메시지의 영화 → 내부 틀 ------------------------------


def plan(
    entry: Mapping[str, Any],
    message: str,
    now: datetime,
    theaters: Sequence[str],
    movies: Sequence[str],
) -> Plan | None:
    """이어받아 실행할 틀과 값. 이어받을 것이 없으면 None(평소 경로로 답한다).

    - 영화는 새 메시지에서만 읽는다(스냅샷에 있는 제목). 없으면 None — 1단계는 영화 버튼뿐이다
    - 제목을 뺀 나머지에 날짜·극장이 있으면 그 값이 앞 답의 값보다 먼저다.
      틀이 담지 못하는 조건(시각·개수·날짜 여러 개)이 있으면 None
    - 앞 답의 날짜가 지났거나 극장이 스냅샷에서 내려갔으면 그 칸만 버린다
    - 시각("○시 이후/이전")은 앞 답의 날짜를 이어받을 때만 같이 이어받는다. 날짜가 지났거나
      새 메시지가 날짜를 말했으면 버린다(그 시각은 앞 답의 날짜에 붙은 조건이다).
      오늘의 이미 지난 시각이어도 그대로 둔다 — 틀에 「지금 이후」가 함께 걸려 있다
    """
    hit = find_name(message, movies)
    if hit is None:
        return None
    movie, raw = hit
    # 제목 글자를 뺀 나머지만 본다 — "7일의 왕비"의 "7일"이 날짜로 읽히지 않게
    rest = extract(message.replace(raw, " ", 1), now, theaters)
    if not rest.cacheable or set(rest.slots) - {"date", "theater"}:
        return None
    today = now.date()
    said: dict[str, str] = {k: str(s.value) for k, s in rest.slots.items()}
    if rest.relative_words:
        if "date" in said:
            return None  # "내일 11월 20일" — 어느 쪽인지 정하지 않는다
        offset = RELATIVE_DAYS[next(iter(rest.relative_words))]
        said["date"] = (today + timedelta(days=offset)).isoformat()

    values: dict[str, str] = {"movie": movie}
    inherited: list[str] = []
    for kind in ("date", "theater"):
        if kind in said:
            values[kind] = said[kind]
        elif _still_valid(kind, entry.get(kind), today, theaters):
            values[kind] = str(entry[kind])
            inherited.append(kind)
    if not inherited:
        return None
    direction = None
    if "date" in inherited:
        time = _time_of(entry)
        if time["time"] is not None:
            values["time"], direction = time["time"], time["timeDirection"]
            inherited.append("time")
    template = _TEMPLATE_OF[frozenset(values), direction]
    warm = _warm_item(template.preset_id)
    return Plan(template, values, tuple(inherited), _sentence(template, values), warm)


def _still_valid(kind: str, value: object, today: date, theaters: Sequence[str]) -> bool:
    if not isinstance(value, str) or not value:
        return False
    if kind == "date":
        day = _iso_day(value)
        return day is not None and date.fromisoformat(day) >= today
    return value in theaters


# (빈칸 종류, 시각 방향) → 틀. 연산자(>=·<=)는 틀에 미리 써 둔 것을 고를 뿐 SQL에 끼워 넣지 않는다
_TEMPLATE_OF = {(frozenset(t.params), t.time_direction): t for t in context_templates()}


@lru_cache
def _warm_item(template_id: str) -> WarmItem:
    """그 틀의 예열 항목(키 `ctx {presetId}` — 직접 친 질문의 키와 겹치지 않는다)."""
    return next(i for i in internal_items(context_templates()) if i.entry.preset_id == template_id)


def _sentence(template: Preset, values: Mapping[str, str]) -> str:
    text = template.phrasings[0]
    for kind, value in values.items():
        if kind == "date":
            day = date.fromisoformat(value)
            value = f"{day.month}월 {day.day}일"
        text = text.replace("{" + kind + "}", value)
    return text


# --- 화면에 보여 줄 글 -------------------------------------------------------------


def day_label(day: str, today: date) -> str:
    """ "내일 11/20(금)" — 되묻기 날짜 버튼(orchestrator._clarify_date)과 같은 모양."""
    d = date.fromisoformat(day)
    short = f"{d.month}/{d.day}({_WEEKDAYS[d.weekday()]})"
    offset = (d - today).days
    return f"{_RELATIVE_WORDS[offset]} {short}" if 0 <= offset < len(_RELATIVE_WORDS) else short


def time_label(time: str, direction: str) -> str:
    """ "20시 이후" · "20시 30분 이전"."""
    hour, minute = int(time[:2]), int(time[3:])
    clock = f"{hour}시 {minute}분" if minute else f"{hour}시"
    return f"{clock} {DIRECTIONS[direction]}"


def label(values: Mapping[str, Any], kinds: Iterable[str], today: date) -> str:
    """ "내일 11/20(금) · 부산본점 · 20시 이후" — 화면이 「{label} 기준」으로 보여 준다."""
    kinds = set(kinds)
    shown = {kind: values.get(kind) for kind in KINDS} | _time_of(values)
    parts = []
    for kind in _LABEL_ORDER:
        value = shown.get(kind)
        if kind not in kinds or not value:
            continue
        if kind == "date":
            parts.append(day_label(value, today))
        elif kind == "time":
            parts.append(time_label(value, shown["timeDirection"]))
        else:
            parts.append(str(value))
    return " · ".join(parts)


def subject(plan: Plan, today: date) -> str:
    """이어받은 답 문장 앞부분("오늘 11/19(목) 건대입구 20시 이후 부활남 회차")."""
    return label(plan.entry_values, _LABEL_ORDER, today).replace(" · ", " ") + " 회차"


def response(entry: Mapping[str, Any], inherited: Sequence[str], today: date) -> dict[str, Any]:
    """계약 ChatResponse.context. label = 이어받았으면 이어받은 조건, 아니면 이 답의 조건."""
    return {
        "id": entry["id"],
        **{kind: entry.get(kind) for kind in KINDS},
        **_time_of(entry),
        "label": label(entry, inherited or _LABEL_ORDER, today),
        "inherited": list(inherited),
    }
