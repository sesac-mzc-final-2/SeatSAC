-- catalog 스키마 V1 (build_seed.py의 DDL에서 생성. 직접 고치지 말고 스크립트를 고친다)
-- 시각은 UTC 저장, ID는 BIGINT. 시드(INSERT)는 포함하지 않는다

CREATE TABLE theater (
  theater_id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, source_provider VARCHAR(10) NOT NULL,
  source_theater_id BIGINT NOT NULL, source_division_code INT, source_detail_division_code CHAR(4), region VARCHAR(20),
  has_layout TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_theater_src (source_provider, source_theater_id));
CREATE TABLE auditorium (
  auditorium_id BIGINT PRIMARY KEY, theater_id BIGINT NOT NULL, name VARCHAR(30) NOT NULL,
  screen_type_code INT, screen_type_name VARCHAR(30), floor VARCHAR(5), source_auditorium_id VARCHAR(20) NOT NULL,
  total_seats INT NOT NULL, layout_version INT NOT NULL, layout_width DECIMAL(8,3), layout_height DECIMAL(8,3),
  UNIQUE KEY uk_aud_src (theater_id, source_auditorium_id),
  FOREIGN KEY (theater_id) REFERENCES theater(theater_id));
CREATE TABLE auditorium_layout (
  auditorium_id BIGINT NOT NULL, layout_version INT NOT NULL,
  screen_x_from DECIMAL(8,3) NOT NULL, screen_x_to DECIMAL(8,3) NOT NULL,
  entrances JSON NOT NULL, vertical_aisles JSON NOT NULL, horizontal_aisles JSON NOT NULL,
  source_provider VARCHAR(10) NOT NULL, fetched_at DATETIME NOT NULL COMMENT 'UTC',
  PRIMARY KEY (auditorium_id, layout_version),
  FOREIGN KEY (auditorium_id) REFERENCES auditorium(auditorium_id));
CREATE TABLE seat (
  seat_id BIGINT PRIMARY KEY, auditorium_id BIGINT NOT NULL, layout_version INT NOT NULL,
  row_label VARCHAR(3) NOT NULL, col_no INT NOT NULL, seat_label VARCHAR(6) NOT NULL,
  x DECIMAL(8,3) NOT NULL, y DECIMAL(8,3) NOT NULL, w DECIMAL(6,3) NOT NULL, h DECIMAL(6,3) NOT NULL,
  zone_no INT, seat_type VARCHAR(10) NOT NULL, source_seat_code INT, active TINYINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_seat_label (auditorium_id, layout_version, seat_label),
  FOREIGN KEY (auditorium_id) REFERENCES auditorium(auditorium_id));
CREATE TABLE layout_import (
  import_id BIGINT PRIMARY KEY, auditorium_id BIGINT NOT NULL, fetched_at VARCHAR(30) NOT NULL, parser_version VARCHAR(30),
  source_seat_count INT, result VARCHAR(255), raw_snapshot_path VARCHAR(255));

CREATE TABLE movie (
  movie_id BIGINT PRIMARY KEY, source_provider VARCHAR(10) NOT NULL, source_movie_code VARCHAR(10) NOT NULL,
  title VARCHAR(200) NOT NULL, title_en VARCHAR(200), view_grade VARCHAR(20), view_grade_code INT,
  slot_minutes INT COMMENT '시작~종료 간격 최빈값. 광고 포함 여부 미확인',
  tmdb_id BIGINT NULL COMMENT 'TMDB 보충분(10/21 결정). 아래 컬럼은 별도 스크립트로 채우며 모두 NULL 허용',
  genres JSON NULL, overview TEXT NULL, overview_lang VARCHAR(5) NULL COMMENT 'ko-KR 또는 en-US(대체)',
  release_date DATE NULL, runtime_minutes INT NULL COMMENT 'TMDB 원 러닝타임. slot_minutes(회차 간격)와 다른 값',
  poster_path VARCHAR(255) NULL COMMENT 'TMDB 상대 경로', director VARCHAR(200) NULL, cast_names JSON NULL,
  meta_fetched_at DATETIME NULL COMMENT 'UTC',
  UNIQUE KEY uk_movie_src (source_provider, source_movie_code));
CREATE TABLE screening (
  screening_id BIGINT PRIMARY KEY, auditorium_id BIGINT NOT NULL, layout_version INT NOT NULL, movie_id BIGINT NOT NULL,
  business_date DATE NOT NULL COMMENT 'KST 영업일', play_sequence INT NOT NULL,
  starts_at DATETIME NOT NULL COMMENT 'UTC', ends_at DATETIME NOT NULL COMMENT 'UTC',
  raw_start_time CHAR(5) NOT NULL COMMENT 'KST 원본 표기', raw_end_time CHAR(5) NOT NULL COMMENT 'KST 원본 표기',
  source_movie_code VARCHAR(10), film_format VARCHAR(40), subtitle_type VARCHAR(10), source_translation_code INT,
  UNIQUE KEY uk_screening (auditorium_id, business_date, play_sequence),
  KEY ix_screening_movie_date (movie_id, business_date),
  FOREIGN KEY (auditorium_id) REFERENCES auditorium(auditorium_id),
  FOREIGN KEY (movie_id) REFERENCES movie(movie_id));
