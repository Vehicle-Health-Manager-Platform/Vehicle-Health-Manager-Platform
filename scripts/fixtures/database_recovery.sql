-- Synthetic data only, loaded into a newly owned recovery container after V061.
INSERT INTO user(id,openid,nickname,point_balance,status) VALUES(1,'synthetic-recovery-owner','合成恢复车主',17,1);
INSERT INTO vehicle(id,user_id,current_mileage,plate_no,vin) VALUES(1,1,12345,'粤A12345','LTEST123456789012');
INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at)
 VALUES(1,1,1,3,JSON_OBJECT('title','合成保养','notes','中文、换行\n与 NULL 的恢复','mileage',12345),UTC_TIMESTAMP());
INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'合成恢复店','非真实地址',1);
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(1,1,'MERCHANT','synthetic-recovery-store','ACTIVE');
INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,project_snapshot)
 VALUES(1,'synthetic-recovery-order',1,1,1,12.34,12.34,'PAID',JSON_OBJECT('project_name','合成检查','test_mode',TRUE));
INSERT INTO payment(id,order_id,channel,payment_no,channel_payment_no,currency,amount,status,paid_at)
 VALUES(1,1,'LOCAL_TEST','P00000000000000000000000000000001','synthetic-recovery-receipt','CNY',12.34,'SUCCEEDED',UTC_TIMESTAMP());
INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome)
 VALUES('LOCAL_TEST','00000000-0000-4000-8000-000000000001',1,REPEAT('a',64),'PAID');
INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at)
 VALUES('00000000-0000-4000-8000-000000000002','user',1,'OWNER','synthetic-recovery',REPEAT('b',64),'2030-01-01');
DELIMITER //
CREATE TRIGGER recovery_mileage_guard BEFORE UPDATE ON vehicle FOR EACH ROW
BEGIN
 IF NEW.current_mileage < OLD.current_mileage THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic recovery guard';
 END IF;
END//
CREATE PROCEDURE recovery_vehicle_count() SELECT COUNT(*) FROM vehicle//
CREATE EVENT recovery_disabled_event ON SCHEDULE EVERY 1 DAY STARTS '2030-01-01 00:00:00'
 DISABLE DO SET @recovery_probe=1//
DELIMITER ;
