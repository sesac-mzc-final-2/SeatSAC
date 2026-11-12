"""ai Valkey의 캐시·세션 (WBS 3.4.5 · conventions 4장 키 표).

  ai:exact:{sha256(정규화 템플릿 질문)}   1시간  정확 일치: SQL 템플릿 + 슬롯 종류 + 경로
  ai:semcache:{id} (HASH, ai_semcache_idx) 1시간  의미 캐시: 템플릿 질문 임베딩(384 FLOAT32, COSINE)
  ai:session:{userId}:{sessionId}          30분   후속 질문 문맥(직전 질문·SQL)

캐시는 비용을 줄이는 장치일 뿐이다. Valkey가 실패하면 **놓친 것(miss)으로 보고** Claude로 답한다
(사용 한도는 quota/limiter.py 가 따로 503으로 막는다).
"""

from __future__ import annotations

import hashlib
import json
import logging
import struct
import time
import uuid
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from typing import Any, Protocol

from redis.exceptions import RedisError, ResponseError

log = logging.getLogger(__name__)

SEMCACHE_INDEX = "ai_semcache_idx"
SEMCACHE_PREFIX = "ai:semcache:"
SEMCACHE_MAX = 5000  # conventions 4장: 넘으면 저장하지 않음(1건 약 2.5KB)
DIM = 384


@dataclass(frozen=True)
class CacheEntry:
    origin: str  # 처음 답한 경로 PRESET · NL2SQL
    preset_id: str | None
    sql: str  # 템플릿(:today·:time …)
    subject: str  # 답 문장 앞부분 템플릿("오늘 {time} 이후 회차")
    kinds: tuple[str, ...]  # 슬롯 종류
    relative_words: tuple[str, ...]  # "오늘·내일" — 의미 캐시는 이것도 같아야 적중
    anchors: tuple[str, ...]  # 의미 캐시 적중 때 새 질문에 있어야 하는 말
    template: str  # 템플릿 질문(로그·디버깅용 아님 — 의미 캐시 저장용)
    meaning: tuple[str, ...] = ()  # 이후·이전·많은·적은 같은 뜻 묶음 — 의미 캐시는 같아야 적중

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)

    @classmethod
    def from_json(cls, text: str) -> CacheEntry:
        raw = json.loads(text)
        for key in ("kinds", "relative_words", "anchors", "meaning"):
            raw[key] = tuple(raw.get(key) or ())
        return cls(**raw)


class Embedder(Protocol):
    def embed(self, text: str) -> list[float]: ...


RETRY_SECONDS = 30  # 기동 때 연결 오류로 의미 캐시 인덱스를 못 만들었을 때 재시도 간격


class SemanticIndex(Protocol):
    async def ensure(self) -> None: ...
    async def count(self) -> int: ...
    async def add(
        self, doc_id: str, vector: Sequence[float], kinds: str, entry: str, ttl: int
    ) -> None: ...
    async def nearest(self, vector: Sequence[float], kinds: str) -> tuple[str, float] | None: ...


def kinds_tag(kinds: Sequence[str]) -> str:
    return "_".join(sorted(kinds)) or "none"


def exact_key(key_text: str) -> str:
    return "ai:exact:" + hashlib.sha256(key_text.encode()).hexdigest()


def session_key(user_id: str, session_id: str) -> str:
    return f"ai:session:{user_id}:{session_id}"


class ValkeySearchIndex:
    """ElastiCache Valkey 8.2 벡터 검색(valkey-search).

    FT.* 가 없으면 ensure() 가 예외 → 의미 캐시를 끈다.
    """

    def __init__(self, valkey: Any) -> None:
        self._v = valkey

    async def ensure(self) -> None:
        try:
            await self._v.execute_command(
                "FT.CREATE", SEMCACHE_INDEX, "ON", "HASH", "PREFIX", "1", SEMCACHE_PREFIX,
                "SCHEMA", "kinds", "TAG",
                "embedding", "VECTOR", "HNSW", "6",
                "TYPE", "FLOAT32", "DIM", str(DIM), "DISTANCE_METRIC", "COSINE",
            )  # fmt: skip
        except ResponseError as exc:
            if "already exists" not in str(exc).lower():
                raise

    async def count(self) -> int:
        info = await self._v.execute_command("FT.INFO", SEMCACHE_INDEX)
        pairs = dict(zip(info[::2], info[1::2], strict=False))
        return int(pairs.get("num_docs", 0))

    async def add(self, doc_id, vector, kinds, entry, ttl) -> None:
        key = SEMCACHE_PREFIX + doc_id
        # 한 트랜잭션으로 — 사이에서 끊기면 TTL 없는 키가 noeviction Valkey에 남는다(#94 리뷰)
        async with self._v.pipeline(transaction=True) as pipe:
            pipe.hset(key, mapping={"embedding": _pack(vector), "kinds": kinds, "entry": entry})
            pipe.expire(key, ttl)
            await pipe.execute()

    async def nearest(self, vector, kinds):
        query = f"(@kinds:{{{kinds}}})=>[KNN 1 @embedding $vec AS score]"
        reply = await self._v.execute_command(
            "FT.SEARCH", SEMCACHE_INDEX, query,
            "PARAMS", "2", "vec", _pack(vector),
            "RETURN", "2", "score", "entry", "DIALECT", "2",
        )  # fmt: skip
        if not reply or int(reply[0]) == 0:
            return None
        fields = reply[2]
        found = dict(zip(fields[::2], fields[1::2], strict=False))
        if "entry" not in found:
            return None
        distance = float(found.get("score", 1.0))  # COSINE 거리 = 1 - 유사도
        return found["entry"], 1.0 - distance


