-- Synthetic fixtures for the isolated vehicle-auth-local Docker database ONLY.
-- Not a production catalog or owner identity. Never run against production.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO brand(id,name,country) VALUES(9100601,'本地合成测试品牌','测试') ON DUPLICATE KEY UPDATE id=id;
INSERT INTO series(id,brand_id,name) VALUES(9100601,9100601,'本地合成测试车系') ON DUPLICATE KEY UPDATE id=id;
INSERT INTO model(id,series_id,year,config_name,power_type) VALUES
  (9100601,9100601,'2026','合成配置A','燃油'),
  (9100602,9100601,'2026','合成配置B','燃油') ON DUPLICATE KEY UPDATE id=id;
INSERT INTO user(id,nickname,openid) VALUES(9100690,'本地合成隔离车主','local-business-synthetic-foreign-owner') ON DUPLICATE KEY UPDATE id=id;
INSERT INTO vehicle(id,user_id,model_id,current_mileage) VALUES(9100690,9100690,9100601,100) ON DUPLICATE KEY UPDATE id=id;
COMMIT;
