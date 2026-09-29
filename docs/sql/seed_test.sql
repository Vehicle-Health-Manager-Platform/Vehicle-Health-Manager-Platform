-- Synthetic test fixtures only. Never run against production.
INSERT IGNORE INTO `brand` (`id`,`name`) VALUES (900001, '演示品牌A');
INSERT IGNORE INTO `series` (`id`,`brand_id`,`name`) VALUES (900001, 900001, '演示车系A');
INSERT IGNORE INTO `model` (`id`,`series_id`,`year`,`config_name`) VALUES (900001, 900001, '2025', '演示配置');
INSERT IGNORE INTO `standard_project` (`id`,`project_name`,`category`,`service_content`,`base_price_low`,`base_price_high`)
VALUES (900001, '演示基础保养', 1, '仅供本地联调的测试项目', 100.00, 200.00);
INSERT IGNORE INTO `merchant` (`id`,`merchant_type`,`name`,`address`,`status`,`commission_rate`)
VALUES (900001, 2, '演示商家A', '测试地址，请勿用于导航', 1, NULL);
INSERT IGNORE INTO `merchant_project` (`id`,`merchant_id`,`project_id`,`price`)
VALUES (900001, 900001, 900001, 150.00);