class MemoryIndex:
    """테스트용(벡터 검색 모듈 없는 fakeredis 대신). TTL은 보지 않는다."""

    def __init__(self) -> None:
        self.docs: dict[str, tuple[list[float], str, str]] = {}

    async def ensure(self) -> None:
        return None

    async def count(self) -> int:
        return len(self.docs)

    async def add(self, doc_id, vector, kinds, entry, ttl) -> None:
        self.docs[doc_id] = (list(vector), kinds, entry)

    async def nearest(self, vector, kinds):
        best: tuple[str, float] | None = None
        for vec, doc_kinds, entry in self.docs.values():
            if doc_kinds != kinds:
                continue
            sim = cosine(vector, vec)
            if best is None or sim > best[1]:
                best = (entry, sim)
        return best


class ChatCache:
    def __init__(
        self,
        valkey: Any,
        *,
        ttl: int,
        session_ttl: int,
        threshold: float,
        embedder: Embedder | None = None,
        index: SemanticIndex | None = None,
    ) -> None:
        self._v = valkey
        self._ttl = ttl
        self._session_ttl = session_ttl
        self.threshold = threshold
        self.embedder = embedder
        self._index = index
        self.semantic_ready = False
        self._retry_at: float | None = None  # 연결 오류로 못 만들었으면 다시 시도할 시각

    async def start(self) -> None:
        """의미 캐시 준비(인덱스 만들기). 실패하면 정확 일치 캐시만 쓴다(계약 9-1 B1).

        모듈 없음·문법 오류(ResponseError)는 파드 수명 동안 끈다. 기동 순간의 연결 오류는
        일시적이라 RETRY_SECONDS 뒤 다음 검색·저장 때 다시 시도한다(#94 리뷰 — ai는 1대 고정).
        """
        if self.embedder is None or self._index is None:
            return
        try:
            await self._index.ensure()
            self.semantic_ready = True
            self._retry_at = None
        except ResponseError as exc:
            log.warning("의미 캐시 인덱스를 만들 수 없어 정확 일치 캐시만 쓴다: %s", exc)
            self._retry_at = None
        except Exception as exc:  # 연결·타임아웃 — 잠시 뒤 다시
            log.warning("의미 캐시 인덱스 준비 실패(%ss 뒤 재시도): %s", RETRY_SECONDS, exc)
            self._retry_at = time.monotonic() + RETRY_SECONDS

    async def semantic_available(self) -> bool:
        """의미 캐시를 쓸 수 있나. 연결 오류로 꺼졌으면 RETRY_SECONDS가 지난 뒤 다시 만들어 본다."""
        if not self.semantic_ready and self._retry_at is not None:
            if time.monotonic() >= self._retry_at:
                await self.start()
        return self.semantic_ready and self._index is not None

    # --- 정확 일치 -------------------------------------------------------------
    async def get_exact(self, key_text: str) -> CacheEntry | None:
        try:
            raw = await self._v.get(exact_key(key_text))
        except RedisError as exc:
            log.warning("캐시 조회 실패(놓친 것으로 처리): %s", exc)
            return None
        return CacheEntry.from_json(raw) if raw else None

    # --- 의미 ----------------------------------------------------------------
    async def nearest(
        self, vector: Sequence[float], kinds: Sequence[str]
    ) -> tuple[CacheEntry, float] | None:
        if not await self.semantic_available():
            return None
        try:
            found = await self._index.nearest(vector, kinds_tag(kinds))
        except (RedisError, ValueError, IndexError) as exc:
            log.warning("의미 캐시 검색 실패(놓친 것으로 처리): %s", exc)
            return None
        if found is None:
            return None
        return CacheEntry.from_json(found[0]), found[1]

    # --- 저장 ----------------------------------------------------------------
    async def put(self, key_text: str, entry: CacheEntry, vector: Sequence[float] | None) -> None:
        payload = entry.to_json()
        try:
            await self._v.set(exact_key(key_text), payload, ex=self._ttl)
            if vector is not None and await self.semantic_available():
                if await self._index.count() < SEMCACHE_MAX:
                    doc_id = hashlib.sha256(key_text.encode()).hexdigest()[:24]
                    await self._index.add(
                        doc_id, vector, kinds_tag(entry.kinds), payload, self._ttl
                    )
                else:
                    log.info("의미 캐시가 %d건이라 더 저장하지 않는다", SEMCACHE_MAX)
        except (RedisError, ValueError, IndexError) as exc:
            log.warning("캐시 저장 실패(답은 그대로 나감): %s", exc)

    # --- 세션 ----------------------------------------------------------------
    async def load_session(self, user_id: str, session_id: str | None) -> tuple[str, dict | None]:
        """(이번에 쓸 sessionId, 직전 문맥). 모르는·남의·만료된 sessionId면 새로 발급(오류 아님)."""
        if session_id:
            try:
                raw = await self._v.get(session_key(user_id, session_id))
            except RedisError as exc:
                log.warning("세션 조회 실패: %s", exc)
                raw = None
            if raw:
                return session_id, json.loads(raw)
        return str(uuid.uuid4()), None

    async def save_session(self, user_id: str, session_id: str, context: dict[str, Any]) -> None:
        try:
            await self._v.set(
                session_key(user_id, session_id),
                json.dumps(context, ensure_ascii=False),
                ex=self._session_ttl,
            )
        except RedisError as exc:
            log.warning("세션 저장 실패: %s", exc)


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    na = sum(x * x for x in a) ** 0.5
    nb = sum(y * y for y in b) ** 0.5
    return dot / (na * nb) if na and nb else 0.0


def _pack(vector: Sequence[float]) -> bytes:
    return struct.pack(f"<{len(vector)}f", *vector)
