-- Apply after V003. No changes to earlier migration files.
CREATE TABLE IF NOT EXISTS upload_request (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 owner_id BIGINT UNSIGNED NOT NULL,
 request_path VARCHAR(64) NOT NULL DEFAULT '/api/file/upload',
 idempotency_key CHAR(36) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 attempt_id CHAR(36) NOT NULL,
 object_key VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL,
 response_body JSON DEFAULT NULL,
 error_status INT DEFAULT NULL,
 deadline DATETIME NOT NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(id),
 UNIQUE KEY uk_upload_key(owner_id,request_path,idempotency_key),
 UNIQUE KEY uk_upload_object(object_key),
 KEY idx_upload_deadline(state,deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS upload_cleanup_task (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 request_id BIGINT UNSIGNED NOT NULL,
 object_key VARCHAR(64) NOT NULL,
 claim_id CHAR(36) DEFAULT NULL,
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_run DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 cleaned_at DATETIME DEFAULT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(id),
 UNIQUE KEY uk_cleanup_object(object_key),
 KEY idx_cleanup_due(next_run)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
