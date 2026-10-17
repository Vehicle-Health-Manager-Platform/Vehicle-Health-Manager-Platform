-- Configuration only. No default threshold and no historical permanent grant.
CREATE TABLE IF NOT EXISTS invite_diagnosis_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 required_count INT UNSIGNED NOT NULL,
 source_note VARCHAR(256) NOT NULL,
 valid_from DATETIME NOT NULL, valid_to DATETIME NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL, created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_invite_diagnosis_active(active_slot),
 CONSTRAINT ck_invite_diagnosis_policy CHECK(revision>0 AND required_count BETWEEN 1 AND 1000000 AND valid_from<valid_to AND status IN ('DRAFT','PUBLISHED','STOPPED')),
 CONSTRAINT ck_invite_diagnosis_publication CHECK(status='DRAFT' OR published_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
