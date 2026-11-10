"""평가 세트(eval/) 자체가 맞는지 — 정답 SQL·함정 쌍·실행기 (3.4.11). Claude는 부르지 않는다."""

from pathlib import Path

import pytest
from eval.capture import load_store
from eval.run import load_questions, run

from seatsac_ai.cache import template as tpl
from seatsac_ai.sql.validator import filters_started, validate

EVAL = Path(__file__).parent.parent / "eval"
QUESTIONS, PARAPHRASES = load_questions()


def test_set_size_and_ids_are_unique():
    ids = [q.id for q in QUESTIONS]
    assert 20 <= len(ids) <= 30 and len(set(ids)) == len(ids)
    assert all(qid in ids for qid in PARAPHRASES)


@pytest.mark.parametrize("q", [q for q in QUESTIONS if q.gold], ids=lambda q: q.id)
def test_gold_sql_passes_the_validator_and_returns_rows(q):
    store, now = load_store(EVAL / "snapshot.json")
    params = tpl.system_params(now.date(), f"{now:%Y-%m-%d %H:%M}")
    validated = validate(q.gold)
    if "screening" in validated.tables:
        assert filters_started(q.gold), "회차를 읽으면 시작한 회차를 빼야 한다(data-model 6-1)"
    with store.reader() as conn:
        cur = conn.execute(q.gold, params)
        columns = [d[0] for d in cur.description]
        rows = cur.fetchall()
    assert q.key in columns
    assert rows, "정답이 0행이면 맞음·틀림을 가르지 못한다"


def test_runner_with_gold_answers_has_no_wrong_cache_hits():
    report = run("fake", semantic=False, seed=17)
    assert all(o.correct for o in report.accuracy)
    for results in report.scenarios.values():
        hits = [o for o in results if o.path.startswith("CACHE_")]
        assert hits and all(o.correct for o in hits)
    assert all(b.correct for _, b in report.traps)
