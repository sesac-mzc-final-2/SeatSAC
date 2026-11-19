"""SQL 검증기 (WBS 3.4.2 · AI-06 · 기능명세서 v4 16장).

Claude가 만든 SQL을 실행 전에 sqlglot(SQLite 방언)으로 **구문 트리**로 읽어 검사한다.
글자 검사(정규식)로는 주석·따옴표·대소문자로 쉽게 속일 수 있어서 쓰지 않는다.

이 검증은 1차 방어선이다. 최종 방어선은 스냅샷 연결 자체가 읽기 전용으로 잠겨 있다는 것
(snapshot/store.py authorizer)이다. 그래서 여기서는 "위험한 것을 놓치지 않는 것"과
"정상 질문을 막지 않는 것"의 균형을 잡는다 — 함수는 허용 목록이 아니라 금지 목록으로 본다.
"""

from __future__ import annotations

from dataclasses import dataclass

import sqlglot
from sqlglot import exp
from sqlglot.errors import SqlglotError

from seatsac_ai.common.metrics import AI_REJECTED
from seatsac_ai.snapshot.schema import QUERYABLE_TABLES

# 거부 사유 (계약 ai.openapi.yaml Rejection.reason)
NOT_SELECT = "NOT_SELECT"
MULTI_STATEMENT = "MULTI_STATEMENT"
FORBIDDEN_OBJECT = "FORBIDDEN_OBJECT"
FORBIDDEN_FUNCTION = "FORBIDDEN_FUNCTION"
PARSE_ERROR = "PARSE_ERROR"

# 문장 자체가 DB 설정·연결을 바꾸는 것
_FORBIDDEN_STATEMENTS = (exp.Pragma, exp.Attach, exp.Detach)
# 파일 접근·확장 로드·메모리 폭주 함수. 소문자로 비교
_FORBIDDEN_FUNCTIONS = frozenset(
    {
        "load_extension",
        "readfile",
        "writefile",
        "edit",
        "randomblob",
        "zeroblob",
        "fts3_tokenizer",
    }
)
_ALLOWED_TABLES = frozenset(QUERYABLE_TABLES)


class SqlRejected(Exception):
    """검증 거부. `reason`은 계약의 Rejection.reason, `message`는 Claude 재요청에도 쓴다."""

    def __init__(self, reason: str, message: str, obj: str | None = None) -> None:
        super().__init__(f"{reason}: {message}")
        self.reason = reason
        self.message = message
        self.object = obj


@dataclass(frozen=True)
class ValidatedSql:
    sql: str  # 실행할 SQL(LIMIT 적용 후, SQLite 방언으로 다시 쓴 것)
    tables: frozenset[str]  # 읽는 표
    limit_capped: bool  # 우리가 LIMIT을 붙이거나 낮췄다 → 결과가 상한에 닿으면 truncated


def validate(sql: str, max_rows: int = 500) -> ValidatedSql:
    """통과하면 실행할 SQL을, 아니면 SqlRejected를 낸다(거부 지표도 센다)."""
    try:
        return _validate(sql, max_rows)
    except SqlRejected as rejected:
        AI_REJECTED.labels(rejected.reason).inc()
        raise


def _validate(sql: str, max_rows: int) -> ValidatedSql:
    try:
        statements = [s for s in sqlglot.parse(sql, read="sqlite") if s is not None]
    except SqlglotError as exc:
        raise SqlRejected(PARSE_ERROR, f"SQL을 해석할 수 없습니다: {exc}") from exc
    if not statements:
        raise SqlRejected(PARSE_ERROR, "SQL이 비어 있습니다")
    if len(statements) > 1:
        raise SqlRejected(MULTI_STATEMENT, "SQL은 한 문장만 쓸 수 있습니다")

    tree = statements[0]
    if isinstance(tree, _FORBIDDEN_STATEMENTS):
        raise SqlRejected(FORBIDDEN_FUNCTION, f"{tree.key.upper()} 문은 쓸 수 없습니다")
    if not isinstance(tree, exp.Query):
        raise SqlRejected(NOT_SELECT, "SELECT 문만 쓸 수 있습니다")

    _check_recursive(tree)
    _check_functions(tree)
    tables = _check_tables(tree)
    capped = _apply_limit(tree, max_rows)
    return ValidatedSql(sql=tree.sql(dialect="sqlite"), tables=tables, limit_capped=capped)


def exclude_started(query: ValidatedSql) -> ValidatedSql:
    """screening 을 읽는 모든 SELECT에 `<별칭>.starts_at > :now` 를 붙인 SQL (data-model 6-1).

    Claude가 조건을 빼먹거나 지금보다 이른 시각과 비교해도(예: 21:30에 `>= '20:00'`)
    이미 시작한 회차가 답에 나오지 않는다. FROM 의 screening 은 WHERE 에, JOIN 의 screening 은
    ON 에 AND 로 붙인다(LEFT JOIN 의미를 바꾸지 않게). 실행할 때 :now 를 넘겨야 한다.
    """
    if "screening" not in query.tables:
        return query
    tree = sqlglot.parse_one(query.sql, read="sqlite")
    for select in list(tree.find_all(exp.Select)):
        from_ = select.args.get("from_")
        if from_ and _is_screening(from_.this):
            select.where(_not_started(from_.this), append=True, copy=False)
        for join in select.args.get("joins") or []:
            if _is_screening(join.this):
                on = join.args.get("on")
                cond = _not_started(join.this)
                join.set("on", exp.and_(on, cond) if on else cond)
    return ValidatedSql(
        sql=tree.sql(dialect="sqlite"), tables=query.tables, limit_capped=query.limit_capped
    )


