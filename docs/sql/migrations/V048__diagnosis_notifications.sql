-- Only new diagnosis events capture recipients. No historical event backfill.
CREATE TABLE IF NOT EXISTS diagnosis_notification_outbox (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 event_id CHAR(36) NOT NULL, source_event_id BIGINT UNSIGNED NOT NULL,
 case_id BIGINT UNSIGNED NOT NULL, case_revision BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(32) NOT NULL, occurred_at DATETIME NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL, last_error VARCHAR(32) DEFAULT NULL, completed_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_diagnosis_notice_event(event_id),
 UNIQUE KEY uk_diagnosis_notice_source(source_event_id),
 UNIQUE KEY uk_diagnosis_notice_revision(case_id,case_revision),
 KEY idx_diagnosis_notice_pending(status,next_attempt_at,id),
 CONSTRAINT ck_diagnosis_notice_status CHECK(status IN ('PENDING','DONE')),
 CONSTRAINT ck_diagnosis_notice_kind CHECK(kind IN ('DIAGNOSIS_CREATED','DIAGNOSIS_MESSAGE','DIAGNOSIS_ASSIGNED','DIAGNOSIS_REPLIED','DIAGNOSIS_CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS diagnosis_notification_target (
 outbox_id BIGINT UNSIGNED NOT NULL, recipient_type VARCHAR(24) NOT NULL, recipient_id BIGINT UNSIGNED NOT NULL,
 can_detail TINYINT NOT NULL, can_queue TINYINT NOT NULL,
 PRIMARY KEY(outbox_id,recipient_type,recipient_id),
 CONSTRAINT ck_diagnosis_notice_target CHECK(
  (recipient_type='user' AND can_detail=1 AND can_queue=0) OR
  (recipient_type='operator_account' AND can_detail IN (0,1) AND can_queue IN (0,1) AND can_detail+can_queue>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
