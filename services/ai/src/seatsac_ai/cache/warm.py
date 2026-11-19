"""버튼 질문(프리셋) 캐시 예열 — ③.

캐시는 누군가 먼저 물어야 생긴다. 그래서 서버를 켠 직후 첫 사용자는 늘 Claude를 부른다.
프리셋은 SQL을 사람이 미리 써 두었으니 Claude 없이도 캐시 항목을 만들 수 있다.
presets.yaml 의 phrasings(말투 몇 개)를 Claude 가 답했을 때와 같은 모양의 항목으로 넣는다.

- 빈칸 종류가 프리셋 params 와 같아야 한다. 날짜만은 '오늘·내일·모레'로 대신할 수 있다
  (SQL의 :date 를 :today·:d1·:d2 로 바꾼다 — Claude 답을 저장할 때와 같은 규칙, template.py)
- 등급(grade) 프리셋은 넣지 않는다(질문에서 뽑는 빈칸이 아니라 캐시하지 않는다)
- 캐시 수명(1시간)보다 자주 다시 넣는다(refresh_forever). 실제 사용으로 생긴 같은 키는 덮어쓴다 —
  같은 프리셋이고 SQL은 사람이 검증한 것이다
"""

from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass
from typing import Protocol

import sqlglot
from sqlglot import exp

from seatsac_ai.cache.slots import RELATIVE_DAYS, canonical, meaning_signature, normalize
from seatsac_ai.cache.store import CacheEntry, ChatCache
from seatsac_ai.cache.template import MAX_OFFSET
from seatsac_ai.resources import Preset

# 문맥 이어받기 내부 틀(resources/context_templates.yaml)의 캐시 키·presetId 머리말.
# 키에 빈칸이 있다 — 질문의 키(slots.normalize)는 빈칸을 모두 지우므로 질문의 키와 겹칠 수 없다
INTERNAL_KEY_PREFIX = "ctx "
INTERNAL_PRESET_PREFIX = "ctx_"

log = logging.getLogger(__name__)

_SLOT = re.compile(r"\{([a-z]+)\}")


class WarmError(ValueError):
    """phrasings 가 프리셋과 맞지 않는다(테스트가 잡는다)."""


@dataclass(frozen=True)
class WarmItem:
    key_text: str
    template: str
    entry: CacheEntry
    semantic: bool = True  # False 면 정확 일치 키로만 넣는다(의미 색인에 넣지 않는다)


def preset_items(presets: tuple[Preset, ...]) -> list[WarmItem]:
    items: dict[str, WarmItem] = {}
    for preset in presets:
        if "grade" in preset.params:
            continue
        for phrasing in preset.phrasings:
            item = _item(preset, phrasing)
            other = items.get(item.key_text)
            if other is not None and other.entry.preset_id != preset.preset_id:
                raise WarmError(
                    f"'{phrasing}' 가 {other.entry.preset_id} 와 {preset.preset_id} 에 같이 있다"
                )
            items[item.key_text] = item  # 다듬은 뒤 글자가 같으면 하나만
    return list(items.values())


def internal_items(templates: tuple[Preset, ...]) -> list[WarmItem]:
    """내부 틀마다 항목 하나. 이어받기 경로(orchestrator._carried)만 이 키를 조회한다.

    직접 친 질문이 내부 틀로 답해지지 않게 두 가지를 지킨다(#3.4.22 리뷰):
      - 키가 질문 말투가 아니라 `ctx {presetId}` 다 → 정확 일치 조회에 걸리지 않는다
      - 의미 색인에 넣지 않는다 → 의미 캐시에 걸리지 않고, 프리셋 항목의 최근접 1등을 가리지 않는다
    """
    items = []
    for template in templates:
        base = _item(template, template.phrasings[0])
        key = INTERNAL_KEY_PREFIX + template.preset_id
        items.append(WarmItem(key, base.template, base.entry, semantic=False))
    return items


