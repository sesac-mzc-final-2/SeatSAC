#!/usr/bin/env python3
"""4.2.1 부하 테스트용 Cognito 계정·토큰 준비 (OPS-10).

booking은 사용자당 회차별 선점을 1건만 허용한다(user-hold). 같은 좌석 100명 경쟁을 재려면
서로 다른 사용자 100명의 Access Token이 필요하다. 이 도구는 그 계정을 만들고 토큰을 미리 받아 둔다
— k6 안에서는 로그인하지 않는다(Cognito 인증 API 요청 제한, WBS 4.2.1 메모).

명령
  create   계정 만들기·비밀번호 고정 (관리자 역할 필요: AdminCreateUser·AdminSetUserPassword)
  login    SRP 로그인으로 토큰 받기 (AWS 자격 증명 불필요 — 공개 앱 클라이언트)
  refresh  리프레시 토큰으로 Access Token만 새로 받기 (자격 증명 불필요, 로그인 호출 없음)
  tokens   K6_TOKENS_FILE 파일(--out, 줄당 1개, 권한 600) 또는 K6_TOKENS 값(쉼표) 출력. 곧 만료되면 실패
  status   계정 수·가장 이른 만료 시각
  delete   계정 삭제 (관리자 역할 필요)

비밀번호·토큰은 레포 밖 상태 파일(기본 ~/.seatsac/loadtest-users.json, 권한 600)에만 둔다.
절대 커밋하지 않는다(발표 후 레포 공개). 표준 라이브러리만 쓴다 — 관리 API만 aws CLI를 부른다.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from pathlib import Path

DEFAULT_STATE = Path.home() / ".seatsac" / "loadtest-users.json"
DEFAULT_PROFILE = "cinema-admin"
EMAIL_PREFIX = "loadtest+"
EMAIL_DOMAIN = "example.com"

# ---------------------------------------------------------------------------
# SRP (amazon-cognito-identity-js AuthenticationHelper와 같은 규칙)
# ---------------------------------------------------------------------------

N_HEX = (
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C"
    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
)
N = int(N_HEX, 16)
G = 2
INFO_BITS = b"Caldera Derived Key"


def pad_hex(value: int | str) -> str:
    """부호 있는 큰 정수로 읽히지 않게 맞춘 16진수 — 홀수 길이면 0, 최상위 비트가 1이면 00을 붙인다."""
    text = value if isinstance(value, str) else format(value, "x")
    if len(text) % 2 == 1:
        return "0" + text
    if text[0] in "89abcdefABCDEF":
        return "00" + text
    return text


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hex_hash(hex_text: str) -> str:
    return sha256_hex(bytes.fromhex(hex_text))


K = int(hex_hash("00" + N_HEX + "0" + format(G, "x")), 16)


def pool_name_of(user_pool_id: str) -> str:
    """ap-northeast-2_AbCd → AbCd (SRP 해시에 쓰는 이름)"""
    return user_pool_id.split("_", 1)[1]


def compute_hkdf(ikm: bytes, salt: bytes) -> bytes:
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, INFO_BITS + b"\x01", hashlib.sha256).digest()[:16]


def compute_x(pool_name: str, user_id: str, password: str, salt_hex: str) -> int:
    inner = sha256_hex(f"{pool_name}{user_id}:{password}".encode("utf-8"))
    return int(hex_hash(pad_hex(salt_hex) + inner), 16)


def compute_u(big_a: int, big_b: int) -> int:
    return int(hex_hash(pad_hex(big_a) + pad_hex(big_b)), 16)


def srp_timestamp(now: dt.datetime) -> str:
    """Cognito가 요구하는 형식: 'Sun Nov 1 09:05:03 UTC 2026' (일자 앞 0 없음, 영문 고정)"""
    days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    return (
        f"{days[now.weekday()]} {months[now.month - 1]} {now.day} "
        f"{now.hour:02d}:{now.minute:02d}:{now.second:02d} UTC {now.year}"
    )


@dataclass
class SrpClient:
    """한 번의 SRP 로그인. a는 로그인마다 새로 만든다."""

    small_a: int = field(default_factory=lambda: int.from_bytes(secrets.token_bytes(128), "big") % N)

    def __post_init__(self) -> None:
        self.big_a = pow(G, self.small_a, N)
        if self.big_a % N == 0:
            raise ValueError("SRP A가 0이다 — 다시 시도")

    def srp_a_hex(self) -> str:
        return format(self.big_a, "x")

    def session_key(self, pool_name: str, user_id: str, password: str, salt_hex: str, srp_b_hex: str) -> bytes:
        big_b = int(srp_b_hex, 16)
        if big_b % N == 0:
            raise ValueError("SRP B가 0이다")
        u = compute_u(self.big_a, big_b)
        if u == 0:
            raise ValueError("SRP u가 0이다")
        x = compute_x(pool_name, user_id, password, salt_hex)
        s = pow(big_b - K * pow(G, x, N), self.small_a + u * x, N)
        return compute_hkdf(bytes.fromhex(pad_hex(s)), bytes.fromhex(pad_hex(format(u, "x"))))

    def challenge_responses(
        self, pool_name: str, password: str, params: dict[str, str], now: dt.datetime
    ) -> dict[str, str]:
        """PASSWORD_VERIFIER 응답. params는 InitiateAuth가 돌려준 ChallengeParameters"""
        user_id = params["USER_ID_FOR_SRP"]
        key = self.session_key(pool_name, user_id, password, params["SALT"], params["SRP_B"])
        timestamp = srp_timestamp(now)
        message = (
            pool_name.encode("utf-8")
            + user_id.encode("utf-8")
            + base64.b64decode(params["SECRET_BLOCK"])
            + timestamp.encode("utf-8")
        )
        signature = base64.b64encode(hmac.new(key, message, hashlib.sha256).digest()).decode("ascii")
        return {
            "TIMESTAMP": timestamp,
            "USERNAME": user_id,
            "PASSWORD_CLAIM_SECRET_BLOCK": params["SECRET_BLOCK"],
            "PASSWORD_CLAIM_SIGNATURE": signature,
        }


# ---------------------------------------------------------------------------
# Cognito 공개 API (서명 없이 호출 — InitiateAuth·RespondToAuthChallenge)
# ---------------------------------------------------------------------------


class CognitoError(Exception):
    def __init__(self, kind: str, message: str) -> None:
        super().__init__(f"{kind}: {message}")
        self.kind = kind


class CognitoApi:
    RETRYABLE = {"TooManyRequestsException", "InternalErrorException", "ServiceUnavailable"}

    def __init__(self, region: str, endpoint: str | None = None) -> None:
        self.endpoint = endpoint or f"https://cognito-idp.{region}.amazonaws.com/"

    def call(self, action: str, body: dict) -> dict:
        delay = 0.5
        for attempt in range(6):
            request = urllib.request.Request(
                self.endpoint,
                data=json.dumps(body).encode("utf-8"),
                headers={
                    "Content-Type": "application/x-amz-json-1.1",
                    "X-Amz-Target": f"AWSCognitoIdentityProviderService.{action}",
                },
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    return json.loads(response.read() or b"{}")
            except urllib.error.HTTPError as error:
                with error:
                    payload = json.loads(error.read() or b"{}")
                kind = str(payload.get("__type", f"HTTP{error.code}")).split("#")[-1]
                if kind in self.RETRYABLE and attempt < 5:
                    time.sleep(delay)
                    delay *= 2
                    continue
                raise CognitoError(kind, payload.get("message", "")) from None
        raise CognitoError("RetryExhausted", action)


@dataclass
class Tokens:
    access_token: str
    access_expires_at: float
    refresh_token: str | None


def srp_login(api: CognitoApi, client_id: str, pool_name: str, email: str, password: str) -> Tokens:
    client = SrpClient()
    started = api.call(
        "InitiateAuth",
        {"AuthFlow": "USER_SRP_AUTH", "ClientId": client_id,
         "AuthParameters": {"USERNAME": email, "SRP_A": client.srp_a_hex()}},
    )
    if started.get("ChallengeName") != "PASSWORD_VERIFIER":
        raise CognitoError("UnexpectedChallenge", str(started.get("ChallengeName")))
    responses = client.challenge_responses(
        pool_name, password, started["ChallengeParameters"], dt.datetime.now(dt.timezone.utc))
    body = {"ChallengeName": "PASSWORD_VERIFIER", "ClientId": client_id, "ChallengeResponses": responses}
    if started.get("Session"):
        body["Session"] = started["Session"]
    finished = api.call("RespondToAuthChallenge", body)
    if "AuthenticationResult" not in finished:
        # NEW_PASSWORD_REQUIRED 등 — create가 비밀번호를 고정(permanent)하지 않은 계정
        raise CognitoError("UnexpectedChallenge", str(finished.get("ChallengeName")))
    return _tokens_of(finished["AuthenticationResult"], refresh_fallback=None)


def token_groups(access_token: str) -> list[str]:
    """Access Token(JWT) 본문의 cognito:groups. 서명은 검증하지 않는다 — 방금 받은 우리 토큰의 내용만 본다.
    JWT 모양이 아니면 ValueError (조용히 통과시키지 않는다)"""
    parts = access_token.split(".")
    if len(parts) != 3:
        raise ValueError("Access Token이 JWT 모양이 아니다")
    body = parts[1] + "=" * (-len(parts[1]) % 4)
    claims = json.loads(base64.urlsafe_b64decode(body))
    groups = claims.get("cognito:groups") or []
    return [str(group) for group in groups] if isinstance(groups, list) else [str(groups)]


def refuse_admin_token(tokens: Tokens) -> Tokens:
    """ADMIN 그룹 토큰은 쓰지 않는다. create 뒤에 누가 그룹에 넣었어도 여기서 걸린다 —
    관리자 API(ListUsersInGroup) 대신 토큰 클레임을 보므로 login·refresh에 AWS 자격 증명이 필요 없다"""
    if ADMIN_GROUP in token_groups(tokens.access_token):
        raise CognitoError("AdminGroup", "ADMIN 그룹 계정이다 — 그룹에서 빼고 다시 로그인한다")
    return tokens


def refresh_access(api: CognitoApi, client_id: str, refresh_token: str) -> Tokens:
    result = api.call(
        "InitiateAuth",
        {"AuthFlow": "REFRESH_TOKEN_AUTH", "ClientId": client_id,
         "AuthParameters": {"REFRESH_TOKEN": refresh_token}},
    )
    return _tokens_of(result["AuthenticationResult"], refresh_fallback=refresh_token)


def _tokens_of(result: dict, refresh_fallback: str | None) -> Tokens:
    return Tokens(
        access_token=result["AccessToken"],
        access_expires_at=time.time() + int(result.get("ExpiresIn", 3600)),
        refresh_token=result.get("RefreshToken", refresh_fallback),
    )


# ---------------------------------------------------------------------------
# 상태 파일 (레포 밖, 권한 600)
# ---------------------------------------------------------------------------


@dataclass
class LoadUser:
    email: str
    password: str
    access_token: str | None = None
    access_expires_at: float = 0.0
    refresh_token: str | None = None


@dataclass
class State:
    user_pool_id: str
    client_id: str
    region: str
    users: list[LoadUser] = field(default_factory=list)

    @staticmethod
    def load(path: Path) -> State:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return State(raw["user_pool_id"], raw["client_id"], raw["region"],
                     [LoadUser(**user) for user in raw.get("users", [])])

    def save(self, path: Path) -> None:
        # 새로 만드는 디렉터리만 700으로 둔다 — --state ./x.json·/tmp/x.json처럼 기존 디렉터리의 권한은 건드리지 않는다
        # (파일 자체는 아래에서 600으로 만든다)
        if not path.parent.exists():
            path.parent.mkdir(mode=0o700, parents=True)
            os.chmod(path.parent, 0o700)
        # 권한 600으로 새로 쓴 뒤 바꿔 끼운다 — 잠깐이라도 다른 사용자가 읽을 수 있는 파일을 만들지 않는다
        fd, temp = tempfile.mkstemp(dir=path.parent, prefix=".loadtest-", suffix=".json")
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(asdict(self), handle, ensure_ascii=False, indent=1)
        os.chmod(temp, 0o600)
        os.replace(temp, path)


def email_of(index: int) -> str:
    return f"{EMAIL_PREFIX}{index:03d}@{EMAIL_DOMAIN}"


def new_password() -> str:
    """풀 정책(8자 이상·소문자·숫자) 충족. 소문자·숫자를 끝에 붙여 우연히 빠지는 일이 없게 한다"""
    return f"lt{secrets.token_hex(12)}a1"


def parse_authority(authority: str) -> tuple[str, str]:
    """https://cognito-idp.{region}.amazonaws.com/{userPoolId} → (region, userPoolId)"""
    match = re.fullmatch(r"https://cognito-idp\.([a-z0-9-]+)\.amazonaws\.com/([a-z0-9-]+_[A-Za-z0-9]+)/?", authority)
    if not match:
        raise SystemExit(f"--authority 형식이 아니다: {authority}")
    return match.group(1), match.group(2)


