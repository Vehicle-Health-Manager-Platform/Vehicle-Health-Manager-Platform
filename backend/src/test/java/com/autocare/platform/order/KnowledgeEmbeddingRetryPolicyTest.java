package com.autocare.platform.order;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeEmbeddingRetryPolicyTest {
 @Test void disabledByDefaultAndEnabledPolicyIsStrictlyBounded(){
  assertFalse(KnowledgeEmbeddingConfiguration.retryPolicy(new MockEnvironment()).enabled());
  var env=new MockEnvironment().withProperty("KNOWLEDGE_EMBEDDING_RETRY_ENABLED","true");
  var policy=KnowledgeEmbeddingConfiguration.retryPolicy(env);assertEquals(2,policy.limit());assertEquals(Duration.ofSeconds(60),policy.delay());
  for(String limit:new String[]{"0","4","-1","2.0"," 2"})assertThrows(IllegalArgumentException.class,()->KnowledgeEmbeddingConfiguration.retryPolicy(new MockEnvironment().withProperty("KNOWLEDGE_EMBEDDING_RETRY_ENABLED","true").withProperty("KNOWLEDGE_EMBEDDING_RETRY_LIMIT",limit)));
  for(String delay:new String[]{"0","59","3601","1e3","-60","60.5"})assertThrows(IllegalArgumentException.class,()->KnowledgeEmbeddingConfiguration.retryPolicy(new MockEnvironment().withProperty("KNOWLEDGE_EMBEDDING_RETRY_ENABLED","true").withProperty("KNOWLEDGE_EMBEDDING_RETRY_DELAY_SECONDS",delay)));
 }
 @Test void retriesCannotRunWithoutIndexingOrInCleanupOnlyMode(){
  assertThrows(IllegalArgumentException.class,()->new KnowledgeEmbeddingRuntime(null,null,null,null,false,false,false,new KnowledgeEmbeddingRetries.Policy(true,2,Duration.ofSeconds(60))));
  assertThrows(IllegalArgumentException.class,()->new KnowledgeEmbeddingRuntime(null,null,null,null,false,true,false,new KnowledgeEmbeddingRetries.Policy(true,2,Duration.ofSeconds(60))));
  assertThrows(IllegalArgumentException.class,()->KnowledgeEmbeddingConfiguration.retryPolicy(new MockEnvironment().withProperty("KNOWLEDGE_EMBEDDING_RETRY_ENABLED","yes")));
 }
}
