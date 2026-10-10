-- Reliable review-triggered archive outbox. No historical backfill.
CREATE TABLE IF NOT EXISTS service_archive_job (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 order_id BIGINT UNSIGNED NOT NULL,
 review_id BIGINT UNSIGNED NOT NULL,
 payload JSON DEFAULT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 archive_id BIGINT UNSIGNED DEFAULT NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 last_error VARCHAR(32) DEFAULT NULL,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_archive_order(order_id),
 UNIQUE KEY uk_archive_review(review_id), UNIQUE KEY uk_archive_output(archive_id),
 KEY idx_archive_pending(status,next_attempt_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