# ---------------------------------------------------------------------------
# 관리 API (aws CLI — 관리자 역할의 MFA·역할 전환을 CLI가 처리한다)
# ---------------------------------------------------------------------------


def aws_cli(profile: str, region: str, args: list[str], payload: dict | None = None) -> dict:
    command = ["aws", "--profile", profile, "--region", region, "--output", "json", "cognito-idp", *args]
    temp_path = None
    try:
        if payload is not None:
            # 비밀번호를 명령줄 인자로 넘기지 않는다(ps에 보인다) — 권한 600 임시 파일로 넘긴다
            fd, temp_path = tempfile.mkstemp(prefix="loadtest-", suffix=".json")
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle)
            command += ["--cli-input-json", f"file://{temp_path}"]
        done = subprocess.run(command, capture_output=True, text=True, check=False)
    finally:
        if temp_path:
            os.unlink(temp_path)
    if done.returncode != 0:
        raise SystemExit(f"aws cognito-idp {args[0]} 실패: {done.stderr.strip()}")
    return json.loads(done.stdout) if done.stdout.strip() else {}


ADMIN_GROUP = "ADMIN"


def admin_loadtest_emails(profile: str, state: State) -> list[str]:
    """ADMIN 그룹에 들어 있는 부하 테스트 계정. 있으면 그 토큰이 관리자 API를 부를 수 있다"""
    listed = aws_cli(profile, state.region, [
        "list-users-in-group", "--user-pool-id", state.user_pool_id, "--group-name", ADMIN_GROUP])
    emails = []
    for user in listed.get("Users", []):
        for attribute in user.get("Attributes", []):
            if attribute["Name"] == "email" and attribute["Value"].startswith(EMAIL_PREFIX):
                emails.append(attribute["Value"])
    return sorted(emails)