# 회차 ID를 얻으려고 서버가 덧붙이는 칸 이름.
# 응답의 columns·rows 에는 나가지 않는다(계약 screeningIds)
SCREENING_ID_COLUMN = "_screening_id"
# sqlglot 이 집계로 알아보지 못하는 SQLite 집계 함수(이름으로 본다). 소문자로 비교
_SQLITE_AGGREGATES = frozenset(
    {"total", "group_concat", "string_agg", "json_group_array", "json_group_object"}
)


def with_screening_id(query: ValidatedSql) -> ValidatedSql | None:
    """결과 행 하나가 회차 하나인 SQL이면 `<별칭>.screening_id AS _screening_id` 를 끝에 붙인다.

    화면이 그 행의 좌석 선택 화면으로 넘어가려면 회차 ID가 필요하다. Claude·프리셋의 SQL은
    그대로 두고(프롬프트·캐시 템플릿이 바뀌지 않게) 실행할 때만 붙인다
    — exclude_started 와 같은 방식. 붙인 칸은 항상 결과의 마지막 칸이다.
    행과 회차가 1:1 이고 표의 시작 시각이 그 회차의 것이라고 볼 수 없으면 None(붙이지 않는다):
      - 바깥 문장이 UNION 등이거나, DISTINCT·GROUP BY·HAVING·집계 함수가 있다
      - 바깥 FROM·JOIN 에 screening 이 없거나(서브쿼리·CTE 뒤에 숨음) 두 번 나온다
      - 결과에 그 screening 의 starts_at 칸이 그대로 나오지 않는다 — 칸이 없거나,
        다른 것에 starts_at 이름을 붙였거나(s.ends_at AS starts_at · CTE 의 min(starts_at)),
        `*` 가 서브쿼리·CTE 와 섞여 어느 표의 칸인지 알 수 없다
      - 결과 칸 이름이 이미 _screening_id 다
    한계(안전한 쪽으로 None): 창 함수의 FILTER, 스칼라 서브쿼리 안의 집계도 집계로 본다.
    칸을 끝에 붙이므로 ORDER BY 2 같은 위치 참조와 LIMIT 은 그대로다.
    """
    if "screening" not in query.tables:
        return None
    try:
        tree = sqlglot.parse_one(query.sql, read="sqlite")
    except SqlglotError:
        return None  # 덧붙이지 않고 그대로 실행한다
    if not isinstance(tree, exp.Select):
        return None
    if any(tree.args.get(key) for key in ("distinct", "group", "having")):
        return None
    from_ = tree.args.get("from_")
    sources = [from_.this] if from_ else []
    sources += [join.this for join in tree.args.get("joins") or []]
    screenings = [source for source in sources if _is_screening(source)]
    if len(screenings) != 1:
        return None
    alias = screenings[0].alias_or_name
    others = [source for source in sources if source is not screenings[0]]
    if not _shows_its_start(tree.expressions, alias, others):
        return None
    column = exp.column("screening_id", table=alias)
    tree.select(exp.alias_(column, SCREENING_ID_COLUMN), append=True, copy=False)
    return ValidatedSql(
        sql=tree.sql(dialect="sqlite"), tables=query.tables, limit_capped=query.limit_capped
    )


def _shows_its_start(
    projections: list[exp.Expression], alias: str, others: list[exp.Expression]
) -> bool:
    """결과에 바깥 screening(alias)의 starts_at 이 그대로 나오고, 행 수를 줄이는 칸이 없다."""
    # movie·theater 에는 starts_at 이 없다. 서브쿼리·CTE 는 무슨 칸이 있는지 모른다
    plain_tables = all(
        isinstance(o, exp.Table) and o.name.lower() not in _ctes_in_scope(o) for o in others
    )
    shown = False
    for projection in projections:
        if _aggregates(projection):
            return False
        value = projection.unalias()
        star = isinstance(value, exp.Star) or (
            isinstance(value, exp.Column) and isinstance(value.this, exp.Star)
        )
        if star:
            if not plain_tables:
                return False
            # `*` 는 모든 표, `s.*` 는 그 표의 칸
            table = value.table if isinstance(value, exp.Column) else alias
            shown = shown or table.lower() == alias.lower()
            continue
        name = projection.alias_or_name.lower()
        if name == SCREENING_ID_COLUMN:
            return False
        if name != "starts_at":
            continue
        own = (
            isinstance(value, exp.Column)
            and value.name.lower() == "starts_at"
            and value.table.lower() in ("", alias.lower())
        )
        if not own:
            return False
        shown = True
    return shown


