-- Candidate-only durable work; never store JWT, source object keys or audio bytes.
CREATE TABLE IF NOT EXISTS recognition_job (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 owner_id BIGINT UNSIGNED NOT NULL,
 vehicle_id BIGINT UNSIGNED NOT NULL,
 material_id BIGINT UNSIGNED NOT NULL,
 purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ASR',
 origin_session_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 origin_expires_at DATETIME NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
 error_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 result_json JSON NULL,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_recognition_claim(state,expires_at,id),
 KEY idx_recognition_owner(owner_id,state,expires_at,id),
 KEY idx_recognition_lease(state,lease_until),
 CONSTRAINT ck_recognition_job CHECK(task='ASR' AND purpose IN ('AI','ARCHIVE')
  AND state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')
  AND ((state='SUCCEEDED' AND result_json IS NOT NULL AND error_code IS NULL)
    OR (state<>'SUCCEEDED' AND result_json IS NULL))
  AND ((state='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (state<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
  AND ((state IN ('QUEUED','RUNNING','SUCCEEDED') AND error_code IS NULL)
    OR (state='FAILED' AND error_code IN ('UNAVAILABLE','SOURCE_CHANGED','WORKER_LOST','INVALID_RESULT'))
    OR (state='CANCELLED' AND error_code IN ('AUTH_REVOKED','USER_CANCELLED','FEATURE_DISABLED'))
    OR (state='EXPIRED' AND error_code='EXPIRED'))
  AND (state IN ('QUEUED','RUNNING','SUCCEEDED') OR error_code IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