def existing_emails(profile: str, state: State) -> set[str]:
    listed = aws_cli(profile, state.region, [
        "list-users", "--user-pool-id", state.user_pool_id,
        "--filter", f'email ^= "{EMAIL_PREFIX}"', "--attributes-to-get", "email"])
    emails = set()
    for user in listed.get("Users", []):
        for attribute in user.get("Attributes", []):
            if attribute["Name"] == "email":
                emails.add(attribute["Value"])
    return emails


# ---------------------------------------------------------------------------
# 명령
# ---------------------------------------------------------------------------


def cmd_create(args: argparse.Namespace) -> None:
    region, pool_id = parse_authority(args.authority)
    state = State.load(args.state) if args.state.exists() else State(pool_id, args.client_id, region)
    if (state.user_pool_id, state.client_id) != (pool_id, args.client_id):
        raise SystemExit(f"상태 파일이 다른 사용자 풀·클라이언트용이다: {args.state} — 다른 --state를 쓰거나 delete 후 다시")
    known = {user.email: user for user in state.users}
    in_pool = existing_emails(args.profile, state)
    admins = admin_loadtest_emails(args.profile, state)
    if admins:
        # 누가 손으로 넣었을 수 있다. 관리자 토큰으로 부하를 주면 측정 대상이 달라지고 위험하다 → 멈춘다
        raise SystemExit(f"ADMIN 그룹에 부하 테스트 계정이 있다: {', '.join(admins)} — 그룹에서 빼고 다시 실행한다")

    for index in range(1, args.count + 1):
        email = email_of(index)
        user = known.get(email)
        if email not in in_pool:
            aws_cli(args.profile, region, [
                "admin-create-user", "--user-pool-id", pool_id, "--username", email,
                "--message-action", "SUPPRESS",   # 메일을 보내지 않는다
                "--user-attributes", f"Name=email,Value={email}", "Name=email_verified,Value=true",
                f"Name=name,Value=loadtest-{index:03d}"])
        if user is None or email not in in_pool:
            # 비밀번호를 고정(permanent)해 FORCE_CHANGE_PASSWORD 없이 SRP로 바로 로그인하게 한다
            password = new_password()
            aws_cli(args.profile, region, ["admin-set-user-password"], {
                "UserPoolId": pool_id, "Username": email, "Password": password, "Permanent": True})
            user = LoadUser(email=email, password=password)
            known[email] = user
            state.users = sorted(known.values(), key=lambda kept: kept.email)
            state.save(args.state)   # 중간에 끊겨도 만든 계정의 비밀번호를 잃지 않게 매번 저장
        print(f"\r계정 {index}/{args.count}", end="", file=sys.stderr, flush=True)
    print(file=sys.stderr)
    state.users = sorted(known.values(), key=lambda kept: kept.email)
    state.save(args.state)
    print(f"계정 {len(state.users)}개 준비 → {args.state}", file=sys.stderr)


