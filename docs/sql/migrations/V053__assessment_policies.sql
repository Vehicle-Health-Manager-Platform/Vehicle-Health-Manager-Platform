-- Source configuration only; never convert legacy default zero scores into verified grades.
SET @assessment_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_assessment')=0,'ALTER TABLE operator_account ADD COLUMN can_assessment TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE assessment_stmt FROM @assessment_ddl; EXECUTE assessment_stmt; DEALLOCATE PREPARE assessment_stmt;
CREATE TABLE IF NOT EXISTS assessment_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 merchant_id BIGINT UNSIGNED NOT NULL,
 merchant_snapshot JSON NOT NULL,
 acquisition_target INT UNSIGNED NOT NULL,
 coupon_target INT UNSIGNED NOT NULL,
 process_requirements JSON NOT NULL,
 grade_thresholds JSON NOT NULL,
 source_note VARCHAR(256) NOT NULL,
 valid_from DATETIME NOT NULL,
 valid_to DATETIME NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 rule_version VARCHAR(32) NOT NULL DEFAULT 'R8B_POLICY_V1',
 created_by BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 created_at DATETIME NOT NULL,
 published_at DATETIME DEFAULT NULL,
 published_merchant BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN merchant_id ELSE NULL END) STORED,
 PRIMARY KEY(id),
 UNIQUE KEY uk_assessment_submission(created_by,submission_key),
 UNIQUE KEY uk_assessment_published(published_merchant),
 KEY idx_assessment_merchant(merchant_id,status,id),
 CONSTRAINT ck_assessment_targets CHECK(acquisition_target BETWEEN 1 AND 1000000 AND coupon_target BETWEEN 1 AND 1000000),
 CONSTRAINT ck_assessment_window CHECK(valid_to>valid_from),
 CONSTRAINT ck_assessment_status CHECK(status IN ('DRAFT','PUBLISHED','STOPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
