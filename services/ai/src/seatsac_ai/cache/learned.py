"""스스로 배운 말투 장부 (WBS 3.4.23).

Claude가 푼 질문은 1시간만 캐시에 남는다(store.py). 그중 **Claude가 버튼(프리셋)을 골라 답한
말투**는 SQL이 사람이 검증한 버튼 SQL 그대로다. 그 말투를 장부에 적어 두고, 예열 루프(warm.py)가
버튼 말투와 함께 캐시에 계속 다시 넣는다 — 1시간이 지나도 Claude를 부르지 않는다.

  ai:learned:v2 (HASH)   필드 = sha256(정규화 템플릿 질문) 앞 24자
                         값   = {"keyText", "entry"(CacheEntry), "learnedAt"}
                         한 줄의 수명 = 배운 때부터 7일(다시 넣을 때 learnedAt 을 보고 지운다)
                         키 전체 TTL = 마지막으로 적은 때부터 7일(수명 없는 키를 두지 않는다)
                         최대 200줄

배우는 조건 (하나라도 아니면 적지 않는다) — accepts()
  - 경로가 PRESET 이고, 저장된 SQL이 **지금의** 그 버튼 SQL과 같다
    — Claude가 직접 쓴 SQL(NL2SQL)은 검증한 사람이 없어 오래 굳히지 않는다
  - 버튼의 날짜 빈칸을 :today·:d1~:d3 로 바꾼 SQL이면 질문에 그 날을 가리키는 말("오늘·내일·모레")이
    하나 있어야 한다. 날짜 말이 없는 질문("주말 영화")에 Claude가 오늘 날짜를 넣은 답은 적지 않는다
    — 적으면 다른 날 물어도 "그날의 오늘"로 답한다(PR 전 검토)
  - 값으로 뽑지 못한 날짜 말("주말·어제·다음 주·크리스마스")이 질문에 남아 있지 않다 — 버튼 SQL은
    대부분 오늘(:today)로 굳어 있어, 그런 질문에 Claude가 오늘 버튼을 고른 답을 적으면 틀린 날짜로
    계속 답한다(PR 전 검토). 낱말 목록(_DAY_WORDS)으로 보는 것이라 목록에 없는 말은 못 막는다
  - 버튼에 이미 적어 둔 말투(phrasings)가 아니다 — 그건 예열이 맡는다
  - 앞 대화를 이어받은 답(문맥 이어받기 3.4.22)·좌석 잡기 답이 아니다 — 캐시와 장부는 모든 사용자가
    같이 쓴다. 한 사람의 조건(내일·부산본점)이 다른 사람 답에 섞이면 안 된다. 그런 답은
    orchestrator 가 저장(_save) 자체를 건너뛰어 여기까지 오지 않고, 내부 틀(ctx_)은 여기서도 막는다
  - 템플릿 질문이 60자 이하 — 긴 문장(사연·개인정보가 섞이기 쉽다)을 오래 두지 않는다

고쳐지는 길
  - 같은 말투에 Claude가 **다른 답**(다른 버튼·다른 SQL)을 내면 그 줄을 지운다 — 어느 쪽이 맞는지
    알 수 없으니 오래 기억하지 않는다. 단, 줄이 있는 동안은 캐시가 먼저 답해 Claude가 다시 불리는
    일이 드물다(예열 실패·캐시 SQL 실행 실패 뒤뿐) — 실제 교정은 7일 수명과 수동 삭제다
  - 다시 넣을 때도 같은 조건을 본다. 배포로 버튼이 없어졌거나 SQL·질문 다듬기 규칙이 바뀌었으면
    지운다
  - 남는 위험: Claude가 조건 하나를 빼먹고 버튼을 고른 답("리클라이너관"을 무시)은 구분하지 못한다.
    지금도 1시간은 그대로 나가는 답이고, 여기서는 최대 7일이다. 급하면 플래그
    APP_AI_CACHE_LEARN_ENABLED=false 로 끄고 `DEL ai:learned:v2`(README) — 끄지 않고 지우기만 하면
    같은 말투를 다시 배운다

장부는 비용을 줄이는 장치일 뿐이다. Valkey가 실패하면 배우지 않은 것으로 보고 답은 그대로 나간다.
ai Valkey는 영속화가 없어 재부팅하면 장부도 사라진다 — 처음부터 다시 배운다.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from collections.abc import Callable
from typing import Any

from redis.exceptions import RedisError

from seatsac_ai.cache.slots import RELATIVE_DAYS, meaning_signature, normalize
from seatsac_ai.cache.store import CACHE_VERSION, CacheEntry
from seatsac_ai.cache.template import MAX_OFFSET
from seatsac_ai.cache.warm import INTERNAL_PRESET_PREFIX, WarmItem, preset_items, rename_date
from seatsac_ai.resources import Preset

log = logging.getLogger(__name__)

LEARNED_KEY = f"ai:learned:{CACHE_VERSION}"
LEARNED_MAX = 200
LEARNED_TTL = 7 * 24 * 3600
TEMPLATE_MAX = 60

# 슬롯(slots.py)이 날짜 값으로 뽑지 못하고 템플릿 글자에 남는 날짜 말. 하나라도 있으면 배우지 않는다
_DAY_WORDS = re.compile(
    r"주말|평일|휴일|연휴|어제|그제|그저께|엊그제|이번\s*주|다음\s*주|담주|지난|요일|이번\s*달|다음\s*달"
    r"|월말|연말|새해|크리스마스|성탄|설날|추석|명절|\d+\s*일\s*(뒤|후|전)|며칠"
)

# learn() 결과 = 지표 ai_cache_learned_total 의 result 라벨
STORED = "stored"  # 장부에 새로 적었다
KNOWN = "known"  # 이미 같은 답으로 적혀 있다
DROPPED = "dropped"  # 같은 말투에 다른 답이 나와 그 줄을 지웠다
SKIPPED = "skipped"  # 조건에 맞지 않는다(accepts)
FULL = "full"  # 장부가 가득 찼다
ERROR = "error"  # Valkey 실패
LEARN_RESULTS = (STORED, KNOWN, DROPPED, SKIPPED, FULL, ERROR)


def field_of(key_text: str) -> str:
    return hashlib.sha256(key_text.encode()).hexdigest()[:24]


class LearnedBook:
    def __init__(
        self,
        valkey: Any,
        presets: tuple[Preset, ...],
        clock: Callable[[], float] = time.time,
    ) -> None:
        self._v = valkey
        self._clock = clock  # 테스트가 "7일 뒤"를 만들 때만 바꾼다
        self._presets = {p.preset_id: p for p in presets}
        self._preset_keys = frozenset(item.key_text for item in preset_items(presets))

    def _expected_sql(self, entry: CacheEntry) -> str | None:
        """이 항목이 가져야 하는 SQL(지금의 버튼 SQL). 설명할 수 없으면 None.

        warm._item 과 같은 규칙: 빈칸 종류가 버튼과 같거나, date 하나만 빠지고 그 자리를
        질문의 "오늘·내일·모레" 한 낱말이 대신한다. sqlglot 으로 다시 쓴 글자로 비교한다
        (template.preset_template 이 저장하는 모양).
        """
        preset = self._presets.get(entry.preset_id or "")
        if preset is None or set(entry.kinds) - set(preset.params):
            return None
        missing = set(preset.params) - set(entry.kinds)
        if not missing:
            return rename_date(preset.sql, "date")  # 이름은 그대로, 글자만 sqlglot 모양으로
        if missing != {"date"} or len(entry.relative_words) != 1:
            return None
        offset = RELATIVE_DAYS.get(entry.relative_words[0])
        if offset is None or not 0 <= offset <= MAX_OFFSET:
            return None
        return rename_date(preset.sql, "today" if offset == 0 else f"d{offset}")

    def accepts(self, key_text: str, entry: CacheEntry) -> bool:
        return (
            entry.origin == "PRESET"
            # 문맥 이어받기 내부 틀(ctx_)은 한 사람의 앞 조건으로 채우는 틀이다 — 배우지 않는다
            and not (entry.preset_id or "").startswith(INTERNAL_PRESET_PREFIX)
            and entry.sql == self._expected_sql(entry)
            and not _DAY_WORDS.search(entry.template)
            and key_text not in self._preset_keys
            and len(entry.template) <= TEMPLATE_MAX
            # 질문 다듬기 규칙(slots.py)이 바뀌면 옛 줄의 키·뜻 묶음은 지금 질문과 맞지 않는다
            and key_text == normalize(entry.template)
            and tuple(entry.meaning) == meaning_signature(entry.template)
        )

    async def learn(self, key_text: str, entry: CacheEntry) -> str:
        """방금 캐시에 저장한 항목을 장부에 적는다. 결과는 LEARN_RESULTS 중 하나."""
        if not self.accepts(key_text, entry):
            return SKIPPED
        field = field_of(key_text)
        try:
            old = await self._v.hget(LEARNED_KEY, field)
            if old is not None:
                if _same_answer(old, entry):
                    return KNOWN
                await self._v.hdel(LEARNED_KEY, field)
                return DROPPED
            if await self._v.hlen(LEARNED_KEY) >= LEARNED_MAX:
                return FULL  # 동시에 적으면 몇 줄 넘을 수 있다 — 크기 보호라 엄격할 필요는 없다
            line = json.dumps(
                {"keyText": key_text, "entry": entry.to_json(), "learnedAt": int(self._clock())},
                ensure_ascii=False,
            )
            # 한 트랜잭션으로 — 사이에서 끊기면 수명 없는 키가 noeviction Valkey에 남는다
            async with self._v.pipeline(transaction=True) as pipe:
                pipe.hset(LEARNED_KEY, field, line)
                pipe.expire(LEARNED_KEY, LEARNED_TTL)
                await pipe.execute()
        except RedisError as exc:
            log.warning("배운 말투 저장 실패(답은 그대로 나감): %s", exc)
            return ERROR
        return STORED

    async def items(self) -> list[WarmItem]:
        """장부의 말투를 예열 항목으로. 7일이 지났거나 지금 조건에 맞지 않는 줄은 지운다."""
        try:
            lines = await self._v.hgetall(LEARNED_KEY)
        except RedisError as exc:
            log.warning("배운 말투 읽기 실패(이번 회차는 건너뜀): %s", exc)
            return []
        oldest = self._clock() - LEARNED_TTL
        items: list[WarmItem] = []
        stale: list[str] = []
        for field, line in lines.items():
            try:
                raw = json.loads(line)
                key_text = raw["keyText"]
                entry = CacheEntry.from_json(raw["entry"])
                keep = raw["learnedAt"] > oldest and self.accepts(key_text, entry)
            except Exception:  # noqa: BLE001 — 읽을 수 없는 줄 하나가 예열 전체를 멈추면 안 된다
                keep = False
            if keep:
                items.append(WarmItem(key_text, entry.template, entry))
            else:
                stale.append(field)
        if stale:
            log.info("배운 말투 %d줄을 지운다(7일 지남·버튼이 바뀜·읽을 수 없음)", len(stale))
            try:
                await self._v.hdel(LEARNED_KEY, *stale)
            except RedisError as exc:
                log.warning("배운 말투 지우기 실패(다음 회차에 다시): %s", exc)
        return items


def _same_answer(line: str, entry: CacheEntry) -> bool:
    try:
        old = CacheEntry.from_json(json.loads(line)["entry"])
    except Exception:  # noqa: BLE001 — 읽을 수 없으면 다른 답으로 보고 지운다
        return False
    return (old.preset_id, old.sql) == (entry.preset_id, entry.sql)