def _for_each(users: list[LoadUser], work, concurrency: int) -> list[str]:
    errors: list[str] = []

    def run(user: LoadUser) -> None:
        try:
            work(user)
        except (CognitoError, OSError, ValueError, KeyError) as error:
            # 사용자 하나의 네트워크 오류·이상한 응답이 전체를 멈추면 이미 받은 토큰을 저장하지 못한다
            # → 다시 돌릴 때 로그인 100건을 또 보내게 된다(인증 API 제한). 모아서 보고하고 저장은 항상 한다
            errors.append(f"{user.email}: {type(error).__name__}: {error}")

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        list(pool.map(run, users))
    return errors


def cmd_login(args: argparse.Namespace) -> None:
    state = State.load(args.state)
    api = CognitoApi(state.region)
    pool_name = pool_name_of(state.user_pool_id)

    def login(user: LoadUser) -> None:
        tokens = refuse_admin_token(srp_login(api, state.client_id, pool_name, user.email, user.password))
        user.access_token, user.access_expires_at = tokens.access_token, tokens.access_expires_at
        user.refresh_token = tokens.refresh_token

    errors = _for_each(state.users, login, args.concurrency)
    state.save(args.state)
    _report("로그인", len(state.users), errors)


def cmd_refresh(args: argparse.Namespace) -> None:
    state = State.load(args.state)
    api = CognitoApi(state.region)

    def refresh(user: LoadUser) -> None:
        if not user.refresh_token:
            raise CognitoError("NoRefreshToken", "login을 먼저 한다")
        try:
            tokens = refuse_admin_token(refresh_access(api, state.client_id, user.refresh_token))
        except CognitoError:
            user.access_token, user.access_expires_at = None, 0.0   # 이전 토큰도 쓰지 않게 지운다
            raise
        user.access_token, user.access_expires_at = tokens.access_token, tokens.access_expires_at

    errors = _for_each(state.users, refresh, args.concurrency)
    state.save(args.state)
    _report("갱신", len(state.users), errors)


