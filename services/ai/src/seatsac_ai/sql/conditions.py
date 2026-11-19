"""SQL 조건 분석기 — 검증을 통과한 SELECT가 「어느 날짜·극장·영화를 조회하는지」 읽는다.

SQL 검증기(validator.py)는 「위험한가」만 본다. 이 모듈은 「무엇을 조회하는가」를 읽어 세 곳에 쓴다.

  1. 질문 시점 잔여석 갱신: 필요한 날짜 × 극장만 공개 API에서 새로 받는다(snapshot/fresh.py)
  2. 의미 검사: 질문에서 뽑은 날짜·극장·영화가 SQL 조건과 같은지(orchestrator)
  3. 되묻기: 잔여석을 읽는데 날짜 조건이 없으면 실행하지 않고 날짜를 묻는다(orchestrator)

읽는 조건은 WHERE·JOIN ON 의 맨 바깥 AND 로 이어진 것만이다. OR·NOT 안의 조건, 범위 비교(>=)는
「조회 대상을 확정하지 못함」으로 보고 그 종류를 None 으로 둔다 — 모르면 막지 않고, 막을 때는
확실할 때만 막는다. 칸 이름은 표마다 겹치지 않아(business_date·title·name) 별칭 없이 이름으로 본다.
"""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from dataclasses import dataclass
from typing import Any

import sqlglot
from sqlglot import exp

from seatsac_ai.sql.validator import ValidatedSql

SEAT_COLUMN = "remaining_seats"
_SEAT_COLUMNS = frozenset({SEAT_COLUMN, "remaining_seats_source"})

# 조건 종류 → 그 종류를 뜻하는 칸 (값으로 이름을 쓰는 칸 · id 칸)
_NAME_COLUMNS = {"date": "business_date", "theater": "name", "movie": "title"}
_ID_COLUMNS = {"theater": "theater_id", "movie": "movie_id"}


@dataclass(frozen=True)
class Match:
    """조건 하나의 값. like=True 면 LIKE '%값%' 의 % 를 뗀 글자(포함 여부로 비교)."""

    value: str
    like: bool = False

    def accepts(self, wanted: str) -> bool:
        return wanted in self.value or self.value in wanted if self.like else self.value == wanted


@dataclass(frozen=True)
class Conditions:
    """확정 조건(맨 바깥 AND 의 = · IN · LIKE)과, 조건 칸이 어디든 나오기는 했는지(referenced).

    dates 등이 None 이면 확정 조건이 없다.
    그때 referenced 에 그 종류가 있으면 「OR·범위라 읽지 못함」,
    없으면 「조건 자체가 없음」이다.
    """

    dates: frozenset[str] | None
    theaters: frozenset[Match] | None
    theater_ids: frozenset[int] | None
    movies: frozenset[Match] | None
    referenced: frozenset[str]  # WHERE·ON 어딘가에 칸이 나온 종류(date·theater·movie)
    reads_seats: bool  # 잔여석 칸(또는 *)을 어디서든 읽는다
    seat_outputs: frozenset[str]  # 결과 칸 중 잔여석 값이 나오는 칸 이름(별칭)
    seat_aggregate: bool = (
        False  # sum(remaining_seats) 처럼 묶어 센다 — 확인 불가(NULL) 행이 빠진다
    )

    def has_date(self, day: str) -> bool | None:
        """True 일치 · False 빠졌거나 다름 · None 읽지 못해 판단 보류."""
        if self.dates is not None:
            return day in self.dates
        return None if "date" in self.referenced else False

    def has_theater(self, name: str, theater_id: int | None = None) -> bool | None:
        if self.theaters is not None and any(m.accepts(name) for m in self.theaters):
            return True
        if self.theater_ids is not None and theater_id is not None:
            return theater_id in self.theater_ids
        if self.theaters is not None or self.theater_ids is not None:
            return False
        return None if "theater" in self.referenced else False

    def has_movie(self, title: str) -> bool | None:
        if self.movies is not None:
            return any(m.accepts(title) for m in self.movies)
        return None if "movie" in self.referenced else False


def analyze(query: ValidatedSql, params: Mapping[str, Any] | None = None) -> Conditions:
    """검증을 통과한 SQL(+ :빈칸 값)에서 조회 조건을 읽는다. 읽지 못한 종류는 None."""
    tree = sqlglot.parse_one(query.sql, read="sqlite")
    params = params or {}
    found: dict[str, set[Any]] = {
        "date": set(),
        "theater": set(),
        "theater_id": set(),
        "movie": set(),
    }
    referenced: set[str] = set()
    for select in tree.find_all(exp.Select):
        for predicate in _top_level_predicates(select):
            _collect(predicate, params, found)
            referenced |= _kinds_in(predicate)
    return Conditions(
        dates=frozenset(found["date"]) or None,
        theaters=frozenset(found["theater"]) or None,
        theater_ids=frozenset(found["theater_id"]) or None,
        movies=frozenset(found["movie"]) or None,
        referenced=frozenset(referenced),
        reads_seats=_reads_seats(tree),
        seat_outputs=_seat_outputs(tree),
        seat_aggregate=_seat_aggregate(tree),
    )


