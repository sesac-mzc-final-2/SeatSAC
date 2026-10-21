-- 집계 Lambda(stats_lambda)와 AI(ai_readonly)가 읽는 리포트 뷰 (v1.0 9-3, ADR-33)
-- 다른 스키마를 직접 JOIN하지 않도록 theater_id까지 붙여 둔다. GRANT는 Flyway가 아니라 3.1.12/Terraform에서 한다
CREATE VIEW v_screening_report AS
SELECT s.screening_id, s.movie_id, a.theater_id, s.auditorium_id, s.business_date, s.starts_at
FROM screening s
JOIN auditorium a ON a.auditorium_id = s.auditorium_id;
