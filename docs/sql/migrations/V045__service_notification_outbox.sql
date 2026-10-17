-- Current business events only. Existing notifications do not gain new provenance.
CREATE TABLE IF NOT EXISTS service_notification_outbox (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 event_id CHAR(36) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 order_id BIGINT UNSIGNED NOT NULL,
 owner_id BIGINT UNSIGNED NOT NULL,
 merchant_id BIGINT UNSIGNED NOT NULL,
 technician_id BIGINT UNSIGNED DEFAULT NULL,
 occurred_at DATETIME NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT UNSIGNED NOT NULL DEFAULT 0,
 next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error VARCHAR(32) DEFAULT NULL,
 completed_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_service_notification_event(event_id),
 UNIQUE KEY uk_service_notification_source(kind,order_id),
 KEY idx_service_notification_pending(status,next_attempt_at,id),
 CONSTRAINT ck_service_notification_status CHECK(status IN ('PENDING','DONE')),
 CONSTRAINT ck_service_notification_kind CHECK(kind IN ('ORDER_PAID','ORDER_CHECK_IN','ORDER_ASSIGN','ORDER_TECH_ACCEPT','ORDER_SERVICE_FINISH','ORDER_COMPLETE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
