-- New issuance evidence only. Never promote legacy coupons or grant permissions.
SET @coupon_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_coupon')=0,'ALTER TABLE operator_account ADD COLUMN can_coupon TINYINT NOT NULL DEFAULT 0','SELECT 1');
PREPARE coupon_stmt FROM @coupon_ddl;
EXECUTE coupon_stmt;
DEALLOCATE PREPARE coupon_stmt;

CREATE TABLE IF NOT EXISTS coupon_source (
 coupon_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 merchant_id BIGINT UNSIGNED NOT NULL, project_id BIGINT UNSIGNED NOT NULL,
 terms_json JSON NOT NULL, revision BIGINT UNSIGNED NOT NULL,
 pool_selected TINYINT NOT NULL DEFAULT 0, pool_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NOT NULL,
 CONSTRAINT ck_coupon_source CHECK(revision>0 AND pool_selected IN(0,1) AND JSON_TYPE(terms_json)='OBJECT'),
 KEY idx_coupon_source_merchant(merchant_id,coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon_publication (
 coupon_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 revision BIGINT UNSIGNED NOT NULL, terms_json JSON NOT NULL,
 published_by BIGINT UNSIGNED NOT NULL, published_at DATETIME NOT NULL,
 CONSTRAINT ck_coupon_publication CHECK(revision>0 AND JSON_TYPE(terms_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon_issue (
 user_coupon_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 coupon_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 publication_revision BIGINT UNSIGNED NOT NULL, issued_at DATETIME NOT NULL,
 UNIQUE KEY uk_coupon_issue_owner(coupon_id,user_id),
 CONSTRAINT ck_coupon_issue CHECK(publication_revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS coupon_stock_event (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 coupon_id BIGINT UNSIGNED NOT NULL, user_coupon_id BIGINT UNSIGNED NOT NULL,
 quantity_delta INT NOT NULL, stock_after INT NOT NULL, occurred_at DATETIME NOT NULL,
 UNIQUE KEY uk_coupon_stock_issue(user_coupon_id),
 CONSTRAINT ck_coupon_stock_event CHECK(quantity_delta=-1 AND stock_after>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