def _report(what: str, total: int, errors: list[str]) -> None:
    for line in errors[:10]:
        print(f"  실패 {line}", file=sys.stderr)
    print(f"{what} {total - len(errors)}/{total}", file=sys.stderr)
    if errors:
        raise SystemExit(1)


def usable_tokens(state: State, min_valid_seconds: float, count: int | None, now: float) -> list[str]:
    """k6에 넘길 토큰. 하나라도 곧 만료되면 실패 — 테스트 중간에 401이 섞이면 측정이 무효다"""
    users = state.users[:count] if count else state.users
    stale = [user.email for user in users if not user.access_token or user.access_expires_at - now < min_valid_seconds]
    if stale:
        raise SystemExit(f"토큰 {len(stale)}개가 없거나 {min_valid_seconds / 60:.0f}분 안에 만료된다 — refresh를 먼저 돌린다")
    admins = [user.email for user in users if ADMIN_GROUP in token_groups(user.access_token or "")]
    if admins:
        raise SystemExit(f"ADMIN 그룹 토큰이 있다: {', '.join(admins)} — 그룹에서 빼고 refresh를 다시 돌린다")
    return [user.access_token for user in users if user.access_token]


def write_private(path: Path, text: str) -> None:
    """권한 600으로 쓴다 — 잠깐이라도 다른 사용자가 읽을 수 있는 파일을 만들지 않는다"""
    fd, temp = tempfile.mkstemp(dir=path.parent if str(path.parent) else ".", prefix=".tokens-")
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(text)
    os.chmod(temp, 0o600)
    os.replace(temp, path)


