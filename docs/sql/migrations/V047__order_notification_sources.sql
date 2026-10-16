-- Preserve existing outbox rows and recipients; never infer historical business events.
SET @notice_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='service_notification_outbox' AND column_name='source_key')=0,
 'ALTER TABLE service_notification_outbox ADD COLUMN source_key VARCHAR(96) NOT NULL DEFAULT ''ONCE''', 'SELECT 1');
PREPARE notice_stmt FROM @notice_ddl; EXECUTE notice_stmt; DEALLOCATE PREPARE notice_stmt;
SET @notice_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='service_notification_outbox' AND index_name='uk_service_notification_source')=2,
 'ALTER TABLE service_notification_outbox DROP INDEX uk_service_notification_source, ADD UNIQUE KEY uk_service_notification_source(kind,order_id,source_key)', 'SELECT 1');
PREPARE notice_stmt FROM @notice_ddl; EXECUTE notice_stmt; DEALLOCATE PREPARE notice_stmt;
SET @notice_ddl = IF((SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='service_notification_outbox' AND constraint_name='ck_service_notification_kind')=1,
 'ALTER TABLE service_notification_outbox DROP CHECK ck_service_notification_kind', 'SELECT 1');
PREPARE notice_stmt FROM @notice_ddl; EXECUTE notice_stmt; DEALLOCATE PREPARE notice_stmt;
ALTER TABLE service_notification_outbox ADD CONSTRAINT ck_service_notification_kind CHECK(kind IN (
 'ORDER_PAID','ORDER_CHECK_IN','ORDER_ASSIGN','ORDER_TECH_ACCEPT','ORDER_SERVICE_FINISH','ORDER_COMPLETE',
 'ORDER_CLOSED','REFUND_REQUESTED','REFUND_WITHDRAWN','REFUND_REJECTED','REFUND_ACCEPTED','REFUND_SUCCEEDED','REFUND_FAILED',
 'DISPUTE_OPENED','DISPUTE_HANDLED','DISPUTE_REJECTED','DISPUTE_RESOLVED'));
