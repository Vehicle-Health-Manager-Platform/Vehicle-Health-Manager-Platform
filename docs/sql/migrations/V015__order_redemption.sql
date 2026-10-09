-- Immutable completion evidence; no verification code is stored here.
CREATE TABLE IF NOT EXISTS order_redemption (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 order_id BIGINT UNSIGNED NOT NULL,
 merchant_id BIGINT UNSIGNED NOT NULL,
 staff_id BIGINT UNSIGNED NOT NULL,
 payment_id BIGINT UNSIGNED NOT NULL,
 test_mode TINYINT NOT NULL,
 redeemed_at DATETIME NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_redemption_order(order_id), KEY idx_redemption_shop(merchant_id,redeemed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
