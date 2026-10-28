package com.autocare.platform.order;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;
/** Actual Spring scheduler/JWT/HTTP/MySQL/BGE. Identity/license/texts are synthetic. */
@EnabledIfEnvironmentVariable(named="REAL_EMBEDDING_ACCEPTANCE",matches="true")
class RealKnowledgeVectorHttpJourneyTest extends IsolatedCoreJourneysTest {
 static final String PATH="/api/admin/knowledge/documents";
 @DynamicPropertySource static void vectors(DynamicPropertyRegistry p){p.add("KNOWLEDGE_ENABLED",()->"true");p.add("KNOWLEDGE_EMBEDDING_ENABLED",()->"true");p.add("KNOWLEDGE_VECTOR_ENABLED",()->"true");p.add("KNOWLEDGE_EMBEDDING_PROFILE_JSON",()->System.getenv("REAL_EMBEDDING_PROFILE"));p.add("KNOWLEDGE_EMBEDDING_URL",()->System.getenv("REAL_EMBEDDING_URL"));p.add("KNOWLEDGE_EMBEDDING_TOKEN",()->System.getenv("REAL_EMBEDDING_TOKEN"));p.add("KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP",()->"true");p.add("KNOWLEDGE_EMBEDDING_DELAY_MS",()->"1000");}
 Map<String,Object> body(String text,boolean embed){var b=new LinkedHashMap<String,Object>();b.put("title","合成汽车资料");b.put("publisher","合成出版者");b.put("document_version","synthetic-v1");b.put("source_reference","synthetic-source-not-real-manual");b.put("license_reference","synthetic-private-license");b.put("license_confirmed",true);b.put("model_id",1);b.put("valid_from",Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());b.put("valid_to",Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());b.put("search_allowed",true);b.put("excerpt_allowed",true);b.put("ai_allowed",false);b.put("body",text);if(embed){b.put("embedding_allowed",true);b.put("embedding_policy_version","KNOWLEDGE_EMBEDDING_V1");}return b;}
 long publish(String text,boolean embed)throws Exception{jdbc.update("UPDATE operator_account SET can_knowledge=1 WHERE id=1");long id=request("POST",PATH,operator,key(),body(text,embed),200).path("data").path("id").asLong();request("POST",PATH+"/"+id+"/status",operator,key(),Map.of("expected_revision",1,"status","PUBLISHED"),200);return id;}
 void stop(long id)throws Exception{request("POST",PATH+"/"+id+"/status",operator,key(),Map.of("expected_revision",2,"status","STOPPED"),200);}
 @Test void actualAutomaticIndexToPublicVectorAndPositionedReference()throws Exception{
  long v=vehicle("粤V66661");long id=publish("合成背景资料。".repeat(50)+"轮胎胎压不足时，应到店检查漏气并按车辆标准补充气压。",true);
  try{
   long end=System.nanoTime()+Duration.ofSeconds(75).toNanos();String state="";
   while(System.nanoTime()<end){var rows=jdbc.queryForList("SELECT state FROM knowledge_embedding_job WHERE document_id=?",id);if(!rows.isEmpty())state=rows.get(0).get("state").toString();if(state.equals("SUCCEEDED")||state.equals("FAILED"))break;Thread.sleep(200);}
   assertEquals("SUCCEEDED",state,"actual automatic indexing did not succeed");String route="/api/knowledge/search?vehicle_id="+v+"&q=%E8%BD%AE%E8%83%8E%E8%83%8E%E5%8E%8B";
   var out=get(route,owner,200).path("data");assertEquals("VECTOR",out.path("method").asText());assertTrue(out.path("next_cursor").isNull());var item=out.path("items").get(0);assertTrue(item.has("chunk"));assertFalse(out.toString().contains("license_reference"));
   String detail="/api/knowledge/documents/"+item.path("reference").asText()+"?vehicle_id="+v+"&chunk="+item.path("chunk").asInt();assertEquals(item.path("excerpt"),get(detail,owner,200).path("data").path("document").path("body"));
   get(route,null,401);get(route,foreign,404);get(route,merchant,403);get(detail+"&chunk=1",owner,400);
  }finally{stop(id);}
 }
 @Test void unconsentedLegacySourceProducesExplicitKeywordFallback()throws Exception{
  long v=vehicle("粤V66662");long id=publish("冷却液合成参考资料，不能用于真实诊断。",false);
  try{var out=get("/api/knowledge/search?vehicle_id="+v+"&q=%E5%86%B7%E5%8D%B4%E6%B6%B2",owner,200).path("data");assertEquals("KEYWORD",out.path("method").asText());assertEquals("INDEX_NOT_READY",out.path("fallback_reason").asText());assertEquals(1,out.path("items").size());assertFalse(out.path("items").get(0).has("chunk"));}finally{stop(id);}
 }
}
