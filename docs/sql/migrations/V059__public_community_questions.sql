-- No backfill of old private messages into a publishable identity/text source.
SET @qa_public_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='community_qa_message' AND column_name='source_hash')=0,'ALTER TABLE community_qa_message ADD COLUMN source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL','SELECT 1');
PREPARE qa_public_stmt FROM @qa_public_ddl; EXECUTE qa_public_stmt; DEALLOCATE PREPARE qa_public_stmt;
CREATE TABLE IF NOT EXISTS community_qa_publication (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, owner_id BIGINT UNSIGNED NOT NULL, case_id BIGINT UNSIGNED NOT NULL,
 question_message_id BIGINT UNSIGNED NOT NULL, answer_message_id BIGINT UNSIGNED NOT NULL,
 model_id BIGINT UNSIGNED NOT NULL, source_snapshot JSON NOT NULL, preview JSON NOT NULL,
 preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 consent_version VARCHAR(32) NOT NULL, revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', reason_code VARCHAR(24) DEFAULT NULL,
 public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, creation_result JSON DEFAULT NULL,
 active_case BIGINT UNSIGNED GENERATED ALWAYS AS(CASE WHEN status IN ('PENDING','APPROVED') THEN case_id ELSE NULL END) STORED,
 PRIMARY KEY(id), UNIQUE KEY uk_qa_public_submission(owner_id,submission_key), UNIQUE KEY uk_qa_public_active(active_case),
 UNIQUE KEY uk_qa_public_uuid(public_id), KEY idx_qa_public_model(model_id,status,id), KEY idx_qa_public_owner(owner_id,case_id,id),
 CONSTRAINT ck_qa_public_state CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN') AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS community_qa_publication_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, publication_id BIGINT UNSIGNED NOT NULL,
 revision BIGINT UNSIGNED NOT NULL, status VARCHAR(16) NOT NULL,
 preview_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 occurred_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_qa_public_event(publication_id,revision),
 CONSTRAINT ck_qa_public_event_state CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
