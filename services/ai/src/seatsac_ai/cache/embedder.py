"""로컬 임베딩(fastembed ONNX, 384차원). 모델은 이미지 빌드 때 /app/models 에 들어 있다(Dockerfile).

실행 중 다운로드하지 않는다. 처음 한 번 읽는 데 몇 초 걸리므로 기동 때 미리 읽는다(main.lifespan).
"""

from __future__ import annotations

import logging
import os
import threading

log = logging.getLogger(__name__)

MODEL_NAME = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"


class FastEmbedder:
    def __init__(self, cache_dir: str | None = None) -> None:
        self._cache_dir = cache_dir or os.environ.get("FASTEMBED_CACHE_PATH")
        self._model = None
        self._lock = threading.Lock()

    def load(self) -> None:
        with self._lock:
            if self._model is None:
                from fastembed import TextEmbedding

                self._model = TextEmbedding(model_name=MODEL_NAME, cache_dir=self._cache_dir)
                log.info("임베딩 모델을 읽었다: %s", MODEL_NAME)

    def embed(self, text: str) -> list[float]:
        if self._model is None:
            self.load()
        vector = next(iter(self._model.embed([text])))  # type: ignore[union-attr]
        return [float(x) for x in vector]
