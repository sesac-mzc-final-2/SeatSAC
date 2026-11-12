"""평가 세트(eval/) 자체가 맞는지 — 정답 SQL·함정 쌍·실행기 (3.4.11). Claude는 부르지 않는다."""

import re

import pytest
from eval.capture import DEFAULT_SNAPSHOT, load_store
from eval.run import TRAPS, load_questions, run

from seatsac_ai.cache import template as tpl
from seatsac_ai.sql.validator import validate

QUESTIONS, PARAPHRASES = load_questions()
GOLD = [q for q in QUESTIONS if q.gold]
# 스냅샷(롯데 편성에서 나온 회차)은 레포 밖에 둔다(#97 리뷰). 없으면 데이터가 필요한 검사만 건너뛴다
needs_snapshot = pytest.mark.skipif(
    not DEFAULT_SNAPSHOT.is_file(), reason=f"평가 스냅샷 없음({DEFAULT_SNAPSHOT})"
)


def test_set_size_and_ids_are_unique():
    ids = [q.id for q in QUESTIONS]
    assert 20 <= len(ids) <= 30 and len(set(ids)) == len(ids)
    assert all(qid in ids for qid in PARAPHRASES)


@pytest.mark.parametrize("q", GOLD, ids=lambda q: q.id)
def test_gold_sql_passes_the_validator(q):
    validated = validate(q.gold)
    if "screening" in validated.tables:
        assert re.search(r"starts_at\s*>\s*:now", q.gold), (
            "회차를 읽으면 시작한 회차를 빼야 한다(data-model 6-1)"
        )


def test_traps_point_at_real_questions():
    ids = {q.id for q in QUESTIONS}
    assert all(first in ids and second in ids for first, second in TRAPS)


@needs_snapshot
@pytest.mark.parametrize("q", GOLD, ids=lambda q: q.id)
def test_gold_sql_returns_rows(q):
    store, now = load_store(DEFAULT_SNAPSHOT)
    params = tpl.system_params(now.date(), f"{now:%Y-%m-%d %H:%M}")
    with store.reader() as conn:
        cur = conn.execute(q.gold, params)
        columns = [d[0] for d in cur.description]
        rows = cur.fetchall()
    assert q.key in columns
    assert rows, "정답이 0행이면 맞음·틀림을 가르지 못한다"


@needs_snapshot
def test_q09_and_q26_have_different_answers():
    """#97 리뷰: 날짜를 빠뜨린 캐시 답(q09 결과)이 q26에서 맞음으로 채점되면 안 된다."""
    store, now = load_store(DEFAULT_SNAPSHOT)
    params = tpl.system_params(now.date(), f"{now:%Y-%m-%d %H:%M}")
    by_id = {q.id: q for q in QUESTIONS}
    with store.reader() as conn:
        answers = [set(conn.execute(by_id[i].gold, params).fetchall()) for i in ("q09", "q26")]
    assert answers[0] != answers[1]


@needs_snapshot
def test_runner_with_gold_answers_has_no_wrong_cache_hits():
    report = run("fake", semantic=False, seed=17)
    assert all(o.correct for o in report.accuracy)
    for results in report.scenarios.values():
        hits = [o for o in results if o.path.startswith("CACHE_")]
        assert hits and all(o.correct for o in hits)
    assert all(b.correct for _, b in report.traps)
