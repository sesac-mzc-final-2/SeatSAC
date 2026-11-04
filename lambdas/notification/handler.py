"""OPS-07 (3.1.8): SQS → 예매 완료 알림(모의 발송) 로그. DB·외부 호출 없음.

- 입력: SQS 이벤트 소스 매핑(배치 최대 10). 본문은 booking 아웃박스의 envelope 그대로
  (contracts/events/booking.confirmed.v1.json), 메시지 속성은 type·traceId 둘뿐이다.
- 출력: stdout에 JSON 한 줄(docs/conventions.md 6장 로그 규약). CloudWatch Logs Insights가 필드로 읽는다.
  함수 로그 형식은 Text로 둔다 — JSON 형식이면 print한 줄이 문자열로 한 번 더 감싸져 필드로 읽히지 않는다.
- 중복: 같은 eventId가 다시 올 수 있다(아웃박스 재전송·SQS 재수신). 하는 일이 로그뿐이라 그대로 기록하고,
  eventId·receiveCount가 같이 찍히니 로그에서 가려낸다. 별도 저장소(중복 제거 테이블)는 두지 않는다.
- 실패: 메시지 단위로 보고한다(ReportBatchItemFailures). 한 건이 깨져도 나머지는 삭제되고,
  깨진 건만 다시 수신되어 3회째 실패하면 DLQ로 간다 → DLQ 경보(런북 6-B-4).
  계약에 어긋난 메시지(JSON 아님·필수 필드 없음·필드 값의 타입/범위/null 위반·모르는 type/version)는
  다시 받아도 같으므로 DLQ로 가서 사람이 본다. null은 Catalog 조회 실패 필드 4개(NULLABLE_PAYLOAD)만 허용된다.
- 개인정보: 계약상 이메일·이름은 본문에 없다. 카드 정보도 없다. userId(Cognito sub)는 규약대로 로그에 둘 수 있다.
"""
import json
import re
import sys
import uuid
from datetime import datetime, timedelta, timezone

SERVICE = "notification"
KST = timezone(timedelta(hours=9))  # 로그 timestamp는 +09:00 (conventions 6장)

EXPECTED_TYPE = "booking.confirmed"
EXPECTED_VERSION = 1
REQUIRED_ENVELOPE = ("eventId", "type", "version", "occurredAt", "producer", "traceId", "payload")
REQUIRED_PAYLOAD = (
    "bookingId", "userId", "screeningId", "movieTitle", "theaterName", "auditoriumName",
    "startsAt", "seatLabels", "seatCount", "amount", "confirmedAt",
)


TRACE_ID = re.compile(r"^[0-9a-f]{32}$")  # 계약의 traceId pattern
# [0-9]로 쓴다: 파이썬 \d는 전각 숫자("２０２６")도 받는다
DATE_TIME = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?([Zz]|[+-][0-9]{2}:[0-5][0-9])$")
# 계약상 null 허용: Catalog 조회 실패 시 null (examples/confirmed-catalog-unavailable.json). 나머지 필수 필드는 null 불가
NULLABLE_PAYLOAD = ("movieTitle", "theaterName", "auditoriumName", "startsAt")


class InvalidMessage(Exception):
    """계약에 맞지 않는 메시지. 재시도해도 같은 결과라 DLQ에서 사람이 본다."""


def _emit(level, message, **fields):
    line = {"timestamp": datetime.now(KST).isoformat(timespec="milliseconds"),
            "level": level, "service": SERVICE, "message": message}
    line.update({k: v for k, v in fields.items() if v is not None})
    sys.stdout.write(json.dumps(line, ensure_ascii=False) + "\n")


def _is_int(v):
    # JSON Schema integer: 1.0처럼 값이 정수인 float은 허용, bool(True/False)은 거부
    if isinstance(v, bool):  # Python은 True도 int
        return False
    if isinstance(v, int):
        return True
    return isinstance(v, float) and v.is_integer()


def _is_uuid(v):
    try:
        return isinstance(v, str) and str(uuid.UUID(v)) == v.lower()
    except ValueError:
        return False


def _is_datetime(v):
    """JSON Schema format: date-time = RFC 3339. 날짜 T 시각 + 시간대(Z 또는 ±hh:mm)가 모두 있어야 한다.
    fromisoformat만 쓰면 날짜만("2026-11-16")·시간대 없음·공백 구분·"20261116T140410+0900"까지 통과한다 →
    모양은 정규식으로, 월·일·시·오프셋 범위는 fromisoformat으로 확인한다. (윤초 :60은 booking(Java Instant)이 만들 수 없어 거부한다)"""
    if not isinstance(v, str) or not DATE_TIME.match(v):
        return False
    try:
        datetime.fromisoformat(v.upper())  # 3.12: Z·소수 초 허용. t/z 소문자는 RFC 3339 허용이라 대문자로 맞춘다
        return True
    except ValueError:
        return False


