"""알림 Lambda 단위 테스트 (표준 라이브러리만 — Lambda 패키지에는 handler.py만 들어간다).

실행: python3 -m unittest discover -s lambdas/notification -v
예시 메시지(contracts/events/examples)가 서비스 테스트 fixture다(docs/contracts.md 1장).
"""
import contextlib
import copy
import io
import json
import re
import unittest
from pathlib import Path

import handler

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = json.loads((ROOT / "contracts/events/booking.confirmed.v1.json").read_text(encoding="utf-8"))
EXAMPLE = json.loads(
    (ROOT / "contracts/events/examples/booking.confirmed.v1/confirmed.json").read_text(encoding="utf-8"))
EXAMPLE_NULLS = json.loads(
    (ROOT / "contracts/events/examples/booking.confirmed.v1/confirmed-catalog-unavailable.json").read_text(
        encoding="utf-8"))


def record(body, message_id="m-1", receive_count="1", trace_attr=None):
    """SQS 이벤트 소스 매핑이 넘기는 Records 항목."""
    body_text = body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)
    attrs = {"type": {"dataType": "String", "stringValue": "booking.confirmed"}}
    if trace_attr:
        attrs["traceId"] = {"dataType": "String", "stringValue": trace_attr}
    return {"messageId": message_id, "body": body_text, "messageAttributes": attrs,
            "attributes": {"ApproximateReceiveCount": receive_count}}


def run(*records):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        result = handler.handler({"Records": list(records)}, None)
    lines = [json.loads(line) for line in out.getvalue().splitlines() if line.strip()]
    return result, lines


