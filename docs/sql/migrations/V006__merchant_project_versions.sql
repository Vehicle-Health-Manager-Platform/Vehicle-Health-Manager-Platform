CREATE TABLE IF NOT EXISTS `merchant_project_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_project_id` BIGINT UNSIGNED NOT NULL,
  `version` BIGINT UNSIGNED NOT NULL,
  `price` DECIMAL(10,2) NOT NULL,
  `on_shelf` TINYINT NOT NULL,
  `actor_staff_id` BIGINT UNSIGNED DEFAULT NULL COMMENT 'NULL为迁移保留的初始版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_project_version` (`merchant_project_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家报价不可变版本';

INSERT INTO merchant_project_version(merchant_project_id,version,price,on_shelf,actor_staff_id,created_at)
SELECT p.id,1,p.price,p.on_shelf,NULL,p.updated_at FROM merchant_project p
WHERE p.is_deleted=0 AND p.on_shelf IN (0,1)
  AND NOT EXISTS(SELECT 1 FROM merchant_project_version v WHERE v.merchant_project_id=p.id);
