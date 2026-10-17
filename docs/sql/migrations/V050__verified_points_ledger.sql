-- Do not import legacy point_balance/point_flow or backfill historical tasks.
SET @points_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='auth_session' AND column_name='point_identity_source')=0,'ALTER TABLE auth_session ADD COLUMN point_identity_source VARCHAR(24) NOT NULL DEFAULT ''UNKNOWN''','SELECT 1');
PREPARE points_stmt FROM @points_ddl; EXECUTE points_stmt; DEALLOCATE PREPARE points_stmt;
CREATE TABLE IF NOT EXISTS point_task_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 user_id BIGINT UNSIGNED NOT NULL,
 task_type VARCHAR(32) NOT NULL,
 period_key VARCHAR(16) NOT NULL,
 policy_id BIGINT UNSIGNED NOT NULL,
 policy_revision BIGINT UNSIGNED NOT NULL,
 points INT UNSIGNED NOT NULL,
 policy_snapshot JSON NOT NULL,
 identity_session CHAR(36) NOT NULL,
 source_id VARCHAR(64) NOT NULL,
 source_revision BIGINT UNSIGNED NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at DATETIME NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 credited_at DATETIME DEFAULT NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL,
 last_error VARCHAR(32) DEFAULT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_point_task_period(user_id,task_type,period_key),
 KEY idx_point_task_pending(status,next_attempt_at,id),
 CONSTRAINT ck_point_task_points CHECK(points BETWEEN 1 AND 1000000),
 CONSTRAINT ck_point_task_state CHECK(status IN ('PENDING','CREDITED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS point_verified_account (
 user_id BIGINT UNSIGNED NOT NULL,
 balance BIGINT UNSIGNED NOT NULL DEFAULT 0,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL,
 PRIMARY KEY(user_id),
 CONSTRAINT ck_point_verified_balance CHECK(balance<=9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS point_verified_entry (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 user_id BIGINT UNSIGNED NOT NULL,
 event_id BIGINT UNSIGNED NOT NULL,
 delta INT UNSIGNED NOT NULL,
 balance_after BIGINT UNSIGNED NOT NULL,
 created_at DATETIME NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_point_verified_event(event_id),
 KEY idx_point_verified_user(user_id,id),
 CONSTRAINT ck_point_verified_delta CHECK(delta BETWEEN 1 AND 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS point_checkin_request (
 user_id BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_id BIGINT UNSIGNED NOT NULL,
 creation_result JSON NOT NULL,
 created_at DATETIME NOT NULL,
 PRIMARY KEY(user_id,submission_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
