-- Separate management and case-processing privileges. No existing account gains access.
SET @diag_add = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_diagnose')=0,
 'ALTER TABLE operator_account ADD COLUMN can_diagnose TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE diag_stmt FROM @diag_add; EXECUTE diag_stmt; DEALLOCATE PREPARE diag_stmt;
SET @diag_add = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_diagnose_manage')=0,
 'ALTER TABLE operator_account ADD COLUMN can_diagnose_manage TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE diag_stmt FROM @diag_add; EXECUTE diag_stmt; DEALLOCATE PREPARE diag_stmt;
CREATE TABLE IF NOT EXISTS diagnosis_control (
 id TINYINT UNSIGNED NOT NULL, PRIMARY KEY(id), CONSTRAINT ck_diagnosis_control CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO diagnosis_control(id) VALUES(1);
CREATE TABLE IF NOT EXISTS diagnosis_service_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', mode VARCHAR(20) NOT NULL,
 source_note VARCHAR(256) NOT NULL, valid_from DATETIME NOT NULL, valid_to DATETIME NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL, created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 active_slot TINYINT GENERATED ALWAYS AS(CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_diagnosis_service_active(active_slot),
 CONSTRAINT ck_diagnosis_service_policy CHECK(revision>0 AND status IN ('DRAFT','PUBLISHED','STOPPED') AND mode IN ('AVAILABLE','LEAVE_MESSAGE','PAUSED') AND valid_from<valid_to),
 CONSTRAINT ck_diagnosis_service_publication CHECK(status='DRAFT' OR published_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS diagnosis_case (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, owner_id BIGINT UNSIGNED NOT NULL, vehicle_id BIGINT UNSIGNED NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1, status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
 assigned_operator_id BIGINT UNSIGNED DEFAULT NULL, vehicle_snapshot JSON NOT NULL,
 submission_key CHAR(36) NOT NULL, submission_hash CHAR(64) NOT NULL, admission_snapshot JSON NOT NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, closed_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_diagnosis_submission(owner_id,submission_key),
 KEY idx_diagnosis_owner(owner_id,id), KEY idx_diagnosis_assignee(assigned_operator_id,id),
 CONSTRAINT ck_diagnosis_case_status CHECK(status IN ('WAITING','PROCESSING','REPLIED','CLOSED') AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS diagnosis_message (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, case_id BIGINT UNSIGNED NOT NULL,
 case_revision BIGINT UNSIGNED NOT NULL, sender_type VARCHAR(16) NOT NULL, sender_id BIGINT UNSIGNED NOT NULL,
 body TEXT NOT NULL, admission_snapshot JSON DEFAULT NULL, created_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_diagnosis_message_revision(case_id,case_revision),
 CONSTRAINT ck_diagnosis_message_sender CHECK(sender_type IN ('OWNER','OPERATOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS diagnosis_case_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, case_id BIGINT UNSIGNED NOT NULL,
 case_revision BIGINT UNSIGNED NOT NULL, kind VARCHAR(16) NOT NULL, occurred_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_diagnosis_case_event(case_id,case_revision),
 CONSTRAINT ck_diagnosis_case_event_kind CHECK(kind IN ('CREATED','MESSAGE','ASSIGNED','REPLIED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
