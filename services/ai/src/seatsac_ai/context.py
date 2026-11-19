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
한계: 시각·개수 조건은 이어받지 않는다("오늘 20시 이후" 답에서 영화를 누르면 오늘 그 영화 전체).
"""

from __future__ import annotations

import secrets
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from functools import lru_cache
from typing import Any

from seatsac_ai.cache.slots import RELATIVE_DAYS, extract, find_name
from seatsac_ai.cache.warm import WarmItem, preset_items
from seatsac_ai.resources import Preset, context_templates
from seatsac_ai.sql.conditions import Conditions

KINDS = ("date", "theater", "movie")
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
    warm: WarmItem  # 조건이 다 찬 틀의 캐시 키·항목. 원래의 짧은 메시지 키는 쓰지 않는다


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
    return {"id": context_id} | {kind: values.get(kind) for kind in KINDS}


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
    template = _TEMPLATE_OF[frozenset(values)]
    warm = _warm_item(template.preset_id)
    return Plan(template, values, tuple(inherited), _sentence(template, values), warm)


def _still_valid(kind: str, value: object, today: date, theaters: Sequence[str]) -> bool:
    if not isinstance(value, str) or not value:
        return False
    if kind == "date":
        day = _iso_day(value)
        return day is not None and date.fromisoformat(day) >= today
    return value in theaters


_TEMPLATE_OF = {frozenset(t.params): t for t in context_templates()}


@lru_cache
def _warm_item(template_id: str) -> WarmItem:
    """빈칸이 모두 있는 첫 말투의 예열 항목(context_templates.yaml 규칙)."""
    template = next(t for t in context_templates() if t.preset_id == template_id)
    return preset_items((template,))[0]


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


def label(values: Mapping[str, Any], kinds: Iterable[str], today: date) -> str:
    """ "내일 11/20(금) · 부산본점" — 화면이 「{label} 기준」으로 보여 준다."""
    parts = []
    for kind in KINDS:
        value = values.get(kind)
        if kind in kinds and value:
            parts.append(day_label(value, today) if kind == "date" else str(value))
    return " · ".join(parts)


def subject(plan: Plan, today: date) -> str:
    """이어받은 답 문장 앞부분("내일 11/20(금) 부산본점 부활남 회차")."""
    return label(plan.values, KINDS, today).replace(" · ", " ") + " 회차"


def response(entry: Mapping[str, Any], inherited: Sequence[str], today: date) -> dict[str, Any]:
    """계약 ChatResponse.context. label = 이어받았으면 이어받은 조건, 아니면 이 답의 조건."""
    return {
        "id": entry["id"],
        **{kind: entry.get(kind) for kind in KINDS},
        "label": label(entry, inherited or KINDS, today),
        "inherited": list(inherited),
    }
