package com.autocare.platform.gateway.identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class JdbcOperatorAdminDiagnosisTest extends JdbcOperatorIdentityTest {
 String session(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",id,UUID.randomUUID().toString());return id;}
 MockEnvironment env(String action){return new MockEnvironment().withProperty("spring.main.web-application-type","none").withProperty("operator.action",action).withProperty("operator.id","1");}
 void run(MockEnvironment env){try(var context=new GenericApplicationContext()){context.refresh();new OperatorAdminCli(env,context,jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new ObjectMapper()).run(new DefaultApplicationArguments(new String[0]));}}
 @Test void diagnosisGrantsAreIndependentAndRevokeSessions(){
  String old=session();run(env("diagnose-manage-permission").withProperty("OPERATOR_CAN_DIAGNOSE_MANAGE","true"));assertEquals(1,jdbc.queryForObject("SELECT can_diagnose_manage FROM operator_account WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT can_diagnose FROM operator_account WHERE id=1",Integer.class));assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,old));
  run(env("diagnose-permission").withProperty("OPERATOR_CAN_DIAGNOSE","true"));String fresh=session();run(env("diagnose-permission").withProperty("OPERATOR_CAN_DIAGNOSE","false"));assertEquals(0,jdbc.queryForObject("SELECT can_diagnose FROM operator_account WHERE id=1",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT can_diagnose_manage FROM operator_account WHERE id=1",Integer.class));assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,fresh));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='OPERATOR_ACCOUNT_MANAGE'",Integer.class));
 }
 @Test void explicitFlagsAndNoHttpAreRequired(){
  session();assertThrows(Exception.class,()->run(env("diagnose-permission")));assertThrows(Exception.class,()->run(env("diagnose-permission").withProperty("OPERATOR_CAN_DIAGNOSE","TRUE")));assertThrows(Exception.class,()->run(env("diagnose-manage-permission").withProperty("spring.main.web-application-type","servlet").withProperty("OPERATOR_CAN_DIAGNOSE_MANAGE","true")));assertEquals(0,jdbc.queryForObject("SELECT can_diagnose+can_diagnose_manage FROM operator_account WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE revoked_at IS NOT NULL",Integer.class));
 }
 @Test void auditFailureRollsBackPermissionAndSessionRevocation(){
  String id=session();jdbc.execute("CREATE TRIGGER reject_operator_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");assertThrows(Exception.class,()->run(env("diagnose-permission").withProperty("OPERATOR_CAN_DIAGNOSE","true")));assertEquals(0,jdbc.queryForObject("SELECT can_diagnose FROM operator_account WHERE id=1",Integer.class));assertNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,id));
 }
}