def _item(preset: Preset, phrasing: str) -> WarmItem:
    kinds = tuple(sorted(set(_SLOT.findall(phrasing))))
    relative = tuple(sorted({w for w in RELATIVE_DAYS if w in phrasing}))
    missing = set(preset.params) - set(kinds)
    if set(kinds) - set(preset.params):
        raise WarmError(f"{preset.preset_id}: '{phrasing}' 의 빈칸을 프리셋이 쓰지 않는다")
    sql = preset.sql
    if missing == {"date"} and len(relative) == 1:
        offset = RELATIVE_DAYS[relative[0]]
        if not 0 <= offset <= MAX_OFFSET:
            raise WarmError(f"{preset.preset_id}: '{phrasing}' 날짜 {offset}일 뒤는 캐시 범위 밖")
        sql = rename_date(sql, "today" if offset == 0 else f"d{offset}")
    elif missing:
        raise WarmError(f"{preset.preset_id}: '{phrasing}' 에 빈칸 {sorted(missing)} 이 없다")
    template = canonical(phrasing)
    entry = CacheEntry(
        origin="PRESET",
        preset_id=preset.preset_id,
        sql=sql,
        subject=preset.title.replace("○시", "{time}"),
        kinds=kinds,
        relative_words=relative,
        anchors=(),
        template=template,
        meaning=meaning_signature(template),  # 질문 쪽과 같은 글자(다듬은 템플릿)로
    )
    return WarmItem(normalize(template), template, entry)


def rename_date(sql: str, name: str) -> str:
    def swap(node: exp.Expression) -> exp.Expression:
        if isinstance(node, exp.Placeholder) and node.name == "date":
            return exp.Placeholder(this=name)
        return node

    return sqlglot.parse_one(sql, read="sqlite").transform(swap).sql(dialect="sqlite")


async def warm(cache: ChatCache, items: list[WarmItem]) -> int:
    """캐시에 넣는다. 임베딩이 되면 의미 캐시에도. 넣은 수.

    하나라도 저장에 실패하면(Valkey 멈춤 등) 이번 회차는 거기서 멈춘다 — 나머지도 실패할 테고
    한 건마다 시간 초과를 기다리게 된다. 다음 회차에 다시 넣는다.
    """
    semantic = cache.embedder is not None and await cache.semantic_available()
    stored = 0
    for item in items:
        vector = None
        if semantic and item.semantic:
            vector = await asyncio.to_thread(cache.embedder.embed, item.template)
        if not await cache.put(item.key_text, item.entry, vector):
            break
        stored += 1
    return stored


class LearnedSource(Protocol):
    async def items(self) -> list[WarmItem]: ...


async def refresh_forever(
    cache: ChatCache,
    items: list[WarmItem],
    every: float,
    learned: LearnedSource | None = None,
) -> None:
    """캐시 수명보다 짧은 간격으로 다시 넣는다(수명이 지나 사라지지 않게).

    learned 가 있으면 스스로 배운 말투(cache/learned.py)도 같이 넣는다.
    """
    while True:
        try:
            n = await warm(cache, items)
            if n == len(items):
                if items:
                    log.info("버튼 질문 말투 %d개를 캐시에 넣었다", n)
            else:
                log.warning("버튼 질문 말투 %d/%d개만 넣었다(다음에 다시)", n, len(items))
        except Exception as exc:  # noqa: BLE001 — 예열 실패는 답에 영향 없음(놓친 것으로 처리)
            log.warning("캐시 예열 실패(다음에 다시): %s", exc)
        if learned is not None:
            try:  # 따로 묶는다 — 배운 말투 쪽 실패가 버튼 말투 예열을 막지 않게
                extra = await learned.items()
                if extra:
                    put = await warm(cache, extra)
                    log.info("배운 말투 %d/%d개를 캐시에 넣었다", put, len(extra))
            except Exception as exc:  # noqa: BLE001
                log.warning("배운 말투 예열 실패(다음에 다시): %s", exc)
        await asyncio.sleep(every)
