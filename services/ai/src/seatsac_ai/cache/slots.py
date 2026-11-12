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


def is_followup(question: str) -> bool:
    """직전 답을 가리키는 질문인가("그중 건대입구만", "그럼 내일은?")."""
    return bool(_FOLLOW_UP.search(question))


def normalize(text: str) -> str:
    return _PUNCT.sub("", text).lower()


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
