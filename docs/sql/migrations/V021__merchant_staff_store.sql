-- R1b 门店员工：为 staff_account 增加展示名与创建者，供店长维护本店店员/技师。
-- 不新建平行表；角色复用既有列，员工码哈希沿用 V001 的 employee_code_hash。
-- 重复执行安全：列存在即跳过。

SET @staff_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='staff_account' AND column_name='display_name')=0,
 'ALTER TABLE staff_account ADD COLUMN display_name VARCHAR(64) DEFAULT NULL AFTER account',
 'SELECT 1');
PREPARE staff_statement FROM @staff_ddl; EXECUTE staff_statement; DEALLOCATE PREPARE staff_statement;

SET @staff_ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='staff_account' AND column_name='created_by')=0,
 'ALTER TABLE staff_account ADD COLUMN created_by BIGINT UNSIGNED DEFAULT NULL AFTER status',
 'SELECT 1');
PREPARE staff_statement FROM @staff_ddl; EXECUTE staff_statement; DEALLOCATE PREPARE staff_statement;
