"""질문 → 템플릿 질문 + 슬롯 (3.4.5 ③)."""

from datetime import datetime

import pytest

from seatsac_ai.cache.slots import extract, is_followup, meaning_signature, normalize
from seatsac_ai.snapshot.mapper import KST

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)  # 화요일
THEATERS = ["건대입구", "강남"]
MOVIES = ["별의 항해", "한강의 밤"]


def ex(question):
    return extract(question, NOW, THEATERS, MOVIES)


@pytest.mark.parametrize(
    ("question", "time"),
    [
        ("오늘 20시 이후 영화", "20:00"),
        ("오후 8시 이후 영화", "20:00"),
        ("저녁 8시 반 이후", "20:30"),
        ("20:30 이후 회차", "20:30"),
        ("밤 11시 넘어서", "23:00"),
        ("21시 10분부터", "21:10"),
        ("밤 12시 넘어서", "00:00"),  # 자정(#94 리뷰)
        ("낮 12시 이후", "12:00"),
    ],
)
def test_time(question, time):
    e = ex(question)
    assert e.slots["time"].value == time and "{time}" in e.template


def test_hours_of_running_time_is_not_a_clock_time():
    assert "time" not in ex("2시간 넘는 영화").slots


@pytest.mark.parametrize(
    ("question", "day"),
    [
        ("11월 18일 상영표", "2026-11-18"),
        ("11/21 회차", "2026-11-21"),
        ("21일 회차", "2026-11-21"),
        ("토요일 회차", "2026-11-21"),
        ("화요일 회차", "2026-11-17"),  # 오늘이 화요일
        ("다음 주 토요일 회차", "2026-11-28"),
    ],
)
def test_absolute_dates_are_slots(question, day):
    assert ex(question).slots["date"].value == day


def test_relative_days_stay_in_the_text():
    e = ex("내일 강남 상영표")
    assert "date" not in e.slots and e.relative_words == {"내일"}
    assert e.template == "내일 {theater} 상영표"
    assert e.allowed_offsets() == {0, 1}


def test_theater_by_full_name_and_by_prefix():
    assert ex("강남 회차").slots["theater"].value == "강남"
    slot = ex("건대에서 하는 영화").slots["theater"]
    assert (slot.value, slot.raw) == ("건대입구", "건대")


def test_movie_title_ignores_spaces():
    e = ex("별의항해 언제 해?")
    assert e.slots["movie"].value == "별의 항해" and e.template.startswith("{movie}")


def test_count():
    e = ex("잔여석 많은 회차 3개")
    assert e.slots["n"].value == 3 and "{n}개" in e.template
    assert ex("상위 5 회차").slots["n"].value == 5


def test_same_template_for_different_values():
    a, b = ex("오늘 20시 이후 영화 알려줘"), ex("오늘 21시 이후 영화 알려줘!")
    assert a.key_text == b.key_text and a.slots["time"].value != b.slots["time"].value


def test_two_values_of_one_kind_are_not_cacheable():
    assert not ex("20시부터 22시 사이 회차").cacheable
    assert not ex("오늘이랑 내일 회차").cacheable


def test_normalize_drops_spaces_and_punctuation():
    assert normalize("오늘 {time} 이후 영화?!") == "오늘{time}이후영화"


@pytest.mark.parametrize(
    ("question", "expected"),
    [("그중 건대입구만", True), ("그럼 내일은?", True), ("거기서 제일 늦은 거", True),
     ("오늘 20시 이후 영화", False), ("이중섭 전시", False)],
)  # fmt: skip
def test_followup(question, expected):
    assert is_followup(question) is expected


def test_meaning_signature_separates_opposites():
    assert meaning_signature("20시 이후 영화") == ("after",)
    assert meaning_signature("20시 전에 시작하는 영화") == ("before",)
    assert meaning_signature("잔여석 많은 회차") != meaning_signature("매진 임박 회차")
