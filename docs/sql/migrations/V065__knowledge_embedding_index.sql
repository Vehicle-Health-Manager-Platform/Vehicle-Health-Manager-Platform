-- No backfill of embedding permission; legacy knowledge payloads remain byte-for-byte unchanged.
CREATE TABLE IF NOT EXISTS knowledge_embedding_job (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 document_id BIGINT UNSIGNED NOT NULL, document_revision BIGINT UNSIGNED NOT NULL,
 source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_json JSON NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'QUEUED', error_code VARCHAR(32) DEFAULT NULL,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
 lease_until DATETIME DEFAULT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 UNIQUE KEY uk_embedding_source(document_id,document_revision,source_hash,profile_hash),
 KEY idx_embedding_claim(profile_hash,state,id),
 CONSTRAINT fk_embedding_document FOREIGN KEY(document_id) REFERENCES knowledge_document(id) ON DELETE CASCADE,
 CONSTRAINT ck_embedding_job CHECK(document_revision>0 AND state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')
  AND ((state='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (state<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
  AND ((state IN ('QUEUED','RUNNING','SUCCEEDED') AND error_code IS NULL)
    OR (state='FAILED' AND error_code IN ('WORKER_LOST','UNAVAILABLE','INVALID_VECTOR','LIMIT_EXCEEDED'))
    OR (state='CANCELLED' AND error_code IN ('SOURCE_CHANGED','FEATURE_DISABLED'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS knowledge_embedding_chunk (
 job_id BIGINT UNSIGNED NOT NULL, ordinal INT NOT NULL,
 start_offset INT NOT NULL, end_offset INT NOT NULL,
 chunk_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_text TEXT NOT NULL, dimension INT NOT NULL, vector_json JSON NOT NULL,
 PRIMARY KEY(job_id,ordinal),
 CONSTRAINT fk_embedding_chunk_job FOREIGN KEY(job_id) REFERENCES knowledge_embedding_job(id) ON DELETE CASCADE,
 CONSTRAINT ck_embedding_chunk CHECK(ordinal BETWEEN 0 AND 63 AND start_offset>=0 AND end_offset>start_offset AND end_offset<=20000
  AND CHAR_LENGTH(content_text) BETWEEN 1 AND 256 AND dimension BETWEEN 1 AND 1024 AND JSON_TYPE(vector_json)='ARRAY' AND JSON_LENGTH(vector_json)=dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