def _top_level_predicates(select: exp.Select) -> Iterator[exp.Expression]:
    where = select.args.get("where")
    if where is not None:
        yield from _flatten_and(where.this)
    for join in select.args.get("joins") or []:
        on = join.args.get("on")
        if on is not None:
            yield from _flatten_and(on)


def _flatten_and(node: exp.Expression) -> Iterator[exp.Expression]:
    node = node.unnest() if isinstance(node, exp.Paren) else node
    if isinstance(node, exp.And):
        yield from _flatten_and(node.left)
        yield from _flatten_and(node.right)
    else:
        yield node


def _collect(predicate: exp.Expression, params: Mapping[str, Any], found: dict[str, set]) -> None:
    if isinstance(predicate, exp.EQ):
        column, value = _column_and_other(predicate.left, predicate.right)
        if column is None:
            return
        literal = _value(value, params)
        if literal is not None:
            _add(column, [literal], like=False, found=found)
    elif isinstance(predicate, exp.In) and isinstance(predicate.this, exp.Column):
        values = [_value(v, params) for v in predicate.expressions]
        if values and all(v is not None for v in values):
            _add(predicate.this, values, like=False, found=found)
    elif isinstance(predicate, exp.Like) and isinstance(predicate.this, exp.Column):
        literal = _value(predicate.expression, params)
        if literal is not None:
            _add(predicate.this, [str(literal).strip("%")], like=True, found=found)


def _column_and_other(
    left: exp.Expression, right: exp.Expression
) -> tuple[exp.Column | None, exp.Expression]:
    if isinstance(left, exp.Column) and not isinstance(right, exp.Column):
        return left, right
    if isinstance(right, exp.Column) and not isinstance(left, exp.Column):
        return right, left
    return None, right


def _value(node: exp.Expression, params: Mapping[str, Any]) -> Any:
    """리터럴 또는 :빈칸 값. 식(:today || ' ' || :time 등)은 읽지 않는다."""
    if isinstance(node, exp.Literal):
        return int(node.this) if node.is_int else node.this
    if isinstance(node, exp.Placeholder) and node.name in params:
        return params[node.name]
    return None


def _add(column: exp.Column, values: list[Any], like: bool, found: dict[str, set]) -> None:
    name = column.name.lower()
    if name == _NAME_COLUMNS["date"]:
        found["date"].update(str(v) for v in values)
    elif name == _NAME_COLUMNS["theater"]:
        found["theater"].update(Match(str(v), like) for v in values)
    elif name == _NAME_COLUMNS["movie"]:
        found["movie"].update(Match(str(v), like) for v in values)
    elif name == _ID_COLUMNS["theater"] and not like:
        found["theater_id"].update(int(v) for v in values if str(v).isdigit())


_KIND_OF_COLUMN = {
    # starts_at 은 넣지 않는다 — 모든 SQL에 붙는 「starts_at > 지금」은 사용자가 말한 날짜가 아니다.
    # 날짜는 business_date 로 거른다(schema.yaml 규칙). starts_at 으로만 걸렀으면 「날짜 조건 없음」
    "business_date": "date",
    "name": "theater",
    "theater_id": "theater",
    "title": "movie",
    "movie_id": "movie",
}


def _kinds_in(predicate: exp.Expression) -> set[str]:
    """조건 안에 나온 칸의 종류. JOIN 의 id = id 연결은 조건이 아니라서 뺀다."""
    if isinstance(predicate, exp.EQ) and all(
        isinstance(side, exp.Column) for side in (predicate.left, predicate.right)
    ):
        return set()
    return {
        _KIND_OF_COLUMN[c.name.lower()]
        for c in predicate.find_all(exp.Column)
        if c.name.lower() in _KIND_OF_COLUMN
    }


def _reads_seats(tree: exp.Expression) -> bool:
    for node in tree.walk():
        # SELECT * · s.* 만 「모든 칸」이다. count(*) 의 * 는 칸을 읽지 않는다
        if isinstance(node, exp.Star) and isinstance(node.parent, exp.Select | exp.Column):
            return True
        if isinstance(node, exp.Column) and node.name.lower() in _SEAT_COLUMNS:
            return True
    return False


def _seat_outputs(tree: exp.Expression) -> frozenset[str]:
    """바깥 SELECT의 결과 칸 중 remaining_seats 값을 담는 칸 이름(별칭)."""
    if not isinstance(tree, exp.Select):
        tree = tree.find(exp.Select) or tree
    names: set[str] = set()
    for expression in getattr(tree, "expressions", []):
        if isinstance(expression, exp.Star) or (
            isinstance(expression, exp.Column) and isinstance(expression.this, exp.Star)
        ):
            names.add(SEAT_COLUMN)
            continue
        if any(c.name.lower() == SEAT_COLUMN for c in expression.find_all(exp.Column)):
            # 별칭이 없는 식(sum(remaining_seats))은 SQLite가 SQL 글자 그대로 칸 이름을 붙인다
            names.add(expression.alias_or_name or expression.sql(dialect="sqlite"))
    return frozenset(names)


def _seat_aggregate(tree: exp.Expression) -> bool:
    return any(
        any(c.name.lower() == SEAT_COLUMN for c in agg.find_all(exp.Column))
        for agg in tree.find_all(exp.AggFunc)
    )
