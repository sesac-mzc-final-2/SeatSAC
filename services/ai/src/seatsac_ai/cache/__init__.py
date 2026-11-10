"""정확 일치 캐시 + 의미 캐시(ai Valkey, fastembed 384차원) + 세션 — 3.4.5.

slots.py    질문 → 템플릿 질문 + 슬롯 (계약 15-2 ③)
template.py SQL ↔ SQL 템플릿 (결과가 아니라 SQL을 저장한다)
store.py    Valkey 키(ai:exact·ai:semcache·ai:session), 벡터 검색
embedder.py 로컬 임베딩 모델
"""
