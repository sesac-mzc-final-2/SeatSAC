"""SQL 템플릿 만들기·되돌리기 (WBS 3.4.5).

캐시에는 결과가 아니라 **SQL 템플릿**을 저장한다
(conventions 4장: 결과는 캐시하지 않고 스냅샷에서 다시 실행).

  Claude가 쓴 SQL  ... business_date = '2026-11-17' AND starts_at >= '2026-11-17 20:00'
                       AND starts_at > '2026-11-17 19:40'
  템플릿            ... business_date = :today AND starts_at >= (:today || ' ' || :time)
                       AND starts_at > :now

바꾸는 규칙 (값이 어디서 왔는지 설명되는 것만)
  - '지금'과 같은 값            → :now
  - 날짜: 슬롯 date와 같으면    → :date
          오늘+k일(질문에 "오늘·내일·모레"가 있거나 k=0) → :today · :d1 · :d2 · :d3
          그 밖의 날짜          → 템플릿 실패(캐시 안 함)
  - 위 두 형식이 아닌 날짜가 든 문자열('…:00'·'…T…'·'2026-11-17%')·지금 시각 'HH:MM' → 템플릿 실패
                                   — "이번 주말"처럼 뜻을 모르는 날짜를 굳혀 두지 않게
  - 'YYYY-MM-DD HH:MM'         → (날짜 || ' ' || 시각) 으로 나눠 위 규칙
  - 슬롯 time·theater·movie와 같은 값 → :time · :theater · :movie ('%건대%' 처럼 일부여도)
  - 정수: start_hour 비교의 시각 → CAST(substr(:time, 1, 2) AS INTEGER), 슬롯 n과 같으면 → :n

질문의 슬롯이 SQL 어디에도 쓰이지 않으면 템플릿 실패다.
예: 질문은 "강남"인데 Claude가 '%강남구%'로 썼다면
값을 바꿔 다시 써도 같은 결과가 안 나온다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

import sqlglot
from sqlglot import exp
from sqlglot.errors import SqlglotError

from seatsac_ai.cache.slots import Extracted

_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_DATETIME = re.compile(r"^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})$")
_HAS_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
_HANGUL = re.compile(r"[가-힣]")
MAX_OFFSET = 3  # :d1 ~ :d3 (오늘 = :today)

# 의미 캐시 오적중 막기: 템플릿에 남은 enum 코드 → 새 질문에 있어야 할 말 (schema.yaml enums)
_ENUM_WORDS = {
    "NORMAL": "일반",
    "RECLINER": "리클라이너",
    "ALL": "전체",
    "12": "12",
    "15": "15",
    "19": "19",
}
_INTERNAL_CODES = frozenset({"BOOKING", "TOTAL", "NOW_SHOWING"})  # 질문에서 오지 않는 값


class TemplateError(ValueError):
    """이 SQL은 캐시할 수 없다(값의 출처를 설명할 수 없음)."""


@dataclass(frozen=True)
class SqlTemplate:
    sql: str  # 자리표시자(:today·:time …)가 들어간 SQL
    anchors: tuple[str, ...]  # 의미 캐시 적중 때 새 질문에 있어야 하는 말


def system_params(today: date, now_text: str) -> dict[str, str]:
    """캐시 SQL을 실행할 때 서버가 넣는 값. 프리셋의 :today·:now 와 같고 :d1~:d3 이 더 있다."""
    params = {"today": today.isoformat(), "now": now_text}
    for k in range(1, MAX_OFFSET + 1):
        params[f"d{k}"] = (today + timedelta(days=k)).isoformat()
    return params


def make_template(sql: str, ext: Extracted, today: date, now_text: str) -> SqlTemplate:
    try:
        tree = sqlglot.parse_one(sql, read="sqlite")
    except SqlglotError as exc:
        raise TemplateError(f"해석 실패: {exc}") from exc

    values = ext.values()
    offsets = ext.allowed_offsets()
    used: set[str] = set()
    anchors: list[str] = []

    def date_expr(text: str) -> exp.Expression:
        if values.get("date") == text:
            used.add("date")
            return exp.Placeholder(this="date")
        k = (date.fromisoformat(text) - today).days
        if k in offsets and 0 <= k <= MAX_OFFSET:
            return exp.Placeholder(this="today" if k == 0 else f"d{k}")
        raise TemplateError(f"설명할 수 없는 날짜 {text}")

    def time_expr(text: str) -> exp.Expression:
        if values.get("time") == text:
            used.add("time")
            return exp.Placeholder(this="time")
        anchors.append(text)
        return exp.Literal.string(text)

    def replace(node: exp.Expression) -> exp.Expression:
        if not isinstance(node, exp.Literal):
            return node
        text = node.this
        if node.is_string:
            if text == now_text:
                return exp.Placeholder(this="now")
            if _DATE.match(text):
                return date_expr(text)
            dt = _DATETIME.match(text)
            if dt:
                joined = exp.DPipe(
                    this=exp.DPipe(this=date_expr(dt.group(1)), expression=exp.Literal.string(" ")),
                    expression=time_expr(dt.group(2)),
                )
                return exp.Paren(this=joined)
            if _HAS_DATE.search(text):
                # '…23:40:00'·'…T23:40'·'2026-11-17%'처럼 위 두 형식이 아닌 날짜는 출처를 설명할 수
                # 없다. 굳혀 두면 TTL 안에 '지금'·'오늘'이 지난 값으로 실행된다(#94 리뷰)
                raise TemplateError(f"설명할 수 없는 날짜 형식 {text}")
            if text == now_text[11:] and values.get("time") != text:
                # substr(starts_at, 12, 5) > '23:40' 처럼 '지금'을 시각만으로 비교한 경우
                raise TemplateError(f"지금 시각을 굳힌 값 {text}")
            for kind in ("time", "theater", "movie"):
                slot = ext.slots.get(kind)
                if slot is None:
                    continue
                # 긴 것부터: '건대입구'를 앞부분 '건대'로 자르면 :theater || '입구' 가 되어 버린다
                for form in sorted({str(slot.value), slot.raw}, key=len, reverse=True):
                    if form and form in text:
                        used.add(kind)
                        return _split_around(text, form, kind)
            _add_anchor(anchors, text)
            return node
        # 숫자
        if node.is_int:
            n = int(text)
            if (
                "time" in values
                and _compares_start_hour(node)
                and n == int(str(values["time"])[:2])
            ):
                used.add("time")
                return exp.Cast(
                    this=exp.Anonymous(
                        this="substr",
                        expressions=[
                            exp.Placeholder(this="time"),
                            exp.Literal.number(1),
                            exp.Literal.number(2),
                        ],
                    ),
                    to=exp.DataType.build("INTEGER"),
                )
            if values.get("n") == n:
                used.add("n")
                return exp.Placeholder(this="n")
            if n not in (0, 1) and not isinstance(node.parent, exp.Limit):
                anchors.append(str(n))
        return node

    templated = tree.transform(replace)
    missing = set(values) - used
    if missing:
        raise TemplateError(f"SQL에 쓰이지 않은 슬롯 {sorted(missing)}")
    return SqlTemplate(sql=templated.sql(dialect="sqlite"), anchors=tuple(dict.fromkeys(anchors)))


def preset_template(
    preset_sql: str, preset_params: dict[str, Any], ext: Extracted, today: date
) -> SqlTemplate:
    """프리셋 선택을 SQL 템플릿으로.

    Claude가 고른 빈칸 값이 질문의 슬롯(또는 오늘·내일)과 같아야 한다.
    """
    values = ext.values()
    offsets = ext.allowed_offsets()
    rename: dict[str, str] = {}
    for name, given in preset_params.items():
        if values.get(name) == given:
            continue  # :time 은 :time 그대로
        if name == "date" and isinstance(given, str) and _DATE.match(given):
            k = (date.fromisoformat(given) - today).days
            if k in offsets and 0 <= k <= MAX_OFFSET:
                rename["date"] = "today" if k == 0 else f"d{k}"
                continue
        raise TemplateError(f"프리셋 빈칸 {name}={given!r}의 출처가 질문에 없다")
    if set(values) - set(preset_params):
        raise TemplateError("질문의 슬롯을 프리셋이 쓰지 않는다")
    try:
        tree = sqlglot.parse_one(preset_sql, read="sqlite")
    except SqlglotError as exc:
        raise TemplateError(str(exc)) from exc

    def swap(node: exp.Expression) -> exp.Expression:
        if isinstance(node, exp.Placeholder) and node.name in rename:
            return exp.Placeholder(this=rename[node.name])
        return node

    return SqlTemplate(sql=tree.transform(swap).sql(dialect="sqlite"), anchors=())


def render(sql: str, params: dict[str, Any]) -> str:
    """화면·응답용: 자리표시자를 실제 값으로 바꾼 SQL(실행은 자리표시자 그대로 바인딩한다)."""
    try:
        tree = sqlglot.parse_one(sql, read="sqlite")
    except SqlglotError:
        return sql

    def fill(node: exp.Expression) -> exp.Expression:
        if isinstance(node, exp.Placeholder) and node.name in params:
            value = params[node.name]
            return (
                exp.Literal.number(value)
                if isinstance(value, int)
                else exp.Literal.string(str(value))
            )
        return node

    return tree.transform(fill).sql(dialect="sqlite")


def anchors_present(anchors: tuple[str, ...], question: str) -> bool:
    plain = question.replace(" ", "")
    return all(a.replace(" ", "") in plain for a in anchors)


# --- 내부 ---------------------------------------------------------------------


def _split_around(text: str, form: str, kind: str) -> exp.Expression:
    """'%건대%' → ('%' || :theater || '%')."""
    before, _, after = text.partition(form)
    node: exp.Expression = exp.Placeholder(this=kind)
    if before:
        node = exp.DPipe(this=exp.Literal.string(before), expression=node)
    if after:
        node = exp.DPipe(this=node, expression=exp.Literal.string(after))
    return exp.Paren(this=node) if (before or after) else node


def _compares_start_hour(node: exp.Expression) -> bool:
    parent = node.parent
    if not isinstance(parent, exp.GT | exp.GTE | exp.LT | exp.LTE | exp.EQ | exp.Between):
        return False
    return any(
        isinstance(side, exp.Column) and side.name.lower() == "start_hour"
        for side in parent.iter_expressions()
    )


def _add_anchor(anchors: list[str], text: str) -> None:
    word = text.strip("%").strip()
    if not word or word in _INTERNAL_CODES:
        return
    if word in _ENUM_WORDS:
        anchors.append(_ENUM_WORDS[word])
    elif _HANGUL.search(word):
        anchors.append(word)
    else:
        anchors.append(word)  # 영어·숫자 값도 질문에 있어야 같은 질문으로 본다
