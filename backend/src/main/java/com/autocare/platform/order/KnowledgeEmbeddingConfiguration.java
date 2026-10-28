package com.autocare.platform.order;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class KnowledgeEmbeddingConfiguration {
 static boolean flag(String value){if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("Invalid knowledge embedding flag");return value.equals("true");}
 static EmbeddingProfile profile(String raw){try{
  if(raw==null||raw.length()>4096)throw new IllegalArgumentException();
  var mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  var p=mapper.readTree(raw);
  if(!p.isObject()||p.size()!=8||!p.path("model").asText().equals("BAAI/bge-small-zh-v1.5")||!p.path("dimension").isInt()||p.path("dimension").intValue()!=512||!p.path("normalization").asText().equals("L2_V1")||!p.path("chunk_policy").asText().equals(EmbeddingProfile.CHUNK_POLICY)||!p.path("encoder_policy").asText().equals("BGE_CLS_FP32_TOKENIZER512_V1")||!p.path("query_prefix").asText().equals("为这个句子生成表示以用于检索相关文章："))throw new IllegalArgumentException();
  for(String key:List.of("model","revision","normalization","chunk_policy","encoder_policy","query_prefix","runtime_sha256"))if(!p.path(key).isTextual())throw new IllegalArgumentException();
  return new EmbeddingProfile(p.path("model").textValue(),p.path("revision").textValue(),512,p.path("query_prefix").textValue(),p.path("encoder_policy").textValue(),p.path("runtime_sha256").textValue());
 }catch(Exception error){throw new IllegalArgumentException("Invalid knowledge embedding profile");}}
 @Bean ThreadPoolTaskScheduler knowledgeEmbeddingScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("knowledge-index-");scheduler.setRemoveOnCancelPolicy(true);return scheduler;}
 @Bean ThreadPoolTaskScheduler taskScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("business-schedule-");scheduler.setRemoveOnCancelPolicy(true);return scheduler;}
 @Bean(destroyMethod="close") KnowledgeEmbeddingRuntime knowledgeEmbeddingRuntime(ReservationStore db,LicensedKnowledge knowledge,Environment env){
  boolean enabled=flag(env.getProperty("KNOWLEDGE_EMBEDDING_ENABLED","false")),cleanup=flag(env.getProperty("KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED","false"));
  EmbeddingProfile p=null;EmbeddingProvider provider=null;
  String delay=env.getProperty("KNOWLEDGE_EMBEDDING_DELAY_MS","5000");
  if(!delay.matches("[0-9]{4,6}")||Long.parseLong(delay)<1000||Long.parseLong(delay)>300000)throw new IllegalArgumentException("Invalid embedding schedule delay");
  if(enabled){if(!flag(env.getProperty("KNOWLEDGE_ENABLED","false")))throw new IllegalArgumentException("Knowledge must be enabled before embedding");
   p=profile(env.getProperty("KNOWLEDGE_EMBEDDING_PROFILE_JSON",""));
   provider=new EmbeddingHttpClient(env.getProperty("KNOWLEDGE_EMBEDDING_URL",""),env.getProperty("KNOWLEDGE_EMBEDDING_TOKEN",""),flag(env.getProperty("KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP","false")),p);
  }
  var runtime=new KnowledgeEmbeddingRuntime(db,knowledge,p,provider,enabled,cleanup);knowledge.embeddingRuntime(runtime);return runtime;
 }
 @Bean EmbeddingSchedule embeddingSchedule(KnowledgeEmbeddingRuntime runtime){return new EmbeddingSchedule(runtime);}
 static final class EmbeddingSchedule {
  private final KnowledgeEmbeddingRuntime runtime;EmbeddingSchedule(KnowledgeEmbeddingRuntime runtime){this.runtime=runtime;}
  @Scheduled(fixedDelayString="${KNOWLEDGE_EMBEDDING_DELAY_MS:5000}",initialDelayString="${KNOWLEDGE_EMBEDDING_DELAY_MS:5000}",scheduler="knowledgeEmbeddingScheduler")
  public void tick(){runtime.tick();}
 }
}
