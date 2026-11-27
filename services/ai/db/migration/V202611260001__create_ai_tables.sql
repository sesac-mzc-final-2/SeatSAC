-- 6.4.1 확장 단계. ai_db·계정은 db-bootstrap이 먼저 만든다.
-- 원본 ID만 참조(FK 없음): 이벤트 순서 역전·삭제 메시지를 독립적으로 받는다.
-- 공개 필드는 삭제 tombstone(ID/version/deleted)도 저장할 수 있도록 NULL 허용.
CREATE TABLE movie (
    movie_id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(255) NULL,
    title_en VARCHAR(255) NULL,
    view_grade VARCHAR(20) NULL,
    slot_minutes INT NULL,
    runtime_minutes INT NULL,
    release_date DATE NULL,
    director VARCHAR(255) NULL,
    genres TEXT NULL,
    synopsis TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE theater (
    theater_id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(255) NULL,
    region VARCHAR(100) NULL,
    has_layout BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE screening (
    screening_id BIGINT NOT NULL PRIMARY KEY,
    movie_id BIGINT NULL,
    theater_id BIGINT NULL,
    auditorium_id BIGINT NULL,
    auditorium_name VARCHAR(255) NULL,
    screen_type VARCHAR(32) NULL,
    business_date DATE NULL,
    starts_at DATETIME(3) NULL,
    ends_at DATETIME(3) NULL,
    start_hour INT NULL,
    film_format VARCHAR(40) NULL,
    total_seats INT NULL,
    remaining_seats INT NULL,
    remaining_seats_source VARCHAR(16) NULL,
    fetched_at DATETIME(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    changed_at DATETIME(3) NULL,
    INDEX idx_screening_date_theater (business_date, theater_id),
    INDEX idx_screening_movie_date (movie_id, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE processed_message (
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    received_at DATETIME(3) NOT NULL,
    INDEX idx_processed_message_received_at (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE unmet_demand (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    -- 요약의 대소문자·악센트·공백을 합치지 않는다(통계의 완전 일치 규칙).
    reason VARCHAR(120) COLLATE utf8mb4_0900_bin NULL,
    question_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slots JSON NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT chk_unmet_demand_slots CHECK (JSON_TYPE(slots) = 'OBJECT'),
    INDEX idx_unmet_demand_created_at (created_at),
    INDEX idx_unmet_demand_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
