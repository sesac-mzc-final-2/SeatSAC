"""OPS-07: SQS → 로그. 실패(예외)는 SQS 재시도 → 3회 후 DLQ. DB 접근 없음."""
import json
import logging

log = logging.getLogger()
log.setLevel(logging.INFO)


def handler(event, context):
    for record in event.get("Records", []):
        body = json.loads(record["body"])
        attrs = record.get("messageAttributes", {})
        trace_id = attrs.get("traceId", {}).get("stringValue")
        payload = body.get("payload", {})
        log.info(json.dumps({
            "type": body.get("type"), "traceId": trace_id,
            "bookingId": payload.get("bookingId"), "movieTitle": payload.get("movieTitle"),
            "seatLabels": payload.get("seatLabels"), "message": "예매 완료 알림 (모의 발송)",
        }, ensure_ascii=False))
    return {"ok": True}
