package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.HealthObservationInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class HealthPolicies {
 final ReservationStore db;final OperatorIdentity operators;
 public HealthPolicies(ReservationStore db,OperatorIdentity operators){this.db=db;this.operators=operators;}
 LocalDate today(){return db.now().atZone(ReservationInput.ZONE).toLocalDate();}
 private static final String MODEL="SELECT md.id,md.series_id,s.brand_id FROM model md JOIN series s ON s.id=md.series_id JOIN brand b ON b.id=s.brand_id WHERE md.id=? AND md.status=1 AND md.is_deleted=0 AND s.status=1 AND s.is_deleted=0 AND b.status=1 AND b.is_deleted=0";
 void model(long model,boolean lock){com.autocare.platform.service.ServiceCatalog.validateId(model);var before=db.one(MODEL,model);if(lock){db.one("SELECT id FROM brand WHERE id=? FOR UPDATE",before.get("brand_id"));db.one("SELECT id FROM series WHERE id=? FOR UPDATE",before.get("series_id"));db.one("SELECT id FROM model WHERE id=? FOR UPDATE",model);if(!before.equals(db.one(MODEL+" FOR UPDATE",model)))throw conflict();}}
 JsonNode json(Object value){try{return db.mapper.readTree(value.toString());}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"来源规则暂不可用");}}
 Map<String,Object> state(long model,String metric,boolean lock){var rows=db.jdbc.queryForList("SELECT version,rule_json FROM model_health_policy WHERE model_id=? AND metric=?"+(lock?" FOR UPDATE":""),model,metric);var out=new LinkedHashMap<String,Object>();out.put("model_id",model);out.put("metric",metric);out.put("version",rows.isEmpty()?0L:ReservationStore.number(rows.get(0),"version"));out.put("rule",rows.isEmpty()||rows.get(0).get("rule_json")==null?null:json(rows.get(0).get("rule_json")));return out;}
 public Map<String,Object> read(OperatorActor actor,long model,String metric){HealthObservationInput.metric(metric);return db.reads.execute(tx->{operators.authorize(actor,OperatorIdentity.Permission.CATALOG,false);model(model,false);return state(model,metric,false);});}
 public JsonNode write(OperatorActor actor,long model,String metric,String key,JsonNode raw){HealthObservationInput.metric(metric);var body=HealthPolicyInput.input(raw.toString(),metric,today());return db.writes.execute(new Actor("operator_account",actor.id()),"PUT","/api/admin/health-policies/"+model+"/"+metric,key,body,()->{
  operators.authorize(actor,OperatorIdentity.Permission.CATALOG,true);var rule=body.get("rule");if(!rule.isNull()&&!rule.get("action_project_id").isNull())db.one("SELECT id FROM standard_project WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",rule.get("action_project_id").longValue());model(model,true);
 },()->{var before=state(model,metric,true);long version=((Number)before.get("version")).longValue();if(version!=body.get("expected_version").longValue()||version>=9007199254740991L)throw conflict();var rule=body.get("rule");if(!rule.isNull())HealthPolicyInput.rule(rule,metric,today(),true);
  db.jdbc.update("INSERT INTO model_health_policy(model_id,metric,rule_json,version,operator_id) VALUES(?,?,?,1,?) ON DUPLICATE KEY UPDATE rule_json=VALUES(rule_json),version=version+1,operator_id=VALUES(operator_id)",model,metric,rule.isNull()?null:db.json(rule),actor.id());var after=state(model,metric,true);return new Change("HEALTH_POLICY_SET","model",model,before,after,after);
 });}
 static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"来源规则或证据版本已变化，请重新读取并核对");}
}
