-- V008: payment foundation. Preserve historical transactions.
SET @payment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='payment_no')=0, 'ALTER TABLE `payment` ADD COLUMN `payment_no` VARCHAR(40) DEFAULT NULL', 'SELECT 1');
PREPARE payment_statement FROM @payment_ddl;
EXECUTE payment_statement;
DEALLOCATE PREPARE payment_statement;
SET @payment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='currency')=0, 'ALTER TABLE `payment` ADD COLUMN `currency` VARCHAR(3) NOT NULL DEFAULT ''CNY''', 'SELECT 1');
PREPARE payment_statement FROM @payment_ddl;
EXECUTE payment_statement;
DEALLOCATE PREPARE payment_statement;
SET @payment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='expires_at')=0, 'ALTER TABLE `payment` ADD COLUMN `expires_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE payment_statement FROM @payment_ddl;
EXECUTE payment_statement;
DEALLOCATE PREPARE payment_statement;
SET @payment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='failed_at')=0, 'ALTER TABLE `payment` ADD COLUMN `failed_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE payment_statement FROM @payment_ddl;
EXECUTE payment_statement;
DEALLOCATE PREPARE payment_statement;
SET @payment_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='payment' AND column_name='closed_at')=0, 'ALTER TABLE `payment` ADD COLUMN `closed_at` DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE payment_statement FROM @payment_ddl;
EXECUTE payment_statement;
DEALLOCATE PREPARE payment_statement;
SET @payment_ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='payment' AND index_name='uk_payment_no')=0, 'ALTER TABLE `payment` ADD UNIQUE INDEX `uk_payment_no` (payment_no)', 'SELECT 1');
PREPARE payment_statement FROM @payment_ddl;
EXECUTE payment_statement;
DEALLOCATE PREPARE payment_statement;
CREATE TABLE IF NOT EXISTS payment_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 channel VARCHAR(32) NOT NULL,
 event_id CHAR(36) NOT NULL,
 payment_id BIGINT UNSIGNED NOT NULL,
 event_hash CHAR(64) NOT NULL,
 outcome VARCHAR(32) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_payment_event(channel,event_id), KEY idx_payment(payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS payment_exception (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 payment_id BIGINT UNSIGNED NOT NULL,
 order_id BIGINT UNSIGNED NOT NULL,
 channel VARCHAR(32) NOT NULL,
 amount DECIMAL(10,2) NOT NULL,
 reason VARCHAR(32) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 is_deleted TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_exception_payment(payment_id), KEY idx_exception_status(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
