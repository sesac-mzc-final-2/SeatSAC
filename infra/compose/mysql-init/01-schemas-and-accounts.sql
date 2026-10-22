-- 로컬용. RDS 에서는 Terraform(3.1.12) 이 같은 내용을 만든다. 비밀번호는 로컬에서만 'local'.
CREATE DATABASE IF NOT EXISTS catalog_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS booking_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS user_db    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS payment_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'catalog_app'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'catalog_migrator'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'booking_app'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'booking_migrator'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'user_app'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'user_migrator'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'payment_app'@'%' IDENTIFIED BY 'local';
CREATE USER IF NOT EXISTS 'payment_migrator'@'%' IDENTIFIED BY 'local';

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog_db.* TO 'catalog_app'@'%';
GRANT ALL PRIVILEGES ON catalog_db.* TO 'catalog_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON booking_db.* TO 'booking_app'@'%';
GRANT ALL PRIVILEGES ON booking_db.* TO 'booking_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON user_db.* TO 'user_app'@'%';
GRANT ALL PRIVILEGES ON user_db.* TO 'user_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON payment_db.* TO 'payment_app'@'%';
GRANT ALL PRIVILEGES ON payment_db.* TO 'payment_migrator'@'%';
FLUSH PRIVILEGES;
