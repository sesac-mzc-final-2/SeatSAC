-- ai_migrator만 ai_db 범위 GRANT OPTION을 가진다. 계정 생성·비밀번호 변경은 하지 않는다.
-- 스키마 전체 SELECT 금지: unmet_demand·inbox·Flyway 이력은 NL2SQL에 노출하지 않는다.
GRANT SELECT ON ai_db.movie TO 'ai_query'@'%';
GRANT SELECT ON ai_db.theater TO 'ai_query'@'%';
GRANT SELECT ON ai_db.screening TO 'ai_query'@'%';
