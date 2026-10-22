-- user 스키마 V1 (ERD 6차 3.2, ADR-37 Cognito)
-- user_id = Cognito sub(CHAR(36)). password_hash·role은 없다(인증·역할은 Cognito). Refresh Token도 저장하지 않는다
-- 시각은 UTC 저장. 프로필 행은 첫 GET /users/me 때 만들고 email·name은 Cognito GetUser로 채운다
-- email·name 길이는 Cognito 표준 속성 한도(2048자)에 맞춘다. email은 RFC 5321 상한(254자)을 넘는 값이 유효한 주소일 수 없고
-- 유일 인덱스(utf8mb4 3072바이트) 안에 들어와야 하므로 VARCHAR(320). name은 인덱스가 없어 2048 그대로 둔다(잘라 저장하지 않음)
-- 테이블 이름 user는 예약어가 아니지만 JPA에서는 @Table(name = "`user`")처럼 따옴표로 감싸는 것이 안전하다
CREATE TABLE `user` (
  user_id CHAR(36) NOT NULL,
  email VARCHAR(320) NOT NULL,
  name VARCHAR(2048) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_user_email (email));

CREATE TABLE user_preference (
  user_id CHAR(36) NOT NULL,
  preferred_time_slot VARCHAR(10) NULL COMMENT 'MORNING·AFTERNOON·EVENING·NIGHT',
  preferred_genres JSON NOT NULL COMMENT '저장·전달만. 추천 사용 여부는 AI 설계(3.4.6)',
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (user_id),
  FOREIGN KEY (user_id) REFERENCES `user`(user_id));

CREATE TABLE user_preferred_theater (
  user_id CHAR(36) NOT NULL,
  rank_no TINYINT NOT NULL COMMENT '1~3 (선호 극장 최대 3곳)',
  theater_id BIGINT NOT NULL COMMENT 'catalog.theater ID 참조(FK 없음)',
  PRIMARY KEY (user_id, rank_no),
  UNIQUE KEY uk_pref_theater (user_id, theater_id),
  CONSTRAINT ck_pref_rank CHECK (rank_no BETWEEN 1 AND 3),
  FOREIGN KEY (user_id) REFERENCES `user`(user_id));
