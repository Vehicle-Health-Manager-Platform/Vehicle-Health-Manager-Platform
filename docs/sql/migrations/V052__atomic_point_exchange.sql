-- Keep old point_balance/point_flow untouched. All rows below require new verified evidence.
CREATE TABLE IF NOT EXISTS point_exchange_record (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 user_id BIGINT UNSIGNED NOT NULL,
 catalog_id BIGINT UNSIGNED NOT NULL,
 catalog_revision BIGINT UNSIGNED NOT NULL,
 coupon_id BIGINT UNSIGNED NOT NULL,
 publication_revision BIGINT UNSIGNED NOT NULL,
 pool_revision BIGINT UNSIGNED NOT NULL,
 points_cost INT UNSIGNED NOT NULL,
 catalog_snapshot JSON NOT NULL,
 identity_session CHAR(36) NOT NULL,
 submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 submission_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_coupon_id BIGINT UNSIGNED NOT NULL,
 creation_result JSON DEFAULT NULL,
 exchanged_at DATETIME NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_point_exchange_submission(user_id,submission_key),
 UNIQUE KEY uk_point_exchange_coupon(user_coupon_id),
 KEY idx_point_exchange_limit(user_id,coupon_id,id),
 CONSTRAINT ck_point_exchange_cost CHECK(points_cost BETWEEN 1 AND 1000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One atomic DDL per existing table; repeated migration does not drop or rebuild evidence.
SET @point_exchange_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='point_verified_entry' AND column_name='exchange_id')=0,
'ALTER TABLE point_verified_entry DROP CHECK ck_point_verified_delta, MODIFY event_id BIGINT UNSIGNED DEFAULT NULL, MODIFY delta INT NOT NULL, ADD COLUMN exchange_id BIGINT UNSIGNED DEFAULT NULL, ADD UNIQUE KEY uk_point_verified_exchange(exchange_id), ADD CONSTRAINT ck_point_verified_delta CHECK((delta BETWEEN 1 AND 1000000 AND event_id IS NOT NULL AND exchange_id IS NULL) OR (delta BETWEEN -1000000 AND -1 AND event_id IS NULL AND exchange_id IS NOT NULL))','SELECT 1');
PREPARE point_exchange_stmt FROM @point_exchange_ddl; EXECUTE point_exchange_stmt; DEALLOCATE PREPARE point_exchange_stmt;
SET @point_exchange_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupon_issue' AND column_name='points_exchange_id')=0,
'ALTER TABLE coupon_issue DROP CHECK ck_coupon_issue_origin, ADD COLUMN points_exchange_id BIGINT UNSIGNED DEFAULT NULL, ADD UNIQUE KEY uk_coupon_issue_points(points_exchange_id), ADD CONSTRAINT ck_coupon_issue_origin CHECK((origin=''PUBLIC'' AND reward_item_id IS NULL AND points_exchange_id IS NULL) OR (origin=''INVITE'' AND reward_item_id IS NOT NULL AND points_exchange_id IS NULL) OR (origin=''POINTS'' AND reward_item_id IS NULL AND points_exchange_id IS NOT NULL))','SELECT 1');
PREPARE point_exchange_stmt FROM @point_exchange_ddl; EXECUTE point_exchange_stmt; DEALLOCATE PREPARE point_exchange_stmt;
