-- Configuration only: never seed points, import legacy balances, or award historical tasks.
SET @point_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_points')=0, 'ALTER TABLE operator_account ADD COLUMN can_points TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE point_stmt FROM @point_ddl; EXECUTE point_stmt; DEALLOCATE PREPARE point_stmt;

CREATE TABLE IF NOT EXISTS point_task_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 task_type VARCHAR(32) NOT NULL,
 points INT UNSIGNED NOT NULL,
 source_note VARCHAR(256) NOT NULL,
 valid_from DATETIME NOT NULL,
 valid_to DATETIME NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 rule_version VARCHAR(32) NOT NULL DEFAULT 'R8A_TASK_V1',
 created_by BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 created_at DATETIME NOT NULL,
 published_at DATETIME DEFAULT NULL,
 published_task VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN task_type ELSE NULL END) STORED,
 PRIMARY KEY(id),
 UNIQUE KEY uk_point_policy_submission(created_by,submission_key),
 UNIQUE KEY uk_point_policy_published(published_task),
 KEY idx_point_policy_task(task_type,status,id),
 CONSTRAINT ck_point_policy_task CHECK(task_type IN ('CHECK_IN','ARCHIVE_FIRST_COMPLETE','AI_FIRST_SUCCESS')),
 CONSTRAINT ck_point_policy_points CHECK(points BETWEEN 1 AND 1000000),
 CONSTRAINT ck_point_policy_window CHECK(valid_to>valid_from),
 CONSTRAINT ck_point_policy_status CHECK(status IN ('DRAFT','PUBLISHED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
