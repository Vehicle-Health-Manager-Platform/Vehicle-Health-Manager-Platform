-- R2c immutable booking facts and append-only accrued commission; no funds transfer.
SET @commission_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='merchant_commission_policy' AND column_name='rule_version')=0,
 'ALTER TABLE merchant_commission_policy ADD COLUMN rule_version VARCHAR(24) NULL, ADD COLUMN source_application_id BIGINT UNSIGNED NULL','SELECT 1');
PREPARE commission_stmt FROM @commission_ddl; EXECUTE commission_stmt; DEALLOCATE PREPARE commission_stmt;
-- Only one matched, immutable approval can supply a calendar exemption origin.
INSERT INTO merchant_commission_policy(merchant_id,rate_percent,waived_until,effective_from,rule_version,source_application_id)
SELECT m.id,3.00,DATE_SUB(DATE_ADD(DATE_ADD(r.decided_at,INTERVAL 8 HOUR),INTERVAL 3 MONTH),INTERVAL 8 HOUR),r.decided_at,'R2C_V1',a.id
FROM merchant m JOIN merchant_application a ON a.merchant_id=m.id AND a.status='APPROVED' AND a.is_deleted=0
JOIN merchant_application_review r ON r.application_id=a.id AND r.merchant_id=m.id AND r.revision=a.revision AND r.decision='APPROVED' AND r.decided_at=a.reviewed_at AND r.operator_id=a.reviewed_by
WHERE m.is_deleted=0 AND m.status=1 AND NOT EXISTS(SELECT 1 FROM merchant_commission_policy p WHERE p.merchant_id=m.id)
AND (SELECT COUNT(*) FROM merchant_application_review r2 WHERE r2.merchant_id=m.id AND r2.decision='APPROVED')=1;
CREATE TABLE IF NOT EXISTS order_commission_snapshot (
 order_id BIGINT UNSIGNED NOT NULL, merchant_id BIGINT UNSIGNED NOT NULL,
 policy_id BIGINT UNSIGNED NULL, booked_at DATETIME NOT NULL, base_amount DECIMAL(12,2) NOT NULL,
 rate_percent DECIMAL(5,2) NULL, waived_until DATETIME NULL,
 state VARCHAR(24) NOT NULL, exemption VARCHAR(32) NOT NULL, commission_amount DECIMAL(12,2) NULL,
 currency VARCHAR(3) NOT NULL DEFAULT 'CNY', rule_version VARCHAR(24) NOT NULL,
 PRIMARY KEY(order_id), KEY idx_commission_snapshot_shop(merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS merchant_commission_entry (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, merchant_id BIGINT UNSIGNED NOT NULL,
 order_id BIGINT UNSIGNED NOT NULL, payment_id BIGINT UNSIGNED NOT NULL, refund_id BIGINT UNSIGNED NULL,
 event_key VARCHAR(16) NOT NULL, kind VARCHAR(24) NOT NULL, reference_entry_id BIGINT UNSIGNED NULL,
 base_amount DECIMAL(12,2) NOT NULL, rate_percent DECIMAL(5,2) NULL, exemption VARCHAR(32) NOT NULL,
 amount DECIMAL(12,2) NULL, currency VARCHAR(3) NOT NULL DEFAULT 'CNY', occurred_at DATETIME NOT NULL,
 test_mode TINYINT NOT NULL, rule_version VARCHAR(24) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_commission_order_event(order_id,event_key), UNIQUE KEY uk_commission_refund(refund_id),
 KEY idx_commission_shop_time(merchant_id,occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
