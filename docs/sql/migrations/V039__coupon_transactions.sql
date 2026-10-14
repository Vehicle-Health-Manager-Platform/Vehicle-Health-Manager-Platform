-- R5b only records new proven contracts; legacy coupon orders remain unverified.
CREATE TABLE IF NOT EXISTS order_coupon_allocation (
 order_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 creation_key CHAR(36) NOT NULL, request_digest CHAR(64) NOT NULL,
 user_coupon_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 coupon_id BIGINT UNSIGNED NOT NULL, publication_revision BIGINT UNSIGNED NOT NULL,
 merchant_id BIGINT UNSIGNED NOT NULL, project_id BIGINT UNSIGNED NOT NULL,
 terms_json JSON NOT NULL, original_amount DECIMAL(12,2) NOT NULL,
 discount_amount DECIMAL(12,2) NOT NULL, cash_amount DECIMAL(12,2) NOT NULL,
 state VARCHAR(24) NOT NULL, reserved_until DATETIME NOT NULL,
 rule_version VARCHAR(32) NOT NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 recovery_after DATETIME NULL, recovery_error VARCHAR(32) NULL,
 active_user_coupon_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN state IN ('RESERVED','CONSUMED') THEN user_coupon_id ELSE NULL END) STORED,
 UNIQUE KEY uk_coupon_active_allocation(active_user_coupon_id),
 UNIQUE KEY uk_coupon_creation_intent(user_id,creation_key),
 KEY idx_coupon_allocation_recovery(state,updated_at,order_id),
 CONSTRAINT ck_coupon_allocation CHECK(state IN ('RESERVED','CONSUMED','RELEASED','RETURNED') AND original_amount=discount_amount+cash_amount AND discount_amount>0 AND cash_amount>=0.01 AND rule_version='R5B_CASH_V1' AND JSON_TYPE(terms_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon_transaction_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_coupon_id BIGINT UNSIGNED NOT NULL, event_key VARCHAR(96) NOT NULL,
 kind VARCHAR(24) NOT NULL, order_id BIGINT UNSIGNED NULL,
 payment_id BIGINT UNSIGNED NULL, refund_id BIGINT UNSIGNED NULL, redemption_id BIGINT UNSIGNED NULL,
 before_status VARCHAR(24) NOT NULL, after_status VARCHAR(24) NOT NULL,
 rule_version VARCHAR(32) NOT NULL, occurred_at DATETIME NOT NULL,
 UNIQUE KEY uk_coupon_transaction_event(event_key),
 KEY idx_coupon_transaction_owner(user_coupon_id,id),
 CONSTRAINT ck_coupon_transaction_kind CHECK(kind IN ('RESERVE','CONSUME','RELEASE','REFUND_RETURN','SERVICE_REDEEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon_service_authorization (
 user_coupon_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 revision BIGINT UNSIGNED NOT NULL, code_hash CHAR(64) NOT NULL,
 expires_at DATETIME NOT NULL, created_at DATETIME NOT NULL,
 CONSTRAINT ck_coupon_service_authorization CHECK(revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon_service_redemption (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_coupon_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 coupon_id BIGINT UNSIGNED NOT NULL, publication_revision BIGINT UNSIGNED NOT NULL,
 merchant_id BIGINT UNSIGNED NOT NULL, project_id BIGINT UNSIGNED NOT NULL, staff_id BIGINT UNSIGNED NOT NULL,
 authorization_revision BIGINT UNSIGNED NOT NULL, terms_json JSON NOT NULL,
 redeemed_at DATETIME NOT NULL, commission_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
 rule_version VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_coupon_service_redemption(user_coupon_id),
 KEY idx_coupon_service_merchant(merchant_id,id),
 CONSTRAINT ck_coupon_service_redemption CHECK(commission_amount=0.00 AND rule_version='R5B_SERVICE_V1' AND JSON_TYPE(terms_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
