package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.AuthTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** Immutable, audited message binding. No private source fields leave this class's caller. */
final class CommunityQuestionSource {
 static JsonNode canonical(JsonNode n){if(n.isObject()){var out=JsonNodeFactory.instance.objectNode();var names=new TreeSet<String>();n.fieldNames().forEachRemaining(names::add);for(String k:names)out.set(k,canonical(n.get(k)));return out;}if(n.isArray()){var out=JsonNodeFactory.instance.arrayNode();n.forEach(v->out.add(canonical(v)));return out;}return n;}
 static String hash(ReservationStore db,Object value){return AuthTokens.sha256(canonical(db.mapper.valueToTree(value)).toString());}
 static String messageHash(ReservationStore db,Map<String,Object> c,long revision,String sender,long senderId,String body,Object proof){var data=new LinkedHashMap<String,Object>();data.put("case_id",c.get("id"));data.put("owner_id",c.get("owner_id"));data.put("vehicle_id",c.get("vehicle_id"));data.put("vehicle",db.parse(c.get("vehicle_snapshot")));data.put("category",c.get("category"));data.put("revision",revision);data.put("sender",sender);data.put("sender_id",senderId);data.put("body",body);data.put("admission",proof);return hash(db,data);}
 static void trustedMessage(ReservationStore db,Map<String,Object> c,Map<String,Object> m){
  Object proof=m.get("admission_snapshot")==null?null:db.parse(m.get("admission_snapshot"));String hash=messageHash(db,c,number(m,"case_revision"),m.get("sender_type").toString(),number(m,"sender_id"),m.get("body").toString(),proof);
  if(!hash.equals(m.get("source_hash")))throw CommunityQuestionPolicies.conflict();
  String kind="OPERATOR".equals(m.get("sender_type"))?"REPLIED":number(m,"case_revision")==1?"CREATED":"MESSAGE";
  var event=db.jdbc.queryForList("SELECT id FROM community_qa_case_event WHERE case_id=? AND case_revision=? AND kind=?",c.get("id"),m.get("case_revision"),kind);if(event.size()!=1)throw CommunityQuestionPolicies.conflict();
  var audit=db.jdbc.queryForList("SELECT * FROM audit_log WHERE resource_type='community_qa_case' AND resource_id=? AND action IN ('COMMUNITY_QUESTION_CREATE','COMMUNITY_QUESTION_MESSAGE','COMMUNITY_QUESTION_REPLIED') AND JSON_UNQUOTE(JSON_EXTRACT(after_state,'$.revision'))=? ORDER BY id DESC LIMIT 1",c.get("id"),m.get("case_revision").toString());
  if(audit.size()!=1)throw CommunityQuestionPolicies.conflict();var a=audit.get(0);String action="CREATED".equals(kind)?"CREATE":kind;
  if(number(a,"is_deleted")!=0||!("COMMUNITY_QUESTION_"+action).equals(a.get("action"))||!("OPERATOR".equals(m.get("sender_type"))?"operator_account":"user").equals(a.get("actor_type"))||number(a,"actor_id")!=number(m,"sender_id")||!hash.equals(db.parse(a.get("after_state")).path("message_hash").asText()))throw CommunityQuestionPolicies.conflict();
 }
}
