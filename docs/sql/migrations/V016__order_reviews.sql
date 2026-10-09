-- Immutable owner-only feedback. No migration of legacy completed orders.
CREATE TABLE IF NOT EXISTS order_review (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 order_id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 redemption_id BIGINT UNSIGNED NOT NULL,
 rating TINYINT UNSIGNED NOT NULL,
 content VARCHAR(500) NOT NULL,
 test_mode TINYINT NOT NULL,
 submitted_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_review_order(order_id), KEY idx_review_owner(user_id,submitted_at),
 CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
 CONSTRAINT ck_review_test_mode CHECK (test_mode IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS order_review_file (
 review_id BIGINT UNSIGNED NOT NULL,
 file_id BIGINT UNSIGNED NOT NULL,
 position TINYINT UNSIGNED NOT NULL,
 PRIMARY KEY(review_id,position), UNIQUE KEY uk_review_file(review_id,file_id),
 CONSTRAINT ck_review_file_position CHECK (position BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