def _check_values(body, payload):
    """필수 필드의 값(타입·범위·null)을 계약대로 확인한다. 오류에는 필드 이름만 담는다(값이 로그에 남지 않게)."""
    bad = []

    def check(name, ok):
        if not ok:
            bad.append(name)

    check("producer", body["producer"] == "booking")
    check("traceId", isinstance(body["traceId"], str) and bool(TRACE_ID.match(body["traceId"])))
    check("occurredAt", _is_datetime(body["occurredAt"]))
    check("bookingId", _is_int(payload["bookingId"]))
    check("userId", _is_uuid(payload["userId"]))
    check("screeningId", _is_int(payload["screeningId"]))
    check("amount", _is_int(payload["amount"]))
    check("seatCount", _is_int(payload["seatCount"]) and 1 <= payload["seatCount"] <= 4)
    check("seatLabels", isinstance(payload["seatLabels"], list)
          and all(isinstance(s, str) for s in payload["seatLabels"]))
    check("confirmedAt", _is_datetime(payload["confirmedAt"]))
    for k in NULLABLE_PAYLOAD:  # null 허용, 값이 있으면 문자열(startsAt은 date-time)
        v = payload[k]
        check(k, v is None or (_is_datetime(v) if k == "startsAt" else isinstance(v, str)))
    if bad:
        raise InvalidMessage(f"계약 위반 필드(값 오류): {','.join(bad)}")


def _parse(record):
    """본문을 envelope으로 읽고 계약의 필수 항목(있는지 + 타입·범위·null)을 확인한다. 모르는 필드는 무시한다(소비자 규칙)."""
    try:
        body = json.loads(record["body"])
    except (KeyError, TypeError, ValueError) as e:
        raise InvalidMessage(f"본문이 JSON이 아님: {type(e).__name__}") from e
    if not isinstance(body, dict):
        raise InvalidMessage("본문이 객체가 아님")

    missing = [k for k in REQUIRED_ENVELOPE if k not in body]
    if missing:
        raise InvalidMessage(f"envelope 필수 필드 없음: {','.join(missing)}")
    if (body["type"] != EXPECTED_TYPE
        or not _is_int(body["version"])
        or body["version"] != EXPECTED_VERSION):     # 1.0 == 1 이라 통과
        raise InvalidMessage(f"지원하지 않는 type/version: {body['type']}/{body['version']}")
    if not _is_uuid(body["eventId"]):
        raise InvalidMessage("eventId가 UUID가 아님")

    payload = body["payload"]
    if not isinstance(payload, dict):
        raise InvalidMessage("payload가 객체가 아님")
    missing = [k for k in REQUIRED_PAYLOAD if k not in payload]
    if missing:
        raise InvalidMessage(f"payload 필수 필드 없음: {','.join(missing)}")
    _check_values(body, payload)
    return body


def _trace_id(record, body):
    """본문의 traceId가 기준이고, 없으면 메시지 속성(둘은 같은 값)."""
    if body and body.get("traceId"):
        return body["traceId"]
    return record.get("messageAttributes", {}).get("traceId", {}).get("stringValue")


def handler(event, context):
    failures = []
    for record in event.get("Records", []):
        message_id = record.get("messageId")
        receive_count = record.get("attributes", {}).get("ApproximateReceiveCount")
        body = None
        try:
            body = _parse(record)
            payload = body["payload"]
            _emit("INFO", "예매 완료 알림 (모의 발송)",
                  traceId=_trace_id(record, body),
                  eventId=body["eventId"], type=body["type"], occurredAt=body["occurredAt"],
                  bookingId=payload["bookingId"], userId=payload["userId"],
                  screeningId=payload["screeningId"], seatCount=payload["seatCount"],
                  seatLabels=payload["seatLabels"], movieTitle=payload["movieTitle"],
                  messageId=message_id, receiveCount=receive_count)
        except Exception as e:  # noqa: BLE001 — 어떤 오류든 이 메시지만 실패로 보고하고 나머지는 계속 처리한다
            _emit("ERROR", "예매 완료 알림 처리 실패",
                  traceId=_trace_id(record, body),
                  eventId=body.get("eventId") if isinstance(body, dict) else None,
                  messageId=message_id, receiveCount=receive_count,
                  error=f"{type(e).__name__}: {e}")
            failures.append({"itemIdentifier": message_id})
    return {"batchItemFailures": failures}
