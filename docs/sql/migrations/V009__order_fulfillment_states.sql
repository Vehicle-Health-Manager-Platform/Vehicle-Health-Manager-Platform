-- V009: order fulfillment states.
-- Adds the transition audit trail plus the per-phase completion timestamps that
-- later phases write to prove their own precondition. Every column is a plain
-- nullable timestamp: V009 owns the state machine, not the business evidence.
-- Re-running on an unchanged schema is safe.

SET @fulfillment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='check_in_completed_at')=0, 'ALTER TABLE `order` ADD COLUMN `check_in_completed_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE fulfillment_statement FROM @fulfillment_ddl;
EXECUTE fulfillment_statement;
DEALLOCATE PREPARE fulfillment_statement;

SET @fulfillment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='owner_confirmed_at')=0, 'ALTER TABLE `order` ADD COLUMN `owner_confirmed_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE fulfillment_statement FROM @fulfillment_ddl;
EXECUTE fulfillment_statement;
DEALLOCATE PREPARE fulfillment_statement;

SET @fulfillment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='assigned_at')=0, 'ALTER TABLE `order` ADD COLUMN `assigned_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE fulfillment_statement FROM @fulfillment_ddl;
EXECUTE fulfillment_statement;
DEALLOCATE PREPARE fulfillment_statement;

SET @fulfillment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' AND column_name='service_report_ready_at')=0, 'ALTER TABLE `order` ADD COLUMN `service_report_ready_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE fulfillment_statement FROM @fulfillment_ddl;
EXECUTE fulfillment_statement;
DEALLOCATE PREPARE fulfillment_statement;

CREATE TABLE IF NOT EXISTS order_status_transition (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 order_id BIGINT UNSIGNED NOT NULL,
 merchant_id BIGINT UNSIGNED NOT NULL,
 from_status VARCHAR(32) NOT NULL,
 to_status VARCHAR(32) NOT NULL,
 action VARCHAR(64) NOT NULL,
 actor_type VARCHAR(16) NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,
 reason_code VARCHAR(32) DEFAULT NULL,
 note VARCHAR(200) DEFAULT NULL,
 occurred_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), KEY idx_transition_order(order_id,id), KEY idx_transition_merchant_shop(merchant_id,to_status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
