-- Source-based curation only; no view counts, inherited permissions, or seeded selections.
SET @curation_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_community_curate')=0,'ALTER TABLE operator_account ADD COLUMN can_community_curate TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE curation_stmt FROM @curation_ddl; EXECUTE curation_stmt; DEALLOCATE PREPARE curation_stmt;
CREATE TABLE IF NOT EXISTS experience_curation (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 experience_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_snapshot JSON NOT NULL,
 priority INT NOT NULL,
 source_note VARCHAR(256) NOT NULL,
 valid_from DATETIME NOT NULL,
 valid_to DATETIME NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 rule_version VARCHAR(32) NOT NULL DEFAULT 'R8C_CURATED_V1',
 created_by BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 created_at DATETIME NOT NULL,
 published_at DATETIME DEFAULT NULL,
 stopped_at DATETIME DEFAULT NULL,
 published_experience CHAR(36) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN experience_id ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_curation_submission(created_by,submission_key),
 UNIQUE KEY uk_curation_published(published_experience), KEY idx_curation_order(status,priority,id),
 CONSTRAINT ck_curation_priority CHECK(priority BETWEEN 1 AND 1000000),
 CONSTRAINT ck_curation_window CHECK(valid_to>valid_from),
 CONSTRAINT ck_curation_status CHECK(status IN ('DRAFT','PUBLISHED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