def cmd_tokens(args: argparse.Namespace) -> None:
    state = State.load(args.state)
    tokens = usable_tokens(state, args.min_valid_minutes * 60, args.count, time.time())
    if args.out:
        # 토큰 하나가 1~1.2KB라 100개를 쉼표로 이으면 리눅스 env·인자 한도(128KiB)에 거의 닿는다 → 파일로 넘긴다
        write_private(args.out, "\n".join(tokens) + "\n")
        print(f"토큰 {len(tokens)}개 → {args.out} (K6_TOKENS_FILE)", file=sys.stderr)
    else:
        print(",".join(tokens))


def cmd_status(args: argparse.Namespace) -> None:
    state = State.load(args.state)
    with_token = [user for user in state.users if user.access_token]
    print(f"사용자 풀 {state.user_pool_id} · 계정 {len(state.users)} · 토큰 {len(with_token)}")
    if with_token:
        earliest = min(user.access_expires_at for user in with_token)
        left = (earliest - time.time()) / 60
        print(f"가장 이른 만료: {dt.datetime.fromtimestamp(earliest):%H:%M} ({left:.0f}분 남음)")


def cmd_delete(args: argparse.Namespace) -> None:
    state = State.load(args.state)
    emails = existing_emails(args.profile, state)
    for email in sorted(emails):
        aws_cli(args.profile, state.region, ["admin-delete-user", "--user-pool-id", state.user_pool_id, "--username", email])
    state.users = []
    state.save(args.state)
    print(f"계정 {len(emails)}개 삭제", file=sys.stderr)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--state", type=Path, default=DEFAULT_STATE, help=f"상태 파일 (기본 {DEFAULT_STATE})")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create", help="계정 만들기 (관리자 역할)")
    create.add_argument("--authority", required=True, help="VITE_COGNITO_AUTHORITY 값")
    create.add_argument("--client-id", required=True, help="VITE_COGNITO_CLIENT_ID 값")
    create.add_argument("--count", type=int, default=100)
    create.add_argument("--profile", default=DEFAULT_PROFILE)
    create.set_defaults(handler=cmd_create)

    for name, handler, help_text in (("login", cmd_login, "SRP 로그인으로 토큰 받기"),
                                     ("refresh", cmd_refresh, "Access Token 갱신")):
        command = sub.add_parser(name, help=help_text)
        command.add_argument("--concurrency", type=int, default=5, help="동시 요청 수 (Cognito 인증 API 제한 고려)")
        command.set_defaults(handler=handler)

    tokens = sub.add_parser("tokens", help="K6_TOKENS 값 출력")
    tokens.add_argument("--min-valid-minutes", type=float, default=30)
    tokens.add_argument("--count", type=int, default=None, help="앞에서부터 N개만 (기본 전부)")
    tokens.add_argument("--out", type=Path, default=None,
                        help="파일로 쓴다(줄당 1개, 권한 600). k6는 K6_TOKENS_FILE로 읽는다. 없으면 쉼표로 이어 출력")
    tokens.set_defaults(handler=cmd_tokens)

    sub.add_parser("status", help="계정·만료 요약").set_defaults(handler=cmd_status)

    delete = sub.add_parser("delete", help="계정 삭제 (관리자 역할)")
    delete.add_argument("--profile", default=DEFAULT_PROFILE)
    delete.set_defaults(handler=cmd_delete)

    args = parser.parse_args(argv)
    args.handler(args)


if __name__ == "__main__":
    main()
