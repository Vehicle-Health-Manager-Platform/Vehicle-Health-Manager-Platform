-- R7b: explicit per-order consent, no default grants or historical backfill.
CREATE TABLE IF NOT EXISTS order_archive_grant (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 order_id BIGINT UNSIGNED NOT NULL,
 archive_id BIGINT UNSIGNED NOT NULL,
 owner_id BIGINT UNSIGNED NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL,
 source_version CHAR(64) DEFAULT NULL,
 granted_at DATETIME DEFAULT NULL,
 revoked_at DATETIME DEFAULT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_order_archive_grant(order_id,archive_id),
 KEY idx_order_archive_owner(owner_id,order_id),
 CONSTRAINT ck_order_archive_status CHECK(status IN ('ACTIVE','REVOKED')),
 CONSTRAINT ck_order_archive_source CHECK(status='REVOKED' OR (source_version IS NOT NULL AND granted_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
