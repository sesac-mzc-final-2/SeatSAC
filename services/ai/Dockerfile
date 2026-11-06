# syntax=docker/dockerfile:1

# ---- builder: 의존성 설치 + 임베딩 모델 내려받기 (실행 중에는 아무것도 내려받지 않는다) ----
FROM python:3.12-slim AS builder

# uv 버전은 고정한다(빌드 재현성). 올릴 때는 uv.lock도 같은 버전으로 다시 만든다.
ARG UV_VERSION=0.11.32
RUN pip install --no-cache-dir "uv==${UV_VERSION}"

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv

WORKDIR /app

# 의존성 레이어: pyproject·uv.lock이 안 바뀌면 캐시 재사용
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

# 프로젝트 설치(비편집 모드라 src가 venv 안으로 복사된다)
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable

# 임베딩 모델(ONNX, 384차원)을 빌드 때 /app/models 에 넣는다. 의미 캐시(3.4.5)가 이 경로를 쓴다.
ENV FASTEMBED_CACHE_PATH=/app/models
RUN /app/.venv/bin/python -c "from fastembed import TextEmbedding; \
TextEmbedding(model_name='sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2', cache_dir='/app/models')"


# ---- runtime ----
FROM python:3.12-slim

# 비루트: pr-check의 「USER가 숫자 UID(0 아님)」 검사와 K8s runAsNonRoot 모두 숫자 UID여야 통과한다.
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

COPY --from=builder /app/.venv /app/.venv
COPY --from=builder /app/models /app/models

ENV PATH="/app/.venv/bin:${PATH}" \
    APP_ENV=staging \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    FASTEMBED_CACHE_PATH=/app/models \
    HF_HUB_OFFLINE=1 \
    HOME=/tmp

# readOnlyRootFilesystem + /tmp emptyDir 에서 쓰는 곳은 /tmp 뿐이다. SQLite는 :memory: 라 디스크에 남기지 않는다.
USER 10001
WORKDIR /app
EXPOSE 8080

# 워커 1(스냅샷·캐시가 프로세스 메모리). 접근 로그는 앱이 JSON으로 직접 남기므로 uvicorn 것은 끈다.
CMD ["uvicorn", "seatsac_ai.main:app", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log"]
