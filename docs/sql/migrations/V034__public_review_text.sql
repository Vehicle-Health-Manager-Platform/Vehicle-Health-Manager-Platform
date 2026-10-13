-- Independent text authorization; no private review backfill and no original-text copies.
CREATE TABLE IF NOT EXISTS public_review_text (
 review_id BIGINT UNSIGNED NOT NULL,
 public_text VARCHAR(300) NULL,
 text_hash CHAR(64) NULL,
 consent_version VARCHAR(32) NULL,
 status VARCHAR(16) NOT NULL,
 version BIGINT UNSIGNED NOT NULL,
 reason_code VARCHAR(24) NULL,
 public_id CHAR(36) NULL,
 submitted_at DATETIME NULL,
 published_at DATETIME NULL,
 PRIMARY KEY(review_id), UNIQUE KEY uk_review_text_public(public_id), KEY idx_review_text_pending(status,review_id),
 CONSTRAINT ck_review_text_status CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
 CONSTRAINT ck_review_text_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS public_review_text_moderation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 review_id BIGINT UNSIGNED NOT NULL,
 request_version BIGINT UNSIGNED NOT NULL,
 text_hash CHAR(64) NOT NULL,
 decision VARCHAR(16) NOT NULL,
 reason_code VARCHAR(24) NULL,
 operator_id BIGINT UNSIGNED NOT NULL,
 decided_at DATETIME NOT NULL,
 public_id CHAR(36) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_review_text_version(review_id,request_version),
 CONSTRAINT ck_review_text_decision CHECK (decision IN ('APPROVE','REJECT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
