"""NL2SQL 정확도·토큰 절감 측정 (WBS 3.4.11 평가 세트 → 4.2.3 측정).

    cd services/ai
    uv run python -m eval.run --llm fake            # 비용 0: 정답 SQL을 돌려주는 가짜 Claude로 실행기 점검
    ANTHROPIC_API_KEY=... uv run python -m eval.run --llm claude   # 실제 측정(질문 약 120건)

무엇을 재나
  A. 정확도: 질문마다 캐시 없이 Claude로 답 → 정답 SQL 결과와 비교(핵심 칸 값 집합이 같으면 맞음)
  B. 반복 시나리오 30%·60%·80%: 질문 30개 중 그 비율만큼 "이미 물은 질문을 다시"(같은 글자·값만 다름·다른 말투)
     → 캐시를 켠 상태에서 토큰 합계와, A에서 잰 질문별 토큰으로 만든 "캐시 없을 때" 합계를 비교 = 절감률
  C. 오적중: 캐시 답(CACHE_*) 중 틀린 비율 + 일부러 만든 함정 쌍("20시"→"21시", "이후"→"전에", 리클라이너→일반관 …)

가정(보고서에도 적는다)
  - 기준 데이터는 eval/snapshot.json 하나(시각 고정). 실제 서비스는 1분마다 스냅샷이 바뀐다
  - 토큰 = input + output. 프롬프트 캐싱(cache_read)은 따로 적고 절감률에 넣지 않는다
  - 반복 시나리오의 "캐시 없을 때" 토큰은 A에서 그 질문(다른 말투면 원래 질문)에 쓴 토큰
  - 의미 캐시 임베딩은 실제 모델(fastembed). --no-semantic 이면 정확 일치만
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import fakeredis
import yaml

from eval.capture import load_store
from seatsac_ai.cache import template as tpl
from seatsac_ai.cache.store import ChatCache, MemoryIndex
from seatsac_ai.common.errors import ApiError
from seatsac_ai.config import Settings
from seatsac_ai.llm.client import ToolCall, Usage
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.snapshot.store import SnapshotStore

HERE = Path(__file__).parent
SCENARIO_LENGTH = 30
RATIOS = (0.3, 0.6, 0.8)
TRAPS = [  # (먼저, 나중) — 나중 질문이 앞 질문의 캐시를 잘못 쓰면 오적중
    ("q01", "q02"),  # 20시 → 21시 (값만 다름: 캐시를 쓰되 값을 바꿔야 맞음)
    ("q01", "q04"),  # 이후 → 전에
    ("q13", "q14"),  # 리클라이너 → 일반관
    ("q05", "q06"),  # 영등포 → 건대입구
    ("q01", "q05"),  # 오늘 → 내일 (전혀 다른 질문)
]


@dataclass
class Question:
    id: str
    family: str
    question: str
    gold: str | None = None
    key: str | None = None
    expect: str | None = None


@dataclass
class Outcome:
    id: str
    question: str
    path: str
    correct: bool
    tokens: int  # input + output
    cache_read: int
    calls: int
    detail: str = ""


@dataclass
class Report:
    accuracy: list[Outcome] = field(default_factory=list)
    scenarios: dict[str, list[Outcome]] = field(default_factory=dict)
    traps: list[tuple[Outcome, Outcome]] = field(default_factory=list)


# --- 데이터 ------------------------------------------------------------------


def load_questions() -> tuple[list[Question], dict[str, list[str]]]:
    raw = yaml.safe_load((HERE / "questions.yaml").read_text())
    qs = [
        Question(**{k: q.get(k) for k in Question.__dataclass_fields__}) for q in raw["questions"]
    ]
    return qs, raw.get("paraphrases") or {}


def gold_rows(store: SnapshotStore, q: Question, params: dict[str, Any]) -> tuple[list[str], list]:
    with store.reader() as conn:
        cur = conn.execute(q.gold, params)
        return [d[0] for d in cur.description], cur.fetchall()


def judge(store, q: Question, body: dict[str, Any], params) -> tuple[bool, str]:
    if q.expect:
        return body["path"] == q.expect, f"expect {q.expect}"
    if body["path"] in ("REJECTED", "OUT_OF_SCOPE"):
        return False, body["path"]
    columns, rows = gold_rows(store, q, params)
    want = {str(r[columns.index(q.key)]) for r in rows}
    got_rows = body.get("rows") or []
    if not want:
        return not got_rows, f"정답 0행, 답 {len(got_rows)}행"
    for j in range(len(body.get("columns") or [])):
        if {str(r[j]) for r in got_rows} == want:
            return True, ""
    return False, f"정답 {len(want)}개 값과 일치하는 칸 없음(답 {len(got_rows)}행)"


# --- 가짜 Claude(비용 0) --------------------------------------------------------


class GoldLlm:
    """질문 글자를 보고 정답 SQL을 run_sql 로 돌려준다. 실행기·캐시 흐름 점검용."""

    def __init__(self, by_text: dict[str, Question], params: dict[str, Any]) -> None:
        self._by_text = by_text
        self._params = params

    async def call(self, system, tools, messages, timeout) -> ToolCall:
        text = messages[0]["content"]
        question = text.rsplit("질문: ", 1)[-1].strip()
        q = self._by_text[question]
        if q.expect == "OUT_OF_SCOPE":
            name, data = "out_of_scope", {"reason": "범위 밖"}
        else:
            name, data = (
                "run_sql",
                {"sql": tpl.render(q.gold, self._params), "explanation": "조회 결과"},
            )
        use_id = f"toolu_{q.id}"
        return ToolCall(
            tool_use_id=use_id, name=name, input=data,
            content=[{"type": "tool_use", "id": use_id, "name": name, "input": data}],
            usage=Usage(input=1200, output=150, cache_read=4000),
        )  # fmt: skip


# --- 실행 --------------------------------------------------------------------


def make_llm(kind: str, by_text, params, settings: Settings):
    if kind == "fake":
        return GoldLlm(by_text, params)
    from seatsac_ai.llm.client import AnthropicClient

    key = settings.anthropic_api_key
    if key is None:
        sys.exit("ANTHROPIC_API_KEY 가 없다 (services/ai/.env 또는 환경 변수)")
    return AnthropicClient(key.get_secret_value(), settings.ai_model)


def load_embedder(semantic: bool):
    if not semantic:
        return None
    from seatsac_ai.cache.embedder import FastEmbedder

    embedder = FastEmbedder()
    embedder.load()
    return embedder


async def make_cache(embedder) -> ChatCache:
    """시나리오마다 빈 캐시(fakeredis + 메모리 벡터 인덱스). 임베딩 모델은 한 번 읽은 것을 같이 쓴다."""
    cache = ChatCache(
        fakeredis.FakeAsyncRedis(decode_responses=True),
        ttl=3600, session_ttl=1800, threshold=Settings().semantic_threshold,
        embedder=embedder, index=MemoryIndex() if embedder else None,
    )  # fmt: skip
    await cache.start()
    return cache


async def ask(service: ChatService, store, q: Question, text: str, now, params) -> Outcome:
    try:
        body = await service.answer(text, now=now, user_id="eval")
    except ApiError as exc:
        return Outcome(q.id, text, f"ERROR {exc.code}", False, 0, 0, 0, str(exc))
    ok, detail = judge(store, q, body, params)
    llm = body["llm"]
    return Outcome(q.id, text, body["path"], ok, llm["inputTokens"] + llm["outputTokens"],
                   llm["cacheReadTokens"], llm["calls"], detail)  # fmt: skip


def scenario(
    questions: list[Question], paraphrases, ratio: float, seed: int
) -> list[tuple[Question, str]]:
    """질문 30개. ratio 만큼은 이미 나온 family 를 다시(같은 글자 1/3 · 같은 family 다른 질문 1/3 · 다른 말투 1/3)."""
    rnd = random.Random(seed)
    pool = [q for q in questions]
    rnd.shuffle(pool)
    asked: list[Question] = []
    by_family: dict[str, list[Question]] = {}
    for q in questions:
        by_family.setdefault(q.family, []).append(q)
    repeats = round(SCENARIO_LENGTH * ratio)
    slots = [True] * repeats + [False] * (SCENARIO_LENGTH - repeats)
    rnd.shuffle(slots)
    if slots[0]:  # 첫 질문은 새 질문이어야 한다
        slots.remove(False)
        slots.insert(0, False)
    out: list[tuple[Question, str]] = []
    for repeat in slots:
        if repeat and asked:
            base = rnd.choice(asked)
            kind = rnd.choice(["same", "family", "paraphrase"])
            if kind == "family" and len(by_family[base.family]) > 1:
                q = rnd.choice(by_family[base.family])
                out.append((q, q.question))
            elif kind == "paraphrase" and paraphrases.get(base.id):
                out.append((base, rnd.choice(paraphrases[base.id])))
            else:
                out.append((base, base.question))
        else:
            q = pool.pop() if pool else rnd.choice(questions)
            asked.append(q)
            out.append((q, q.question))
    return out


def run(llm_kind: str, semantic: bool, seed: int) -> Report:
    store, now = load_store(HERE / "snapshot.json")
    params = tpl.system_params(now.date(), f"{now:%Y-%m-%d %H:%M}")
    questions, paraphrases = load_questions()
    by_id = {q.id: q for q in questions}
    by_text = {q.question: q for q in questions}
    for qid, texts in paraphrases.items():
        for t in texts:
            by_text[t] = by_id[qid]
    settings = Settings()
    llm = make_llm(llm_kind, by_text, params, settings)
    embedder = load_embedder(semantic)
    report = Report()

    async def go():
        plain = ChatService(store, llm, settings, clock=lambda: now)
        for q in questions:
            report.accuracy.append(await ask(plain, store, q, q.question, now, params))
            print(
                f"  A {q.id} {report.accuracy[-1].path:13} {'O' if report.accuracy[-1].correct else 'X'}"
            )
        for ratio in RATIOS:
            service = ChatService(
                store, llm, settings, clock=lambda: now, cache=await make_cache(embedder)
            )
            seq = scenario(questions, paraphrases, ratio, seed)
            results = []
            for q, text in seq:
                results.append(await ask(service, store, q, text, now, params))
            report.scenarios[f"{int(ratio * 100)}%"] = results
            print(f"  B {int(ratio * 100)}% done")
        for first, second in TRAPS:
            service = ChatService(
                store, llm, settings, clock=lambda: now, cache=await make_cache(embedder)
            )
            a = await ask(service, store, by_id[first], by_id[first].question, now, params)
            b = await ask(service, store, by_id[second], by_id[second].question, now, params)
            report.traps.append((a, b))

    asyncio.run(go())
    return report


# --- 보고서 ------------------------------------------------------------------


def summarize(report: Report, llm_kind: str, semantic: bool) -> str:
    acc = report.accuracy
    answerable = [o for o in acc]
    correct = sum(o.correct for o in answerable)
    base_tokens = {o.id: o.tokens for o in acc}
    lines = [
        "# NL2SQL 평가 결과",
        "",
        f"- 실행: {datetime.now():%Y-%m-%d %H:%M} · Claude: `{llm_kind}` · 의미 캐시: {'켬' if semantic else '끔'}",
        "- 기준 데이터: `eval/snapshot.json` (시각 고정) · 토큰 = input+output(프롬프트 캐싱 cache_read 제외)",
        "",
        "## A. 정확도 (캐시 없이)",
        "",
        f"**{correct}/{len(answerable)} = {correct / len(answerable):.0%}** (목표 ≥ 80%)",
        "",
        "| id | 질문 | 경로 | 맞음 | 토큰 | 비고 |",
        "|---|---|---|---|---|---|",
    ]
    for o in acc:
        lines.append(
            f"| {o.id} | {o.question} | {o.path} | {'O' if o.correct else 'X'} | {o.tokens} | {o.detail} |"
        )
    lines += ["", "## B. 반복 시나리오 (질문 30개, 캐시 켬)", "",
              "| 반복 비율 | 캐시 답 | 토큰(캐시 있음) | 토큰(캐시 없을 때) | 절감률 | 정확도 | 캐시 답 오적중 |",
              "|---|---|---|---|---|---|---|"]  # fmt: skip
    for name, results in report.scenarios.items():
        used = sum(o.tokens for o in results)
        baseline = sum(base_tokens[o.id] for o in results)
        hits = [o for o in results if o.path.startswith("CACHE_")]
        wrong_hits = [o for o in hits if not o.correct]
        ok = sum(o.correct for o in results)
        saving = 1 - used / baseline if baseline else 0
        lines.append(
            f"| {name} | {len(hits)}/{len(results)} | {used:,} | {baseline:,} | **{saving:.0%}** | "
            f"{ok}/{len(results)} | {len(wrong_hits)}/{len(hits) or 1} |"
        )
    lines += [
        "",
        "## C. 오적중 함정 쌍",
        "",
        "| 먼저 | 나중 | 나중 경로 | 나중 맞음 |",
        "|---|---|---|---|",
    ]
    for a, b in report.traps:
        lines.append(f"| {a.question} | {b.question} | {b.path} | {'O' if b.correct else 'X'} |")
    lines += ["", "## 가정", "",
              "- 실제 서비스는 스냅샷이 1분·10분마다 바뀐다. 여기서는 한 시점으로 고정해 정답을 정했다.",
              "- 반복 시나리오의 '캐시 없을 때' 토큰은 A에서 같은 질문(다른 말투면 원래 질문)에 쓴 토큰이다.",
              "- staging 데이터 제약: 예매 0건이라 잔여석 = 전체 좌석, 장르·감독·러닝타임 없음 → 해당 질문 제외."]  # fmt: skip
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--llm", choices=["fake", "claude"], default="fake")
    parser.add_argument("--no-semantic", action="store_true")
    parser.add_argument("--seed", type=int, default=17)
    parser.add_argument("--out", type=Path, default=HERE / "out")
    args = parser.parse_args()
    report = run(args.llm, not args.no_semantic, args.seed)
    args.out.mkdir(exist_ok=True)
    md = summarize(report, args.llm, not args.no_semantic)
    (args.out / f"report-{args.llm}.md").write_text(md)
    (args.out / f"report-{args.llm}.json").write_text(json.dumps(
        {"accuracy": [o.__dict__ for o in report.accuracy],
         "scenarios": {k: [o.__dict__ for o in v] for k, v in report.scenarios.items()},
         "traps": [[a.__dict__, b.__dict__] for a, b in report.traps]},
        ensure_ascii=False, indent=1))  # fmt: skip
    print(md)


if __name__ == "__main__":
    main()
