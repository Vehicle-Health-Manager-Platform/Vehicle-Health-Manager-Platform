-- Repeated atomic ALTERs preserve existing rows and never remove an enforcement window.
ALTER TABLE input_material DROP CHECK ck_input_material,
 ADD CONSTRAINT ck_input_material CHECK(purpose IN ('AI','ARCHIVE') AND scan_status='CLEAN' AND is_deleted IN (0,1)
  AND ((content_type='audio/mpeg' AND size_bytes BETWEEN 1 AND 3145728 AND duration_ms BETWEEN 1000 AND 60000 AND pages=0)
    OR (content_type='application/pdf' AND size_bytes BETWEEN 1 AND 10485760 AND duration_ms=0 AND pages BETWEEN 1 AND 50)
    OR (content_type IN ('image/jpeg','image/png') AND size_bytes BETWEEN 1 AND 4194304 AND duration_ms=0 AND pages=1)));

ALTER TABLE recognition_job
 DROP INDEX idx_recognition_claim, ADD INDEX idx_recognition_claim(task,state,expires_at,id),
 DROP INDEX idx_recognition_lease, ADD INDEX idx_recognition_lease(task,state,lease_until),
 DROP CHECK ck_recognition_job,
 ADD CONSTRAINT ck_recognition_job CHECK(task IN ('ASR','DOCUMENT') AND purpose IN ('AI','ARCHIVE')
  AND state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED')
  AND ((state='SUCCEEDED' AND result_json IS NOT NULL AND error_code IS NULL)
    OR (state<>'SUCCEEDED' AND result_json IS NULL))
  AND ((state='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
    OR (state<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
  AND ((state IN ('QUEUED','RUNNING','SUCCEEDED') AND error_code IS NULL)
    OR (state='FAILED' AND (error_code IN ('UNAVAILABLE','SOURCE_CHANGED','WORKER_LOST','INVALID_RESULT')
      OR (task='DOCUMENT' AND error_code IN ('INVALID_DOCUMENT','LIMIT_EXCEEDED'))))
    OR (state='CANCELLED' AND error_code IN ('AUTH_REVOKED','USER_CANCELLED','FEATURE_DISABLED'))
    OR (state='EXPIRED' AND error_code='EXPIRED'))
  AND (state IN ('QUEUED','RUNNING','SUCCEEDED') OR error_code IS NOT NULL));
