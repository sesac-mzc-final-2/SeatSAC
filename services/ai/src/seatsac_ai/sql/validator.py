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
    """이 표 위치에서 보이는 WITH 이름만. 문장 전체에서 모으면 안쪽 서브쿼리의 WITH 이름으로
    바깥의 sqlite_master 를 가릴 수 있다(#91 리뷰: FROM (WITH sqlite_master AS …), sqlite_master)."""
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
