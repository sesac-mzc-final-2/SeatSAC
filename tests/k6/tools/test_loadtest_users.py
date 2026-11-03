"""loadtest_users 단위 테스트 — 네트워크 없이 돈다.

    python3 -m unittest discover -s tests/k6/tools -v

SRP는 가짜 Cognito 서버로 확인한다. 서버는 클라이언트와 <b>다른 식</b>(S = (A·v^u)^b)으로 세션 키를 구해
클라이언트 서명을 검증한다 — 두 쪽이 같은 키에 도달해야 통과하므로 패딩·해시·HKDF·서명 형식이 함께 검증된다.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import stat
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import loadtest_users as lt  # noqa: E402

POOL_ID = "ap-northeast-2_TestPool1"
POOL_NAME = "TestPool1"
CLIENT_ID = "testclientid"
def fake_jwt(sub: str = "u", groups: list[str] | None = None) -> str:
    """서명 없는 JWT 모양 — 도구는 본문(cognito:groups)만 읽는다"""
    def part(data: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()
    claims = {"sub": sub, **({"cognito:groups": groups} if groups is not None else {})}
    return f"{part({'alg': 'none'})}.{part(claims)}.sig"


ACCESS_1, ACCESS_2 = fake_jwt("first"), fake_jwt("second")
TIMESTAMP_FORMAT = re.compile(r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) [A-Z][a-z]{2} [1-9]\d? \d{2}:\d{2}:\d{2} UTC \d{4}$")


class FakeCognito:
    """USER_SRP_AUTH·REFRESH_TOKEN_AUTH만 흉내 내는 서버 쪽 SRP"""

    def __init__(self, email: str, password: str) -> None:
        self.email = email
        self.user_id = "8f1c3d2e-0000-4000-8000-000000000001"   # USER_ID_FOR_SRP (email이 아닌 내부 이름)
        self.salt = secrets.token_hex(16)
        x = lt.compute_x(POOL_NAME, self.user_id, password, self.salt)
        self.verifier = pow(lt.G, x, lt.N)
        self.pending: dict | None = None
        self.calls: list[str] = []
        self.throttle_next = 0
        self.login_access = ACCESS_1
        self.refresh_access = ACCESS_2

    def handle(self, action: str, body: dict) -> tuple[int, dict]:
        self.calls.append(action)
        if self.throttle_next > 0:
            self.throttle_next -= 1
            return 400, {"__type": "TooManyRequestsException", "message": "slow down"}
        if body.get("ClientId") != CLIENT_ID:
            return 400, {"__type": "ResourceNotFoundException", "message": "client"}
        if action == "InitiateAuth" and body["AuthFlow"] == "USER_SRP_AUTH":
            return self._start(body["AuthParameters"])
        if action == "InitiateAuth" and body["AuthFlow"] == "REFRESH_TOKEN_AUTH":
            if body["AuthParameters"]["REFRESH_TOKEN"] != "refresh-1":
                return 400, {"__type": "NotAuthorizedException", "message": "bad refresh"}
            return 200, {"AuthenticationResult": {"AccessToken": self.refresh_access, "ExpiresIn": 3600}}
        if action == "RespondToAuthChallenge":
            return self._finish(body["ChallengeResponses"])
        return 400, {"__type": "InvalidParameterException", "message": action}

    def _start(self, params: dict) -> tuple[int, dict]:
        if params["USERNAME"] != self.email:
            return 400, {"__type": "NotAuthorizedException", "message": "user"}
        big_a = int(params["SRP_A"], 16)
        small_b = secrets.randbelow(lt.N)
        big_b = (lt.K * self.verifier + pow(lt.G, small_b, lt.N)) % lt.N
        secret_block = base64.b64encode(secrets.token_bytes(48)).decode()
        self.pending = {"A": big_a, "B": big_b, "b": small_b, "secret": secret_block}
        return 200, {"ChallengeName": "PASSWORD_VERIFIER", "ChallengeParameters": {
            "SALT": self.salt, "SRP_B": format(big_b, "x"), "SECRET_BLOCK": secret_block,
            "USER_ID_FOR_SRP": self.user_id, "USERNAME": self.user_id}}

    def _finish(self, responses: dict) -> tuple[int, dict]:
        pending = self.pending
        assert pending is not None
        if not TIMESTAMP_FORMAT.match(responses["TIMESTAMP"]):
            return 400, {"__type": "InvalidParameterException", "message": "timestamp " + responses["TIMESTAMP"]}
        u = lt.compute_u(pending["A"], pending["B"])
        server_s = pow(pending["A"] * pow(self.verifier, u, lt.N), pending["b"], lt.N)
        key = lt.compute_hkdf(bytes.fromhex(lt.pad_hex(server_s)), bytes.fromhex(lt.pad_hex(format(u, "x"))))
        message = (POOL_NAME + self.user_id).encode() + base64.b64decode(pending["secret"]) + responses["TIMESTAMP"].encode()
        expected = base64.b64encode(hmac.new(key, message, hashlib.sha256).digest()).decode()
        if responses["PASSWORD_CLAIM_SIGNATURE"] != expected or responses["USERNAME"] != self.user_id:
            return 400, {"__type": "NotAuthorizedException", "message": "Incorrect username or password."}
        return 200, {"AuthenticationResult": {"AccessToken": self.login_access, "ExpiresIn": 3600, "RefreshToken": "refresh-1"}}


class FakeServer:
    def __init__(self, cognito: FakeCognito) -> None:
        fake = cognito

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                action = self.headers["X-Amz-Target"].split(".")[-1]
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                status, payload = fake.handle(action, body)
                data = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/x-amz-json-1.1")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args) -> None:
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self) -> str:
        self.thread.start()
        return f"http://127.0.0.1:{self.server.server_port}/"

    def __exit__(self, *exc) -> None:
        self.server.shutdown()
        self.server.server_close()


class SrpTest(unittest.TestCase):
    def test_pad_hex_keeps_values_positive_and_even_length(self) -> None:
        self.assertEqual(lt.pad_hex("abc"), "0abc")      # 홀수 길이
        self.assertEqual(lt.pad_hex("8f"), "008f")       # 최상위 비트 1 → 음수로 읽히지 않게
        self.assertEqual(lt.pad_hex("7f"), "7f")
        self.assertEqual(lt.pad_hex(255), "00ff")

    def test_timestamp_has_no_leading_zero_on_day(self) -> None:
        import datetime as dt
        when = dt.datetime(2026, 11, 1, 9, 5, 3, tzinfo=dt.timezone.utc)
        self.assertEqual(lt.srp_timestamp(when), "Sun Nov 1 09:05:03 UTC 2026")
        self.assertRegex(lt.srp_timestamp(when.replace(day=21)), TIMESTAMP_FORMAT)

    def test_login_agrees_with_server_side_srp(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "lt0123abcd456789a1")
        with FakeServer(cognito) as endpoint:
            tokens = lt.srp_login(lt.CognitoApi("ap-northeast-2", endpoint), CLIENT_ID, POOL_NAME,
                                  "loadtest+001@example.com", "lt0123abcd456789a1")
        self.assertEqual(tokens.access_token, ACCESS_1)
        self.assertEqual(tokens.refresh_token, "refresh-1")
        self.assertGreater(tokens.access_expires_at, time.time() + 3500)

    def test_wrong_password_is_rejected_by_server(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "lt0123abcd456789a1")
        with FakeServer(cognito) as endpoint, self.assertRaises(lt.CognitoError) as raised:
            lt.srp_login(lt.CognitoApi("ap-northeast-2", endpoint), CLIENT_ID, POOL_NAME,
                         "loadtest+001@example.com", "wrong-password-1")
        self.assertEqual(raised.exception.kind, "NotAuthorizedException")

    def test_refresh_keeps_refresh_token_and_retries_throttling(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "x")
        cognito.throttle_next = 2
        with FakeServer(cognito) as endpoint:
            tokens = lt.refresh_access(lt.CognitoApi("ap-northeast-2", endpoint), CLIENT_ID, "refresh-1")
        self.assertEqual(tokens.access_token, ACCESS_2)
        self.assertEqual(tokens.refresh_token, "refresh-1")   # 갱신 응답에는 리프레시 토큰이 없다
        self.assertEqual(cognito.calls.count("InitiateAuth"), 3)


class RefreshUserTest(unittest.TestCase):
    """갱신 실패: 일시 오류면 이전 토큰을 남기고, 거절이면 지운다"""

    def test_transient_failure_keeps_previous_token(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "x")
        cognito.throttle_next = 99   # 재시도를 다 써도 요청 제한
        user = lt.LoadUser("loadtest+001@example.com", "pw", ACCESS_1, 2_000_000.0, "refresh-1")
        with FakeServer(cognito) as endpoint, self.assertRaises(lt.CognitoError):
            lt.refresh_user(lt.CognitoApi("ap-northeast-2", endpoint, retry_delay=0), CLIENT_ID, user)
        self.assertEqual((user.access_token, user.access_expires_at), (ACCESS_1, 2_000_000.0))

    def test_rejected_refresh_clears_previous_token(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "x")
        user = lt.LoadUser("loadtest+001@example.com", "pw", ACCESS_1, 2_000_000.0, "revoked")
        with FakeServer(cognito) as endpoint, self.assertRaises(lt.CognitoError):
            lt.refresh_user(lt.CognitoApi("ap-northeast-2", endpoint, retry_delay=0), CLIENT_ID, user)
        self.assertEqual((user.access_token, user.access_expires_at), (None, 0.0))

    def test_admin_token_on_refresh_clears_previous_token(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "x")
        cognito.refresh_access = fake_jwt("admin", ["ADMIN"])
        user = lt.LoadUser("loadtest+001@example.com", "pw", ACCESS_1, 2_000_000.0, "refresh-1")
        with FakeServer(cognito) as endpoint, self.assertRaises(lt.CognitoError):
            lt.refresh_user(lt.CognitoApi("ap-northeast-2", endpoint, retry_delay=0), CLIENT_ID, user)
        self.assertIsNone(user.access_token)


class AdminTokenTest(unittest.TestCase):
    """create 뒤에 누가 ADMIN 그룹에 넣어도 토큰 클레임(cognito:groups)으로 걸러진다 — AWS 자격 증명 없이"""

    def test_login_refuses_admin_token(self) -> None:
        cognito = FakeCognito("loadtest+001@example.com", "lt0123abcd456789a1")
        cognito.login_access = fake_jwt("admin", ["ADMIN"])
        with FakeServer(cognito) as endpoint, self.assertRaises(lt.CognitoError) as raised:
            lt.refuse_admin_token(lt.srp_login(lt.CognitoApi("ap-northeast-2", endpoint), CLIENT_ID, POOL_NAME,
                                               "loadtest+001@example.com", "lt0123abcd456789a1"))
        self.assertEqual(raised.exception.kind, "AdminGroup")

    def test_tokens_refuse_admin_token_in_state(self) -> None:
        state = lt.State(POOL_ID, CLIENT_ID, "ap-northeast-2", [
            lt.LoadUser("a@example.com", "pw", ACCESS_1, 2_000_000.0),
            lt.LoadUser("b@example.com", "pw", fake_jwt("b", ["USER", "ADMIN"]), 2_000_000.0),
        ])
        with self.assertRaises(SystemExit) as raised:
            lt.usable_tokens(state, 60, None, 1_000_000.0)
        self.assertIn("b@example.com", str(raised.exception))

    def test_groups_are_read_from_jwt_body(self) -> None:
        self.assertEqual(lt.token_groups(fake_jwt("x", ["ADMIN"])), ["ADMIN"])
        self.assertEqual(lt.token_groups(fake_jwt("x")), [])
        with self.assertRaises(ValueError):
            lt.token_groups("not-a-jwt")   # 조용히 통과시키지 않는다


class HelpersTest(unittest.TestCase):
    def test_password_meets_pool_policy(self) -> None:
        for _ in range(20):
            password = lt.new_password()
            self.assertGreaterEqual(len(password), 8)
            self.assertRegex(password, r"[a-z]")
            self.assertRegex(password, r"\d")

    def test_authority_is_split_into_region_and_pool(self) -> None:
        self.assertEqual(lt.parse_authority(f"https://cognito-idp.ap-northeast-2.amazonaws.com/{POOL_ID}"),
                         ("ap-northeast-2", POOL_ID))
        with self.assertRaises(SystemExit):
            lt.parse_authority("https://example.com/x")

    def test_emails_are_zero_padded_and_sortable(self) -> None:
        self.assertEqual(lt.email_of(7), "loadtest+007@example.com")
        self.assertLess(lt.email_of(9), lt.email_of(10))

    def test_state_file_is_private(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "state.json"
            state = lt.State(POOL_ID, CLIENT_ID, "ap-northeast-2", [lt.LoadUser("a@example.com", "pw")])
            state.save(path)
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(os.stat(path.parent).st_mode), 0o700)
            self.assertEqual(lt.State.load(path).users[0].password, "pw")

    def test_existing_directory_permissions_are_left_alone(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory, 0o755)
            lt.State(POOL_ID, CLIENT_ID, "ap-northeast-2").save(Path(directory) / "state.json")
            self.assertEqual(stat.S_IMODE(os.stat(directory).st_mode), 0o755)   # --state ./x.json 같은 경우
            self.assertEqual(stat.S_IMODE(os.stat(Path(directory) / "state.json").st_mode), 0o600)

    def test_tokens_out_writes_one_per_line_privately(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            state_path, out = Path(directory) / "state.json", Path(directory) / "tokens.txt"
            far = time.time() + 3600
            lt.State(POOL_ID, CLIENT_ID, "ap-northeast-2", [
                lt.LoadUser("a@example.com", "pw", ACCESS_1, far), lt.LoadUser("b@example.com", "pw", ACCESS_2, far),
            ]).save(state_path)
            lt.main(["--state", str(state_path), "tokens", "--out", str(out)])
            self.assertEqual(out.read_text().splitlines(), [ACCESS_1, ACCESS_2])
            self.assertEqual(stat.S_IMODE(os.stat(out).st_mode), 0o600)

    def test_network_error_of_one_user_does_not_lose_the_others(self) -> None:
        users = [lt.LoadUser("a@example.com", "pw"), lt.LoadUser("b@example.com", "pw")]

        def work(user: lt.LoadUser) -> None:
            if user.email == "b@example.com":
                raise TimeoutError("timed out")
            user.access_token = "ok"

        errors = lt._for_each(users, work, concurrency=2)
        self.assertEqual(users[0].access_token, "ok")
        self.assertEqual(len(errors), 1)
        self.assertIn("TimeoutError", errors[0])

    def test_tokens_refuse_when_any_expires_soon(self) -> None:
        now = 1_000_000.0
        state = lt.State(POOL_ID, CLIENT_ID, "ap-northeast-2", [
            lt.LoadUser("a@example.com", "pw", ACCESS_1, now + 3600),
            lt.LoadUser("b@example.com", "pw", ACCESS_2, now + 600),
        ])
        self.assertEqual(lt.usable_tokens(state, 300, None, now), [ACCESS_1, ACCESS_2])
        self.assertEqual(lt.usable_tokens(state, 1800, 1, now), [ACCESS_1])   # 앞에서 1개만
        with self.assertRaises(SystemExit):
            lt.usable_tokens(state, 1800, None, now)   # b가 30분 안에 만료


class AdminCommandsTest(unittest.TestCase):
    """create·delete는 aws CLI를 부른다 — 호출을 가로채 순서와 인자만 본다"""

    AUTHORITY = f"https://cognito-idp.ap-northeast-2.amazonaws.com/{POOL_ID}"

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.state_path = Path(self.directory.name) / "state.json"
        self.calls: list[tuple[list[str], dict | None]] = []
        self.pool: set[str] = set()
        self.admins: set[str] = set()
        self.original = lt.aws_cli
        lt.aws_cli = self.fake_aws

    def tearDown(self) -> None:
        lt.aws_cli = self.original
        self.directory.cleanup()

    def fake_aws(self, profile: str, region: str, args: list[str], payload: dict | None = None) -> dict:
        self.assertEqual(profile, "cinema-admin")
        self.calls.append((args, payload))
        if args[0] == "list-users-in-group":
            self.assertEqual(args[args.index("--group-name") + 1], "ADMIN")
            return {"Users": [{"Attributes": [{"Name": "email", "Value": email}]} for email in sorted(self.admins)]}
        if args[0] == "list-users":
            return {"Users": [{"Attributes": [{"Name": "email", "Value": email}]} for email in sorted(self.pool)]}
        if args[0] == "admin-create-user":
            self.pool.add(args[args.index("--username") + 1])
        if args[0] == "admin-delete-user":
            self.pool.discard(args[args.index("--username") + 1])
        return {}

    def run_cli(self, *argv: str) -> None:
        lt.main(["--state", str(self.state_path), *argv])

    def test_create_makes_missing_users_with_permanent_password_outside_argv(self) -> None:
        self.pool.add(lt.email_of(2))   # 2번은 풀에만 있고 상태 파일에는 없다(상태 파일을 잃은 경우)
        self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "3")

        created = [args[args.index("--username") + 1] for args, _ in self.calls if args[0] == "admin-create-user"]
        self.assertEqual(created, [lt.email_of(1), lt.email_of(3)])
        create_args = next(args for args, _ in self.calls if args[0] == "admin-create-user")
        self.assertIn("SUPPRESS", create_args)   # 메일을 보내지 않는다

        passwords = [payload for args, payload in self.calls if args[0] == "admin-set-user-password"]
        self.assertEqual(len(passwords), 3)   # 2번도 비밀번호를 모르니 다시 정한다
        self.assertTrue(all(payload["Permanent"] for payload in passwords))
        for args, _ in self.calls:   # 비밀번호는 명령줄 인자에 나오지 않는다(ps에 보인다)
            self.assertFalse(any(payload["Password"] in args for payload in passwords))

        state = lt.State.load(self.state_path)
        self.assertEqual([user.email for user in state.users], [lt.email_of(i) for i in (1, 2, 3)])
        self.assertEqual({user.password for user in state.users}, {payload["Password"] for payload in passwords})

    def test_create_again_does_nothing_for_known_users(self) -> None:
        self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "2")
        self.calls.clear()
        self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "2")
        self.assertEqual([args[0] for args, _ in self.calls], ["list-users", "list-users-in-group"])

    def test_create_stops_when_a_loadtest_user_is_admin(self) -> None:
        self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "2")
        self.admins.add(lt.email_of(2))   # 누가 손으로 ADMIN에 넣었다
        with self.assertRaises(SystemExit) as raised:
            self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "3")
        self.assertIn(lt.email_of(2), str(raised.exception))
        self.assertNotIn(lt.email_of(3), self.pool)   # 멈추기 전에 아무것도 만들지 않는다

    def test_create_refuses_state_of_another_pool(self) -> None:
        lt.State("ap-northeast-2_Other", CLIENT_ID, "ap-northeast-2").save(self.state_path)
        with self.assertRaises(SystemExit):
            self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "1")

    def test_delete_removes_users_and_clears_state(self) -> None:
        self.run_cli("create", "--authority", self.AUTHORITY, "--client-id", CLIENT_ID, "--count", "2")
        self.run_cli("delete")
        self.assertEqual(self.pool, set())
        self.assertEqual(lt.State.load(self.state_path).users, [])


if __name__ == "__main__":
    unittest.main()
