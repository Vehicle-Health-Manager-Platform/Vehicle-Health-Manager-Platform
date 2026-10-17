SET @knowledge_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_knowledge')=0,'ALTER TABLE operator_account ADD COLUMN can_knowledge TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE knowledge_stmt FROM @knowledge_ddl; EXECUTE knowledge_stmt; DEALLOCATE PREPARE knowledge_stmt;
CREATE TABLE IF NOT EXISTS knowledge_control(id TINYINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT IGNORE INTO knowledge_control(id) VALUES(1);
CREATE TABLE IF NOT EXISTS knowledge_document (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 model_id BIGINT UNSIGNED NOT NULL, payload JSON NOT NULL,
 valid_from DATETIME NOT NULL, valid_to DATETIME NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 created_at DATETIME NOT NULL, published_at DATETIME DEFAULT NULL,
 submission_actor BIGINT UNSIGNED NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 creation_result JSON DEFAULT NULL,
 UNIQUE KEY uk_knowledge_submission(submission_actor,submission_key), UNIQUE KEY uk_knowledge_public(public_id),
 KEY idx_knowledge_model(model_id,status,id),
 CONSTRAINT ck_knowledge_state CHECK(status IN ('DRAFT','PUBLISHED','STOPPED') AND revision>0 AND valid_from<valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_document_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 document_id BIGINT UNSIGNED NOT NULL, revision BIGINT UNSIGNED NOT NULL,
 status VARCHAR(16) NOT NULL, source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 occurred_at DATETIME NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_knowledge_revision(document_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
