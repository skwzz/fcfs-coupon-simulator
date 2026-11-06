-- simulator 전용 DB. 서비스마다 자기 DB를 갖는 실무 구성을 따라 coupon(coupon-api)과 나눈다.
-- MySQL 컨테이너가 처음 만들어질 때(볼륨이 비어 있을 때)만 자동 실행된다.
CREATE DATABASE IF NOT EXISTS simulation CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON simulation.* TO 'coupon'@'%';
