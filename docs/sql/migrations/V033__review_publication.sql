-- Separate, revocable rating-only consent; historical reviews stay private.
CREATE TABLE IF NOT EXISTS review_publication (
 review_id BIGINT UNSIGNED NOT NULL,
 consented TINYINT NOT NULL,
 version BIGINT UNSIGNED NOT NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(review_id),
 CONSTRAINT ck_publication_consent CHECK (consented IN (0,1)),
 CONSTRAINT ck_publication_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
