"""질문 정규화·슬롯 추출 (계약 15-2 ③ · WBS 3.4.5).

"오늘 20시 이후 영화" → 템플릿 "오늘 {time} 이후 영화" + 슬롯 {time: "20:00"}.
템플릿이 같으면 값만 바꿔 같은 SQL을 다시 쓸 수 있다(캐시 키).

슬롯 5종 (presets.yaml 의 params 와 같은 이름·형식)
  time    'HH:MM'       "20시", "오후 8시 반", "20:30"
  date    'YYYY-MM-DD'  "11월 18일", "11/18", "18일", "토요일", "다음 주 토요일" (절대 날짜만)
  theater 스냅샷 theater.name (질문의 "건대"처럼 앞부분만 맞아도 하나로 정해지면)
  movie   스냅샷 movie.title (띄어쓰기 무시)
  n       정수           "3개", "5편", "상위 3"

"오늘·내일·모레"는 슬롯이 아니다. 묻는 날에 따라 뜻이 바뀌는 말이라 템플릿에 글자 그대로 남기고,
SQL 쪽에서 :today·:d1·:d2 로 바꾼다(template.py). 그래서 내일 다시 물어도 맞는 날짜로 실행된다.

같은 종류 슬롯이 두 번 나오면("20시부터 22시 사이") 캐시하지 않는다(cacheable=False) — 어느 값이
SQL의 어느 자리인지 알 수 없어서.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

SLOT_KINDS = ("time", "date", "theater", "movie", "n")

# 상대 날짜 단어 → 오늘에서 며칠 뒤
RELATIVE_DAYS = {"오늘": 0, "금일": 0, "내일": 1, "낼": 1, "모레": 2, "글피": 3}
_RELATIVE = re.compile("|".join(sorted(RELATIVE_DAYS, key=len, reverse=True)))

_TIME_KO = re.compile(
    r"(?P<ampm>오전|오후|아침|낮|저녁|밤|새벽)?\s*(?P<h>\d{1,2})\s*시(?!간)"
    r"(?:\s*(?P<m>\d{1,2})\s*분|\s*(?P<half>반))?"
)
_TIME_COLON = re.compile(r"(?<!\d)(?P<h>[01]?\d|2[0-3]):(?P<m>[0-5]\d)(?!\d)")
_DATE_MD = re.compile(r"(?P<mo>\d{1,2})\s*월\s*(?P<d>\d{1,2})\s*일")
_DATE_SLASH = re.compile(r"(?<![\d:])(?P<mo>\d{1,2})/(?P<d>\d{1,2})(?![\d:])")
_DATE_D = re.compile(r"(?<![\d월])(?P<d>\d{1,2})\s*일(?![간째])")
_WEEKDAY = re.compile(
    r"(?P<week>이번\s*주\s*|이번주\s*|다음\s*주\s*|다음주\s*|담주\s*)?(?P<wd>[월화수목금토일])요일"
)
_N = re.compile(
    r"(?:(?:상위|top|TOP|베스트)\s*(?P<n1>\d{1,3}))|(?:(?P<n2>\d{1,3})\s*(?:개|편|곳|건|군데|위))"
)
_PUNCT = re.compile(r"[\s?？!！.,~·'\"“”‘’()\[\]]+")
_WEEKDAYS = "월화수목금토일"
# 극장 이름 앞부분으로 고를 때 떼어 볼 조사·꼬리
_PARTICLES = re.compile(r"(에서|으로|에|의|은|는|이|가|만|점|관|극장|지점)$")


@dataclass(frozen=True)
class Slot:
    value: str | int  # SQL에 넣을 값(정규화된 값)
    raw: str  # 질문에 적힌 글자 그대로("건대", "오후 8시")


@dataclass(frozen=True)
class Extracted:
    question: str
    template: str  # "오늘 {time} 이후 영화" — 의미 캐시 임베딩 입력
    key_text: str  # 정규화한 템플릿 — 정확 일치 캐시 키
    slots: dict[str, Slot] = field(default_factory=dict)
    relative_words: frozenset[str] = frozenset()  # 질문에 있던 "오늘·내일·모레"
    cacheable: bool = True

    @property
    def kinds(self) -> tuple[str, ...]:
        return tuple(sorted(self.slots))

    def values(self) -> dict[str, str | int]:
        return {k: s.value for k, s in self.slots.items()}

    def allowed_offsets(self) -> frozenset[int]:
        """SQL 날짜를 :today·:dN 으로 바꿔도 되는 오프셋. 오늘(0)은 질문에 없어도 기본값."""
        return frozenset({0} | {RELATIVE_DAYS[w] for w in self.relative_words})


_FOLLOW_UP = re.compile(
    # "이중섭"처럼 낱말 안의 "이중"은 빼려고 "이 중"(띄어 씀)·"이중에"만 본다
    r"그\s*중|이\s+중|이중에|거기|그거|그것|방금|아까|위\s*결과"
    r"|^\s*(그럼|그러면|그리고|그\s*영화|그\s*극장)"
    # 직전 조건을 그대로 쓰겠다는 말("같은 조건으로 건대입구는?", "5개로 늘려서")
    r"|같은\s*(조건|걸로|시간|날)|마찬가지|똑같이|동일|늘려|줄여|이번엔|이번에는"
    # 값 하나만 바꾸는 짧은 말("부산은?", "영등포도 알려줘") — 혼자서는 뜻이 서지 않는다
    r"|^\s*\S{1,8}(은|는|도)\s*(어때|요|알려\s*줘)?\s*[?？]*\s*$"
)


# 의미 캐시 오적중 막기: 임베딩은 "20시 이후"와 "20시 전에"를 0.93으로 거의 같게 본다(11/10 실측).
# 뜻을 뒤집는 말을 묶음으로 나눠, 묶음 구성이 같을 때만 같은 질문으로 본다.
_MEANING_GROUPS = {
    "after": r"이후|부터|넘어|넘은|지나|뒤에|후에|늦게",
    "before": r"이전|전에|전까지|까지|앞에|일찍",
    "most": r"많은|많이|최대|여유|넉넉",
    "least": r"적은|적게|최소|임박|매진|얼마\s*안",
    "first": r"가장\s*빠른|제일\s*빠른|이른|첫|먼저",
    "last": r"가장\s*늦은|제일\s*늦은|마지막|막차",
    "not": r"빼고|제외|말고|아닌|없는",
}
_MEANING = {k: re.compile(v) for k, v in _MEANING_GROUPS.items()}


def meaning_signature(question: str) -> tuple[str, ...]:
    return tuple(k for k, pattern in _MEANING.items() if pattern.search(question))


_SLOT_TOKEN = re.compile(r"\{[a-z]+\}")
_WORD_SPLIT = re.compile(r"[^0-9A-Za-z가-힣]+")
_WORD_TAIL = re.compile(
    r"(에서는|에서도|에서|으로|에는|에도|에|의|은|는|이|가|을|를|도|만|로|쪽|점|역|관|극장|지점)$"
)
# 뜻을 바꾸지 않는 말(부탁·물음 꼬리). 이것만 다르면 같은 질문으로 본다
_FILLER = frozenset(
    "알려줘 알려주세요 알려줄래 보여줘 보여주세요 말해줘 찾아줘 뭐 뭐야 뭐가 뭐있어 뭐해 "
    "있어 있나 있나요 있니 있음 있을까 좀 해줘 줘 주세요 어떤 어느 무슨 혹시 것 거 걸 "
    "하는 한 할 해 하나 돼 되나 인가 인지 궁금해 어때".split()
)


def extra_words(new_template: str, cached_template: str) -> tuple[str, ...]:
    """새 질문에만 있는 낱말. 하나라도 있으면 의미 캐시를 쓰지 않는다(orchestrator).

    슬롯은 스냅샷에 **있는** 극장·영화 이름만 뽑는다. 없는 지점("강남")·없는 영화("주토피아")·
    새 조건("리클라이너관", "액션")은 템플릿 글자에 그대로 남는데, 임베딩은 낱말 하나 차이를
    0.93~0.99로 본다(11/18 실측). 그래서 앞서 물은 질문의 SQL이 그 조건 없이 다시 쓰였다.
    유사도 기준을 올려서는 못 막는다("잠실" 0.985). 낱말로 직접 본다.
    """
    base = normalize(_SLOT_TOKEN.sub(" ", cached_template))
    extra: list[str] = []
    for raw in _WORD_SPLIT.split(_SLOT_TOKEN.sub(" ", new_template)):
        word = raw.lower()
        stem = _WORD_TAIL.sub("", word)
        if word in _FILLER or stem in _FILLER or len(stem) < 2:
            continue
        # 뜻 묶음에 드는 말("이후"↔"넘어서")은 meaning_signature 가 이미 같은지 본다
        if any(pattern.search(word) for pattern in _MEANING.values()):
            continue
        if stem not in base:
            extra.append(stem)
    return tuple(extra)


def is_followup(question: str) -> bool:
    """직전 답을 가리키는 질문인가("그중 건대입구만", "그럼 내일은?")."""
    return bool(_FOLLOW_UP.search(question))


def normalize(text: str) -> str:
    return _PUNCT.sub("", text).lower()


# --- 템플릿 다듬기 (① 같은 뜻 말 맞추기) ---------------------------------------
# 값을 빈칸으로 바꾼 뒤에도 "넘어서 시작하는 … 뭐 있어"처럼 꾸미는 말이 다르면 임베딩 유사도가
# 0.82까지 떨어진다(11/18 실측, 기준 0.92). 비교 전에 같은 뜻의 말은 한 낱말로 맞추고, 질문 뜻에
# 보태지 않는 부탁·물음 꼬리는 지운다. 뜻을 바꾸는 말(이후↔이전, 많은↔적은, 말고 …)은 지우지 않는다.
_CANON = {
    **dict.fromkeys(
        "이후 이후에 이후로 넘어서 넘어 넘은 넘는 지나서 뒤에 후에 부터 부터는".split(), "이후"
    ),
    **dict.fromkeys("이전 이전에 전에 전까지 전".split(), "이전"),
    **dict.fromkeys("영화 영화들 영화는 영화가 영화를 영화도 작품 상영작".split(), "영화"),
    **dict.fromkeys("회차 회차들 회차는 회차가 회차를 상영회차 시간표 상영시간표".split(), "회차"),
}
_DROP = frozenset(
    "알려줘 알려주세요 알려줄래 알려줄수있어 보여줘 보여주세요 보여줄래 찾아줘 찾아주세요 말해줘 "
    "뭐 뭐야 뭐가 뭐있어 뭐있나 뭐있음 무슨 어떤 어떤게 있어 있나 있나요 있니 있음 있을까 있어요 "
    "좀 혹시 그냥 하는 시작하는 상영하는 상영되는 하는거 하는것 하는게 거 것 게 줘 주세요".split()
)
_SLOT_HEAD = re.compile(r"^(\{[a-z]+\})(.+)$")


def canonical(template: str) -> str:
    """템플릿 질문 → 비교용 글자.

    "오늘 {time} 넘어서 시작하는 영화 뭐 있어" → "오늘 {time} 이후 영화".

    "{time}부터"처럼 빈칸에 붙은 말도 본다("{time} 이후"). "{n}개"처럼 맞출 말이 아니면 그대로 둔다.
    """
    words: list[str] = []
    for token in template.split():
        word = token.strip("?？!！.,~'\"")
        head = _SLOT_HEAD.match(word)
        if head and (head.group(2) in _CANON or head.group(2) in _DROP):
            words.append(head.group(1))
            word = head.group(2)
        if not word or word in _DROP:
            continue
        words.append(_CANON.get(word, word))
    return " ".join(words)


def extract(
    question: str,
    now: datetime,
    theaters: Iterable[str] = (),
    movies: Iterable[str] = (),
) -> Extracted:
    text = question.strip()
    slots: dict[str, Slot] = {}
    duplicated = False

    def take(kind: str, pattern_text: str, slot: Slot) -> None:
        nonlocal text, duplicated
        if kind in slots:
            duplicated = True
        slots[kind] = slot
        text = text.replace(pattern_text, "{" + kind + "}", 1)

    today = now.date()

    # 시각 (먼저 — "8시"의 숫자가 개수·날짜로 잡히지 않게)
    for m in list(_TIME_KO.finditer(text)) + list(_TIME_COLON.finditer(text)):
        value = _time_value(m)
        if value is not None:
            take("time", m.group(0).strip(), Slot(value, m.group(0).strip()))

    # 날짜(절대)
    for pattern in (_DATE_MD, _DATE_SLASH):
        for m in list(pattern.finditer(text)):
            value = _month_day(today, int(m.group("mo")), int(m.group("d")))
            if value is not None:
                take("date", m.group(0), Slot(value.isoformat(), m.group(0)))
    for m in list(_WEEKDAY.finditer(text)):
        value = _weekday(today, m.group("wd"), m.group("week"))
        take("date", m.group(0), Slot(value.isoformat(), m.group(0)))
    for m in list(_DATE_D.finditer(text)):
        value = _day_only(today, int(m.group("d")))
        if value is not None:
            take("date", m.group(0), Slot(value.isoformat(), m.group(0)))

    # 개수
    for m in list(_N.finditer(text)):
        n = int(m.group("n1") or m.group("n2"))
        raw = m.group("n1") or m.group("n2")
        if n > 0:
            # 숫자만 빈칸으로 바꾼다("3개" → "{n}개")
            if "n" in slots:
                duplicated = True
            slots["n"] = Slot(n, m.group(0))
            text = text.replace(m.group(0), m.group(0).replace(raw, "{n}", 1), 1)

    # 영화·극장 (스냅샷에 있는 이름만)
    movie_hit = _find_name(text, movies)
    if movie_hit:
        name, raw = movie_hit
        take("movie", raw, Slot(name, raw))
    theater_hit = _find_name(text, theaters) or _find_prefix(text, theaters)
    if theater_hit:
        name, raw = theater_hit
        take("theater", raw, Slot(name, raw))

    relative = frozenset(m.group(0) for m in _RELATIVE.finditer(text))
    # "오늘"과 "내일"이 같이 있으면 SQL의 날짜를 어느 쪽으로 바꿀지 모호하다
    many_days = len({RELATIVE_DAYS[w] for w in relative}) > 1
    text = canonical(text)
    return Extracted(
        question=question,
        template=text,
        key_text=normalize(text),
        slots=slots,
        relative_words=relative,
        cacheable=not duplicated and not many_days,
    )


# --- 값 계산 -------------------------------------------------------------------


def _time_value(m: re.Match[str]) -> str | None:
    h = int(m.group("h"))
    groups = m.groupdict()
    minute = int(groups.get("m") or 0)
    if groups.get("half"):
        minute = 30
    ampm = groups.get("ampm")
    if ampm == "밤" and h == 12:
        h = 0  # 영화관에서 "밤 12시"는 자정(#94 리뷰). 날짜가 바뀌는 문제는 남는다
    elif ampm in ("오후", "저녁", "밤") and h < 12:
        h += 12
    elif ampm == "낮" and h <= 5:
        h += 12
    elif ampm in ("오전", "아침", "새벽") and h == 12:
        h = 0
    if not (0 <= h <= 23 and 0 <= minute <= 59):
        return None
    return f"{h:02d}:{minute:02d}"


def _month_day(today: date, month: int, day: int) -> date | None:
    try:
        value = date(today.year, month, day)
    except ValueError:
        return None
    if value < today - timedelta(days=180):  # 12월에 "1월 3일"을 물으면 내년
        value = date(today.year + 1, month, day)
    return value


def _day_only(today: date, day: int) -> date | None:
    """ "18일" — 이번 달, 이미 지났으면 다음 달."""
    year, month = today.year, today.month
    if day < today.day:
        month += 1
        if month == 13:
            year, month = year + 1, 1
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _weekday(today: date, wd: str, week: str | None) -> date:
    target = _WEEKDAYS.index(wd)
    if week and ("다음" in week or "담" in week):
        monday_next = today - timedelta(days=today.weekday()) + timedelta(days=7)
        return monday_next + timedelta(days=target)
    # "토요일"·"이번 주 토요일" → 오늘 포함 가장 가까운 그 요일
    return today + timedelta(days=(target - today.weekday()) % 7)


# --- 이름 찾기 -----------------------------------------------------------------


def _loose(name: str) -> re.Pattern[str]:
    """띄어쓰기를 무시하고 찾는 패턴("별의항해" = "별의 항해")."""
    chars = [re.escape(c) for c in name if not c.isspace()]
    return re.compile(r"\s*".join(chars))


def _find_name(text: str, names: Iterable[str]) -> tuple[str, str] | None:
    for name in sorted({n for n in names if n and n.strip()}, key=len, reverse=True):
        m = _loose(name).search(text)
        if m:
            return name, m.group(0)
    return None


def _find_prefix(text: str, names: Iterable[str]) -> tuple[str, str] | None:
    """ "건대에서" → "건대입구"처럼 앞부분이 이름 하나에만 맞으면 그 이름."""
    names = [n for n in names if n]
    for token in re.split(r"[\s{}]+", text):
        word = _PARTICLES.sub("", token)
        if len(word) < 2:
            continue
        matches = [n for n in names if n.replace(" ", "").startswith(word)]
        if len(matches) == 1:
            return matches[0], word
    return None
