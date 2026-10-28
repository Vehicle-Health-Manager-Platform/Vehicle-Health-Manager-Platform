package com.autocare.platform.order;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual offline model + MySQL; identities, permissions and texts are synthetic fixtures. */
@EnabledIfEnvironmentVariable(named="REAL_EMBEDDING_ACCEPTANCE",matches="true")
class RealEmbeddingIndexJourneyTest extends JdbcLicensedKnowledgeTest {
 EmbeddingProfile profile;EmbeddingHttpClient provider;KnowledgeVectorIndex index;
 @BeforeEach void setupActual()throws Exception{
  var p=json.readTree(System.getenv("REAL_EMBEDDING_PROFILE"));
  profile=new EmbeddingProfile(p.path("model").asText(),p.path("revision").asText(),p.path("dimension").asInt(),p.path("query_prefix").asText(),p.path("encoder_policy").asText(),p.path("runtime_sha256").asText());
  provider=new EmbeddingHttpClient(System.getenv("REAL_EMBEDDING_URL"),System.getenv("REAL_EMBEDDING_TOKEN"),true,profile);
  index=new KnowledgeVectorIndex(db,knowledge,profile,provider,true);
 }
 @AfterEach void closeActual(){if(index!=null)index.close();}
 long publish(String text){long id=createKnowledge(config().put("embedding_allowed",true).put("embedding_policy_version",KnowledgeInput.EMBEDDING_POLICY).put("body",text));publishKnowledge(id);return id;}
 @Test void actualVectorsPersistAndRankWithCurrentOwnerLicenseAndModel(){
  long related=publish("合成测试：发现汽车轮胎胎压不足时，应检查漏气并按车辆标准补充气压。"),other=publish("合成测试：今天的食谱介绍西红柿炒鸡蛋的烹饪方法。");
  long job=index.enqueue(related);index.enqueue(other);assertTrue(index.workOne());assertTrue(index.workOne());assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM knowledge_embedding_job WHERE id=?",String.class,job));
  assertEquals(512,jdbc.queryForObject("SELECT dimension FROM knowledge_embedding_chunk WHERE job_id=?",Integer.class,job));
  var hits=index.rank(owner,1,"汽车轮胎胎压不足应该如何处理？",5);assertEquals(2,hits.size());assertEquals(related,hits.get(0).documentId());assertTrue(hits.get(0).score()>hits.get(1).score());assertEquals(404,code(()->index.rank(owner,2,"胎压",5)));
  assertEquals("KEYWORD",knowledge.search(owner,1,"胎压",null).get("method"));
  knowledge.status(manager,related,key(),state(2,"STOPPED"));assertTrue(index.rank(owner,1,"胎压",5).stream().noneMatch(h->h.documentId()==related));index.cleanup();assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_chunk WHERE job_id=?",Integer.class,job));
 }
 @Test void actualProviderSourceRevocationDuringCallWithholdsEveryVector(){
  long document=publish("合成测试轮胎气压检查资料。");long job=index.enqueue(document);
  try(var revoking=new KnowledgeVectorIndex(db,knowledge,profile,(p,t)->{var out=provider.embed(p,t);knowledge.status(manager,document,key(),state(2,"STOPPED"));return out;},true)){
   assertTrue(revoking.workOne());assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM knowledge_embedding_job WHERE id=?",String.class,job));assertEquals(0,count("knowledge_embedding_chunk"));
  }
 }
 @Test void actualTokenizerFailurePropagatesThroughJavaAndExecutor(){
  try(var executor=new EmbeddingExecutor(profile,provider,java.time.Duration.ofSeconds(30))){assertEquals("LIMIT_EXCEEDED",assertThrows(EmbeddingExecutor.Failed.class,()->executor.call(List.of("车".repeat(511)))).code);}
  assertEquals(1,provider.embed(profile,List.of("合成恢复检查")).vectors().size());assertEquals(0,count("knowledge_embedding_chunk"));
 }
 @Test void actualRuntimeDiscoversGeneratesReportsAndPurgesStoppedSource(){
  long id=publish("合成生命周期检查：轮胎气压不足时到店检查。");int audits=count("audit_log");
  try(var runtime=new KnowledgeEmbeddingRuntime(db,knowledge,profile,provider,true,false)){
   knowledge.embeddingRuntime(runtime);runtime.tick();assertFalse(runtime.cycleFailed());assertEquals(1,count("knowledge_embedding_job"));assertEquals(1,count("knowledge_embedding_chunk"));
   var status=runtime.status(jdbc.queryForMap("SELECT * FROM knowledge_document WHERE id=?",id));assertEquals("SUCCEEDED",status.get("state"));assertEquals(profile.fingerprint(),status.get("profile_hash"));assertEquals(audits,count("audit_log"));
   runtime.tick();assertEquals(1,count("knowledge_embedding_job"));assertEquals("KEYWORD",knowledge.search(owner,1,"轮胎",null).get("method"));
   knowledge.status(manager,id,key(),state(2,"STOPPED"));runtime.tick();assertEquals(0,count("knowledge_embedding_chunk"));assertEquals("NOT_ALLOWED",runtime.status(jdbc.queryForMap("SELECT * FROM knowledge_document WHERE id=?",id)).get("state"));
  }
 }
 @Test void actualPublicVectorUsesCurrentExactChunkAndOwnerScope(){
  long id=publish("合成背景资料。".repeat(50)+"轮胎胎压不足时应到店检查漏气并按标准补充气压。");index.enqueue(id);assertTrue(index.workOne());
  try(var runtime=new KnowledgeEmbeddingRuntime(db,knowledge,profile,provider,true,false,true)){
   knowledge.embeddingRuntime(runtime);var out=json.valueToTree(knowledge.search(owner,1,"轮胎胎压不足如何处理",null));assertEquals("VECTOR",out.path("method").asText());var item=out.path("items").get(0);assertTrue(item.has("chunk"));var detail=json.valueToTree(knowledge.detail(owner,1,item.path("reference").asText(),item.path("chunk").asInt()));assertEquals(item.path("excerpt"),detail.path("document").path("body"));assertEquals(404,code(()->knowledge.search(owner,2,"胎压",null)));
  }
 }
 @Test void actualVectorAiSourcesAreRecheckedAfterMockUpstream(){
  long id=publish("轮胎气压合成资料，仅用于测试。");index.enqueue(id);index.workOne();
  try(var runtime=new KnowledgeEmbeddingRuntime(db,knowledge,profile,provider,true,false,true)){
   knowledge.embeddingRuntime(runtime);org.mockito.Mockito.when(ai.complete(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(new com.autocare.platform.ai.DeepSeekClient.Completion("合成AI回答","synthetic-ai",1,1));
   var q=json.createObjectNode().put("vehicle_id",1).put("question","轮胎气压检查");var out=json.valueToTree(knowledge.answer(owner,q));assertEquals("VECTOR",out.path("method").asText());assertTrue(out.path("provided_sources").get(0).has("chunk"));
   org.mockito.Mockito.when(ai.complete(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i->{knowledge.status(manager,id,key(),state(2,"STOPPED"));return new com.autocare.platform.ai.DeepSeekClient.Completion("must be withheld","synthetic-ai",1,1);});assertEquals(404,code(()->knowledge.answer(owner,q)));
  }
 }
}