class NotificationHandlerTest(unittest.TestCase):
    def test_valid_message_is_logged_as_one_json_line(self):
        result, lines = run(record(EXAMPLE, trace_attr=EXAMPLE["traceId"]))
        self.assertEqual(result, {"batchItemFailures": []})
        self.assertEqual(len(lines), 1)
        log = lines[0]
        self.assertEqual(log["level"], "INFO")
        self.assertEqual(log["service"], "notification")
        self.assertEqual(log["eventId"], EXAMPLE["eventId"])
        self.assertEqual(log["traceId"], EXAMPLE["traceId"])
        self.assertEqual(log["bookingId"], 9876)
        self.assertEqual(log["screeningId"], 5123)
        self.assertEqual(log["seatCount"], 2)
        self.assertEqual(log["seatLabels"], ["J7", "J8"])
        self.assertEqual(log["receiveCount"], "1")
        self.assertRegex(log["timestamp"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+09:00$")

    def test_log_has_no_personal_or_payment_data(self):
        # 계약상 본문에 이메일·이름·카드가 없다. 금액·극장 정보도 알림 로그에는 필요 없어 남기지 않는다
        _, lines = run(record(EXAMPLE))
        text = json.dumps(lines[0], ensure_ascii=False)
        for forbidden in ("email", "card", "amount", "theaterName"):
            self.assertNotIn(forbidden, text)

    def test_unknown_fields_are_ignored(self):
        body = copy.deepcopy(EXAMPLE)
        body["futureField"] = {"x": 1}
        body["payload"]["couponCode"] = "NEW"
        result, lines = run(record(body))
        self.assertEqual(result["batchItemFailures"], [])
        self.assertEqual(lines[0]["level"], "INFO")

    def test_duplicate_delivery_is_logged_again_without_failure(self):
        # 같은 eventId 재전송(아웃박스)·재수신(SQS) — 로그만 남기므로 실패가 아니다
        result, lines = run(record(EXAMPLE, "m-1", "1"), record(EXAMPLE, "m-2", "2"))
        self.assertEqual(result["batchItemFailures"], [])
        self.assertEqual([l["eventId"] for l in lines], [EXAMPLE["eventId"]] * 2)
        self.assertEqual([l["receiveCount"] for l in lines], ["1", "2"])

    def test_only_broken_messages_are_reported_as_failures(self):
        no_payload_field = copy.deepcopy(EXAMPLE)
        del no_payload_field["payload"]["screeningId"]
        result, lines = run(
            record(EXAMPLE, "ok-1"),
            record("not json", "bad-json"),
            record(no_payload_field, "bad-payload"),
            record(EXAMPLE, "ok-2"),
        )
        self.assertEqual(result["batchItemFailures"],
                         [{"itemIdentifier": "bad-json"}, {"itemIdentifier": "bad-payload"}])
        self.assertEqual([l["level"] for l in lines], ["INFO", "ERROR", "ERROR", "INFO"])
        self.assertIn("screeningId", lines[2]["error"])

    def test_wrong_type_or_version_fails(self):
        for change in ({"type": "booking.canceled"}, {"version": 2}):
            body = {**EXAMPLE, **change}
            result, _ = run(record(body, "m-x"))
            self.assertEqual(result["batchItemFailures"], [{"itemIdentifier": "m-x"}], change)

    def test_event_id_must_be_uuid(self):
        result, lines = run(record({**EXAMPLE, "eventId": "not-a-uuid"}, "m-x"))
        self.assertEqual(result["batchItemFailures"], [{"itemIdentifier": "m-x"}])
        self.assertIn("UUID", lines[0]["error"])

    def test_non_object_body_fails(self):
        result, _ = run(record("[1, 2]", "m-x"))
        self.assertEqual(result["batchItemFailures"], [{"itemIdentifier": "m-x"}])

    def test_error_log_falls_back_to_message_attribute_trace_id(self):
        broken = {k: v for k, v in EXAMPLE.items() if k != "payload"}
        trace = "a" * 32
        result, lines = run(record(broken, "m-x", trace_attr=trace))
        self.assertEqual(result["batchItemFailures"], [{"itemIdentifier": "m-x"}])
        self.assertEqual(lines[0]["traceId"], trace)

    def test_catalog_unavailable_example_with_nulls_succeeds(self):
        # 계약상 null 허용 4개(movieTitle·theaterName·auditoriumName·startsAt)는 정상 메시지다 → DLQ로 보내면 안 된다
        result, lines = run(record(EXAMPLE_NULLS))
        self.assertEqual(result, {"batchItemFailures": []})
        self.assertEqual(lines[0]["level"], "INFO")
        self.assertNotIn("movieTitle", lines[0])

    def test_nullable_fields_match_the_contract(self):
        props = SCHEMA["properties"]["payload"]["properties"]
        nullable = {k for k, v in props.items() if isinstance(v.get("type"), list) and "null" in v["type"]}
        self.assertEqual(set(handler.NULLABLE_PAYLOAD), nullable)

    def test_contract_violating_values_fail(self):
        # 키는 있지만 값이 계약(타입·범위·null·형식)에 어긋나면 DLQ 경로로 보낸다
        payload_cases = {
            "bookingId": [None, "9876", 1.5, True],
            "userId": [None, "abc", 123],
            "screeningId": [None, "5123"],
            "amount": [None, "abc", 15000.5],
            "seatCount": [None, 0, 5, 99, "2", True],
            "seatLabels": [None, "J7", [1], ["J7", None]],
            "confirmedAt": [None, "어제", 20261116, "2026-11-16", "2026-11-16T14:04:10", "2026-11-16 14:04:10+09:00", "20261116T140410+0900", "2026-13-01T00:00:00+09:00", "2026-11-16T25:00:00+09:00", "２０２６-11-16T14:04:10+09:00"],
            "movieTitle": [123, ["x"]],
            "startsAt": ["내일", 123, "2026-11-16", "2026-11-16T14:04:10", "2026-11-16 14:04:10+09:00", "20261116T140410+0900", "2026-13-01T00:00:00+09:00", "2026-11-16T25:00:00+09:00", "２０２６-11-16T14:04:10+09:00"],
        }
        envelope_cases = {
            "producer": [None, "other"],
            "traceId": [None, "xyz", "A" * 32, "a" * 31],
            "occurredAt": [None, "어제", "2026-11-16", "2026-11-16T14:04:10", "2026-11-16 14:04:10+09:00", "20261116T140410+0900", "2026-13-01T00:00:00+09:00", "2026-11-16T25:00:00+09:00", "２０２６-11-16T14:04:10+09:00"],
        }
        for field, values in payload_cases.items():
            for value in values:
                body = copy.deepcopy(EXAMPLE)
                body["payload"][field] = value
                result, lines = run(record(body, "m-x"))
                self.assertEqual(result["batchItemFailures"], [{"itemIdentifier": "m-x"}], (field, value))
                self.assertIn(field, lines[0]["error"])
        for field, values in envelope_cases.items():
            for value in values:
                result, lines = run(record({**EXAMPLE, field: value}, "m-x"))
                self.assertEqual(result["batchItemFailures"], [{"itemIdentifier": "m-x"}], (field, value))
                self.assertIn(field, lines[0]["error"])

    def test_rfc3339_variants_are_accepted(self):
        # 과하게 막지 않는다: Z·소수 초·다른 오프셋·소문자 t/z 는 RFC 3339 라 정상 메시지다
        for stamp in ("2026-11-16T05:04:10Z", "2026-11-16T14:04:10.123+09:00", "2026-11-16T14:04:10.123456789+09:00",
                      "2026-11-16T14:04:10+00:00", "2026-11-15T21:04:10-08:00", "2026-11-16t05:04:10z"):
            body = copy.deepcopy(EXAMPLE)
            body["occurredAt"] = body["payload"]["confirmedAt"] = body["payload"]["startsAt"] = stamp
            result, _ = run(record(body, "m-x"))
            self.assertEqual(result, {"batchItemFailures": []}, stamp)

    def test_error_log_does_not_contain_the_bad_value(self):
        body = copy.deepcopy(EXAMPLE)
        body["payload"]["userId"] = "SECRET-LOOKING-VALUE"
        _, lines = run(record(body, "m-x"))
        self.assertIn("userId", lines[0]["error"])
        self.assertNotIn("SECRET-LOOKING-VALUE", json.dumps(lines[0]))

    def test_empty_event(self):
        for event in ({}, {"Records": []}):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                self.assertEqual(handler.handler(event, None), {"batchItemFailures": []})
            self.assertEqual(out.getvalue(), "")

    def test_required_fields_match_the_contract(self):
        # 계약(JSON Schema)의 필수 필드가 바뀌면 이 목록도 같이 고쳐야 한다
        self.assertEqual(set(handler.REQUIRED_ENVELOPE), set(SCHEMA["required"]))
        self.assertEqual(set(handler.REQUIRED_PAYLOAD), set(SCHEMA["properties"]["payload"]["required"]))
        self.assertEqual(handler.EXPECTED_TYPE, SCHEMA["properties"]["type"]["const"])
        self.assertEqual(handler.EXPECTED_VERSION, SCHEMA["properties"]["version"]["const"])

    def test_example_matches_contract_trace_id_pattern(self):
        self.assertRegex(EXAMPLE["traceId"], SCHEMA["properties"]["traceId"]["pattern"])
        self.assertTrue(re.fullmatch(r"[0-9a-f]{32}", EXAMPLE["traceId"]))


if __name__ == "__main__":
    unittest.main()