def _aggregates(projection: exp.Expression) -> bool:
    # sum(...) OVER (...) 같은 창 함수는 행 수를 줄이지 않는다
    for func in projection.find_all(exp.AggFunc, exp.Anonymous):
        known = isinstance(func, exp.AggFunc) or func.name.lower() in _SQLITE_AGGREGATES
        if known and not isinstance(func.parent, exp.Window):
            return True
    return False


def _is_screening(node: exp.Expression) -> bool:
    # 같은 이름의 CTE 는 실제 표가 아니다(그 CTE 안의 SELECT 가 따로 조건을 받는다)
    return (
        isinstance(node, exp.Table)
        and node.name.lower() == "screening"
        and not node.db
        and node.name.lower() not in _ctes_in_scope(node)
    )


def _not_started(table: exp.Table) -> exp.Expression:
    return exp.GT(
        this=exp.column("starts_at", table=table.alias_or_name),
        expression=exp.Placeholder(this="now"),
    )


def sql_template(sql: str) -> str | None:
    """로그용: 값(문자열·숫자)을 ?로 바꾼 SQL. 질문에 든 개인정보가 SQL 값으로 남지 않게 한다."""
    try:
        tree = sqlglot.parse_one(sql, read="sqlite")
    except SqlglotError:
        return None
    return tree.transform(
        lambda node: exp.Placeholder() if isinstance(node, exp.Literal) else node
    ).sql(dialect="sqlite")


def _check_recursive(tree: exp.Expression) -> None:
    # 재귀 CTE는 끝없이 행을 만들 수 있다(5초 중단이 있어도 처음부터 막는다)
    for with_ in tree.find_all(exp.With):
        if with_.args.get("recursive"):
            raise SqlRejected(FORBIDDEN_FUNCTION, "재귀 WITH는 쓸 수 없습니다")


def _check_functions(tree: exp.Expression) -> None:
    for func in tree.find_all(exp.Func):
        name = (func.name if isinstance(func, exp.Anonymous) else func.sql_name()).lower()
        if name in _FORBIDDEN_FUNCTIONS or name.startswith(("sqlite_", "pragma_")):
            raise SqlRejected(FORBIDDEN_FUNCTION, f"함수 {name}는 쓸 수 없습니다", name)


def _ctes_in_scope(table: exp.Table) -> set[str]:
    """이 표 위치에서 보이는 WITH 이름만.

    문장 전체에서 모으면 안쪽 서브쿼리의 WITH 이름으로 바깥의 sqlite_master 를 가릴 수 있다
    (#91 리뷰: FROM (WITH sqlite_master AS …), sqlite_master).
    """
    names: set[str] = set()
    node = table.parent
    while node is not None:
        with_ = node.args.get("with") or node.args.get("with_")
        if isinstance(with_, exp.With):
            names |= {cte.alias_or_name.lower() for cte in with_.expressions}
        node = node.parent
    return names


def _check_tables(tree: exp.Expression) -> frozenset[str]:
    used: set[str] = set()
    for table in tree.find_all(exp.Table):
        if not isinstance(table.this, exp.Identifier):
            # FROM json_each(...) · pragma_table_info(...) 같은 표 함수
            raise SqlRejected(FORBIDDEN_FUNCTION, "FROM에는 표 이름만 쓸 수 있습니다")
        name = table.name.lower()
        db = table.db.lower()
        if db and db != "main":
            raise SqlRejected(FORBIDDEN_OBJECT, f"{db}.{name}은 조회할 수 없습니다", f"{db}.{name}")
        if not db and name in _ctes_in_scope(table):
            continue  # 이 위치에서 보이는 WITH 임시 이름
        if name not in _ALLOWED_TABLES:
            raise SqlRejected(FORBIDDEN_OBJECT, f"{name} 표는 조회할 수 없습니다", name)
        used.add(name)
    return frozenset(used)


def _apply_limit(tree: exp.Query, max_rows: int) -> bool:
    """바깥 문장의 LIMIT을 max_rows 이하로 맞춘다. 붙이거나 낮췄으면 True."""
    limit = tree.args.get("limit")
    if limit is None:
        tree.set("limit", exp.Limit(expression=exp.Literal.number(max_rows)))
        return True
    value = limit.expression
    if isinstance(value, exp.Literal) and value.is_int:
        if int(value.this) <= max_rows:
            return False
        limit.set("expression", exp.Literal.number(max_rows))
        return True
    # LIMIT :n 같은 식 → max(0, min(식, 500)). 값이 작으면 그대로 쓰이고,
    # 음수(SQLite에서 "상한 없음")는 0이 된다
    limit.set(
        "expression",
        exp.Anonymous(
            this="max",
            expressions=[
                exp.Literal.number(0),
                exp.Anonymous(
                    this="min", expressions=[value.copy(), exp.Literal.number(max_rows)]
                ),
            ],
        ),
    )
    return True
