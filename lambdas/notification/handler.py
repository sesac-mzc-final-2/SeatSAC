"""OPS-07 (3.1.8): SQS → 예매 완료 알림(모의 발송) 로그. DB·외부 호출 없음.

- 입력: SQS 이벤트 소스 매핑(배치 최대 10). 본문은 booking 아웃박스의 envelope 그대로
  (contracts/events/booking.confirmed.v1.json), 메시지 속성은 type·traceId 둘뿐이다.
- 출력: stdout에 JSON 한 줄(docs/conventions.md 6장 로그 규약). CloudWatch Logs Insights가 필드로 읽는다.
  함수 로그 형식은 Text로 둔다 — JSON 형식이면 print한 줄이 문자열로 한 번 더 감싸져 필드로 읽히지 않는다.
- 중복: 같은 eventId가 다시 올 수 있다(아웃박스 재전송·SQS 재수신). 하는 일이 로그뿐이라 그대로 기록하고,
  eventId·receiveCount가 같이 찍히니 로그에서 가려낸다. 별도 저장소(중복 제거 테이블)는 두지 않는다.
- 실패: 메시지 단위로 보고한다(ReportBatchItemFailures). 한 건이 깨져도 나머지는 삭제되고,
  깨진 건만 다시 수신되어 3회째 실패하면 DLQ로 간다 → DLQ 경보(런북 6-B-4).
  계약에 어긋난 메시지(JSON 아님·필수 필드 없음·모르는 type/version)는 다시 받아도 같으므로 DLQ로 가서 사람이 본다.
- 개인정보: 계약상 이메일·이름은 본문에 없다. 카드 정보도 없다. userId(Cognito sub)는 규약대로 로그에 둘 수 있다.
"""
import json
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


class InvalidMessage(Exception):
    """계약에 맞지 않는 메시지. 재시도해도 같은 결과라 DLQ에서 사람이 본다."""


def _emit(level, message, **fields):
    line = {"timestamp": datetime.now(KST).isoformat(timespec="milliseconds"),
            "level": level, "service": SERVICE, "message": message}
    line.update({k: v for k, v in fields.items() if v is not None})
    sys.stdout.write(json.dumps(line, ensure_ascii=False) + "\n")


def _parse(record):
    """본문을 envelope으로 읽고 계약의 필수 항목만 확인한다. 모르는 필드는 무시한다(소비자 규칙)."""
    try:
        body = json.loads(record["body"])
    except (KeyError, TypeError, ValueError) as e:
        raise InvalidMessage(f"본문이 JSON이 아님: {type(e).__name__}") from e
    if not isinstance(body, dict):
        raise InvalidMessage("본문이 객체가 아님")

    missing = [k for k in REQUIRED_ENVELOPE if k not in body]
    if missing:
        raise InvalidMessage(f"envelope 필수 필드 없음: {','.join(missing)}")
    if body["type"] != EXPECTED_TYPE or body["version"] != EXPECTED_VERSION:
        raise InvalidMessage(f"지원하지 않는 type/version: {body['type']}/{body['version']}")
    try:
        uuid.UUID(str(body["eventId"]))
    except ValueError as e:
        raise InvalidMessage("eventId가 UUID가 아님") from e

    payload = body["payload"]
    if not isinstance(payload, dict):
        raise InvalidMessage("payload가 객체가 아님")
    missing = [k for k in REQUIRED_PAYLOAD if k not in payload]
    if missing:
        raise InvalidMessage(f"payload 필수 필드 없음: {','.join(missing)}")
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
