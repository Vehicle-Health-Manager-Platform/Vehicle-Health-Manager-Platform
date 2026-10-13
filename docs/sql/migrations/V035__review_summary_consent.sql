-- Separate third-party summary purpose, bound to the exact approved text version.
CREATE TABLE IF NOT EXISTS review_summary_consent (
 review_id BIGINT UNSIGNED NOT NULL,
 consented TINYINT NOT NULL,
 version BIGINT UNSIGNED NOT NULL,
 text_version BIGINT UNSIGNED NOT NULL,
 consent_version VARCHAR(32) NOT NULL,
 PRIMARY KEY(review_id),
 CONSTRAINT ck_summary_consent CHECK (consented IN (0,1)),
 CONSTRAINT ck_summary_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
