# syntax=docker/dockerfile:1
# ops-api (6.1.7, ADR-119) — services/ai Dockerfile과 같은 골격. 모델·데이터가 없어 단계가 더 짧다

# ---- builder: 의존성 설치 (실행 중에는 아무것도 내려받지 않는다) ----
FROM python:3.12-slim AS builder

# uv 버전은 고정한다(빌드 재현성). pr-check python job이 이 줄을 읽어 같은 버전을 쓴다
ARG UV_VERSION=0.11.32
RUN pip install --no-cache-dir "uv==${UV_VERSION}"

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv

WORKDIR /app

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
RUN uv sync --frozen --no-dev --no-editable


# ---- runtime ----
FROM python:3.12-slim

# 비루트: pr-check의 「USER가 숫자 UID(0 아님)」 검사와 K8s runAsNonRoot 모두 숫자 UID여야 통과한다
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

COPY --from=builder /app/.venv /app/.venv

ENV PATH="/app/.venv/bin:${PATH}" \
    APP_ENV=staging \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HOME=/tmp

# readOnlyRootFilesystem + /tmp emptyDir. 디스크에 쓰는 것이 없다
USER 10001
WORKDIR /app
EXPOSE 8080

# 워커 1 — 리포트 결과 캐시(1시간)가 프로세스 메모리다. 접근 로그는 앱이 JSON으로 남긴다
CMD ["uvicorn", "seatsac_ops.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log", "--timeout-keep-alive", "75"]
