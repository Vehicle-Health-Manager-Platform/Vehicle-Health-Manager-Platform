package com.autocare.platform.order;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class KnowledgeEmbeddingConfigurationTest {
 static String valid(){return new EmbeddingProfile("BAAI/bge-small-zh-v1.5","7999e1d3359715c523056ef9478215996d62a620",512,"为这个句子生成表示以用于检索相关文章：","BGE_CLS_FP32_TOKENIZER512_V1","a".repeat(64)).json();}
 MockEnvironment ready(){return new MockEnvironment().withProperty("KNOWLEDGE_ENABLED","true").withProperty("KNOWLEDGE_EMBEDDING_ENABLED","true").withProperty("KNOWLEDGE_EMBEDDING_PROFILE_JSON",valid()).withProperty("KNOWLEDGE_EMBEDDING_URL","http://embedding:8093/embed").withProperty("KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP","true").withProperty("KNOWLEDGE_EMBEDDING_TOKEN","synthetic-private-token-32-characters");}
 @Test void profileAcceptsOnlyCompleteFixedProcessingIdentity(){assertEquals(512,KnowledgeEmbeddingConfiguration.profile(valid()).dimension());for(String raw:List.of("", "null","{}",valid()+"{}",valid().replace("\"dimension\":512","\"dimension\":512.0"),valid().replace("\"dimension\":512","\"dimension\":512,\"dimension\":512"),valid().replace("L2_V1","NONE"),valid().replace("BGE_CLS_FP32_TOKENIZER512_V1","MEAN"),valid().replace("runtime_sha256","unknown")))assertThrows(IllegalArgumentException.class,()->KnowledgeEmbeddingConfiguration.profile(raw));}
 @Test void disabledFactoryDoesNotNeedDatabaseCredentialsOrModel(){var knowledge=mock(LicensedKnowledge.class);try(var r=new KnowledgeEmbeddingConfiguration().knowledgeEmbeddingRuntime(null,knowledge,new MockEnvironment())){assertFalse(r.configured());r.tick();assertFalse(r.cycleFailed());}verify(knowledge).embeddingRuntime(any());}
 @Test void configuredFactoryValidatesAllEnablePreconditions(){for(var pair:List.of(List.of("KNOWLEDGE_EMBEDDING_ENABLED","yes"),List.of("KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED","yes"),List.of("KNOWLEDGE_ENABLED","false"),List.of("KNOWLEDGE_EMBEDDING_PROFILE_JSON","{}"),List.of("KNOWLEDGE_EMBEDDING_URL","http://external.invalid/embed"),List.of("KNOWLEDGE_EMBEDDING_TOKEN","short"),List.of("KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP","false"),List.of("KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP","yes"),List.of("KNOWLEDGE_EMBEDDING_DELAY_MS","1"),List.of("KNOWLEDGE_EMBEDDING_DELAY_MS","300001"),List.of("KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED","true"))){var env=ready().withProperty(pair.get(0),pair.get(1));assertThrows(IllegalArgumentException.class,()->new KnowledgeEmbeddingConfiguration().knowledgeEmbeddingRuntime(null,mock(LicensedKnowledge.class),env));}}
 @Test void standaloneCleanupHasNoModelOrProvider(){try(var r=new KnowledgeEmbeddingConfiguration().knowledgeEmbeddingRuntime(null,mock(LicensedKnowledge.class),new MockEnvironment().withProperty("KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED","true"))){assertTrue(r.configured());}}
 @Test void schedulersAreDistinctAndIndexHasOneThread()throws Exception{var config=new KnowledgeEmbeddingConfiguration();var index=config.knowledgeEmbeddingScheduler();var business=config.taskScheduler();assertNotSame(index,business);assertEquals(1,index.getPoolSize());var method=KnowledgeEmbeddingConfiguration.EmbeddingSchedule.class.getMethod("tick");assertEquals("knowledgeEmbeddingScheduler",method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class).scheduler());}
 @Test void actualScheduledCallbackUsesDedicatedThreadAndLeavesBusinessSchedulingLive()throws Exception{
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var runtime=mock(KnowledgeEmbeddingRuntime.class);
  doAnswer(i->{assertTrue(Thread.currentThread().getName().startsWith("knowledge-index-"));entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return null;}).when(runtime).tick();
  try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
   context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("KNOWLEDGE_EMBEDDING_DELAY_MS","1000")));
   context.registerBean(KnowledgeEmbeddingRuntime.class,()->runtime);context.register(SchedulingHarness.class);context.refresh();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
   var count=context.getBean(java.util.concurrent.atomic.AtomicInteger.class);int before=count.get();assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->{while(count.get()==before)Thread.sleep(20);});release.countDown();
  }finally{release.countDown();}
 }
 @org.springframework.boot.test.context.TestConfiguration @org.springframework.scheduling.annotation.EnableScheduling
 static class SchedulingHarness {
  @org.springframework.context.annotation.Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler knowledgeEmbeddingScheduler(){return new KnowledgeEmbeddingConfiguration().knowledgeEmbeddingScheduler();}
  @org.springframework.context.annotation.Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler(){return new KnowledgeEmbeddingConfiguration().taskScheduler();}
  @org.springframework.context.annotation.Bean KnowledgeEmbeddingConfiguration.EmbeddingSchedule schedule(KnowledgeEmbeddingRuntime r){return new KnowledgeEmbeddingConfiguration.EmbeddingSchedule(r);}
  @org.springframework.context.annotation.Bean java.util.concurrent.atomic.AtomicInteger counter(){return new java.util.concurrent.atomic.AtomicInteger();}
  @org.springframework.context.annotation.Bean BusinessTicker ticker(java.util.concurrent.atomic.AtomicInteger count){return new BusinessTicker(count);}
 }
 static class BusinessTicker {
  private final java.util.concurrent.atomic.AtomicInteger count;BusinessTicker(java.util.concurrent.atomic.AtomicInteger count){this.count=count;}
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=50) public void tick(){assertTrue(Thread.currentThread().getName().startsWith("business-schedule-"));count.incrementAndGet();}
 }
}
