-- Independent private community questions; no diagnostic content, inherited permission or seeded service.
SET @diag_add = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_community_answer')=0,
 'ALTER TABLE operator_account ADD COLUMN can_community_answer TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE diag_stmt FROM @diag_add; EXECUTE diag_stmt; DEALLOCATE PREPARE diag_stmt;
SET @diag_add = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_community_manage')=0,
 'ALTER TABLE operator_account ADD COLUMN can_community_manage TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE diag_stmt FROM @diag_add; EXECUTE diag_stmt; DEALLOCATE PREPARE diag_stmt;
CREATE TABLE IF NOT EXISTS community_qa_control (
 id TINYINT UNSIGNED NOT NULL, PRIMARY KEY(id), CONSTRAINT ck_community_qa_control CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO community_qa_control(id) VALUES(1);
CREATE TABLE IF NOT EXISTS community_qa_service_policy (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', mode VARCHAR(20) NOT NULL,
 source_note VARCHAR(256) NOT NULL, valid_from DATETIME NOT NULL, valid_to DATETIME NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL, created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, creation_result JSON DEFAULT NULL,
 active_slot TINYINT GENERATED ALWAYS AS(CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_community_qa_service_active(active_slot), UNIQUE KEY uk_community_qa_policy_submission(created_by,submission_key),
 CONSTRAINT ck_community_qa_service_policy CHECK(revision>0 AND status IN ('DRAFT','PUBLISHED','STOPPED') AND mode IN ('AVAILABLE','LEAVE_MESSAGE','PAUSED') AND valid_from<valid_to),
 CONSTRAINT ck_community_qa_service_publication CHECK(status='DRAFT' OR published_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS community_qa_case (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, owner_id BIGINT UNSIGNED NOT NULL, vehicle_id BIGINT UNSIGNED NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1, status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
 assigned_operator_id BIGINT UNSIGNED DEFAULT NULL, vehicle_snapshot JSON NOT NULL,
 category VARCHAR(16) NOT NULL, creation_result JSON DEFAULT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, admission_snapshot JSON NOT NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL, closed_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_community_qa_submission(owner_id,submission_key),
 KEY idx_community_qa_owner(owner_id,id), KEY idx_community_qa_assignee(assigned_operator_id,id),
 CONSTRAINT ck_community_qa_category CHECK(category IN ('MAINTENANCE','REPAIR','FAULT')),
 CONSTRAINT ck_community_qa_case_status CHECK(status IN ('WAITING','PROCESSING','REPLIED','CLOSED') AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS community_qa_message (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, case_id BIGINT UNSIGNED NOT NULL,
 case_revision BIGINT UNSIGNED NOT NULL, sender_type VARCHAR(16) NOT NULL, sender_id BIGINT UNSIGNED NOT NULL,
 body TEXT NOT NULL, admission_snapshot JSON DEFAULT NULL, created_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_community_qa_message_revision(case_id,case_revision),
 CONSTRAINT ck_community_qa_message_sender CHECK(sender_type IN ('OWNER','OPERATOR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS community_qa_case_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, case_id BIGINT UNSIGNED NOT NULL,
 case_revision BIGINT UNSIGNED NOT NULL, kind VARCHAR(16) NOT NULL, occurred_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_community_qa_case_event(case_id,case_revision),
 CONSTRAINT ck_community_qa_case_event_kind CHECK(kind IN ('CREATED','MESSAGE','ASSIGNED','REPLIED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
