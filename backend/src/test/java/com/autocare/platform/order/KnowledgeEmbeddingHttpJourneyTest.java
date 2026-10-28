package com.autocare.platform.order;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;
/** Production HTTP/JWT/MySQL and permission metadata; no embedding service/model involved. */
@TestPropertySource(properties="KNOWLEDGE_ENABLED=true")
class KnowledgeEmbeddingHttpJourneyTest extends IsolatedCoreJourneysTest {
 static final String PATH="/api/admin/knowledge/documents";
 Map<String,Object> body(boolean current){var b=new LinkedHashMap<String,Object>();b.put("title","合成冷却液资料");b.put("publisher","合成出版者");b.put("document_version","synthetic-v1");b.put("source_reference","synthetic-source-not-real-manual");b.put("license_reference","synthetic-private-license");b.put("license_confirmed",true);b.put("model_id",1);b.put("valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());b.put("valid_to",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());b.put("search_allowed",true);b.put("excerpt_allowed",true);b.put("ai_allowed",false);b.put("body","合成冷却液资料，不是正式维修建议。");if(current){b.put("embedding_allowed",true);b.put("embedding_policy_version","KNOWLEDGE_EMBEDDING_V1");}return b;}
 @Test void legacyAndCurrentPayloadsRoundtripWithoutImplicitEmbeddingOrIndexing()throws Exception{
  jdbc.update("UPDATE operator_account SET can_knowledge=1 WHERE id=1");
  for(boolean current:List.of(false,true)){var b=body(current);String key=key();var created=request("POST",PATH,operator,key,b,200);assertEquals(created,request("POST",PATH,operator,key,b,200));assertEquals(mapper.valueToTree(b),created.path("data").path("payload"));assertEquals(current,created.path("data").path("payload").has("embedding_allowed"));if(current){var changed=new LinkedHashMap<>(b);changed.put("embedding_allowed",false);request("POST",PATH,operator,key,changed,400);}}
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_job",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_chunk",Integer.class));
 }
 @Test void newPermissionCannotBypassOperatorRoleOrPairValidation()throws Exception{
  jdbc.update("UPDATE operator_account SET can_knowledge=1 WHERE id=1");var b=body(true);request("POST",PATH,null,key(),b,401);request("POST",PATH,owner,key(),b,403);request("POST",PATH,merchant,key(),b,403);request("POST",PATH,operator,null,b,400);
  for(String remove:List.of("embedding_allowed","embedding_policy_version")){var bad=new LinkedHashMap<>(b);bad.remove(remove);request("POST",PATH,operator,key(),bad,400);}
  var bad=new LinkedHashMap<>(b);bad.put("embedding_policy_version","OLD");request("POST",PATH,operator,key(),bad,400);jdbc.update("UPDATE operator_account SET can_knowledge=0 WHERE id=1");request("POST",PATH,operator,key(),b,403);
 }
 @Test void grantedEmbeddingDoesNotChangePublicKeywordResponseOrExposeInternalPermission()throws Exception{
  jdbc.update("UPDATE operator_account SET can_knowledge=1 WHERE id=1");long vehicle=vehicle("粤V55555");long id=request("POST",PATH,operator,key(),body(true),200).path("data").path("id").asLong();request("POST",PATH+"/"+id+"/status",operator,key(),Map.of("expected_revision",1,"status","PUBLISHED"),200);
  var found=get("/api/knowledge/search?vehicle_id="+vehicle+"&q=%E5%86%B7%E5%8D%B4%E6%B6%B2",owner,200).path("data");assertEquals("KEYWORD",found.path("method").asText());assertEquals(1,found.path("items").size());assertFalse(found.toString().contains("embedding_allowed"));assertFalse(found.toString().contains("license_reference"));String reference=found.path("items").get(0).path("reference").asText();request("POST",PATH+"/"+id+"/status",operator,key(),Map.of("expected_revision",2,"status","STOPPED"),200);get("/api/knowledge/documents/"+reference+"?vehicle_id="+vehicle,owner,404);
 }
}
