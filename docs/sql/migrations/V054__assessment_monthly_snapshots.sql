-- No historical grade/stop-date backfill. Explicit immutable monthly revisions only.
SET @assessment_month_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='assessment_policy' AND column_name='stopped_at')=0,'ALTER TABLE assessment_policy ADD COLUMN stopped_at DATETIME DEFAULT NULL','SELECT 1');
PREPARE assessment_month_stmt FROM @assessment_month_ddl; EXECUTE assessment_month_stmt; DEALLOCATE PREPARE assessment_month_stmt;
CREATE TABLE IF NOT EXISTS assessment_month (
 merchant_id BIGINT UNSIGNED NOT NULL,
 period_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(merchant_id,period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS assessment_snapshot (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 merchant_id BIGINT UNSIGNED NOT NULL,
 period_month CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT UNSIGNED NOT NULL,
 policy_snapshot JSON NOT NULL,
 summary JSON NOT NULL,
 facts_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 calculation_version VARCHAR(32) NOT NULL DEFAULT 'R8B_CALC_V1',
 captured_from DATETIME NOT NULL,
 captured_to DATETIME NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_assessment_month_revision(merchant_id,period_month,revision),
 UNIQUE KEY uk_assessment_snapshot_submission(created_by,submission_key),
 CONSTRAINT ck_assessment_snapshot_revision CHECK(revision>0 AND captured_to>=captured_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS assessment_snapshot_fact (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 snapshot_id BIGINT UNSIGNED NOT NULL,
 ordinal INT UNSIGNED NOT NULL,
 fact_json JSON NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_assessment_snapshot_fact(snapshot_id,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
