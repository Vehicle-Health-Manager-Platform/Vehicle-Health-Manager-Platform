package com.autocare.platform.order;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport vectors only. Actual BGE acceptance is separate. */
class EmbeddingHttpClientTest {
 static final String TOKEN="synthetic-embedding-token-32-characters";
 static EmbeddingProfile profile(){return new EmbeddingProfile("BAAI/bge-small-zh-v1.5","7999e1d3359715c523056ef9478215996d62a620",512,"为这个句子生成表示以用于检索相关文章：","BGE_CLS_FP32_TOKENIZER512_V1","a".repeat(64));}
 HttpServer server;ExecutorService pool;String url;String response;int status=200;String type="application/json";long delay;int calls;String input;String auth;
 final ObjectMapper json=new ObjectMapper();
 @BeforeEach void start()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newCachedThreadPool();server.setExecutor(pool);
  server.createContext("/embed",exchange->{try{calls++;input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);auth=exchange.getRequestHeaders().getFirst("Authorization");if(delay>0)Thread.sleep(delay);byte[] raw=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type",type);exchange.sendResponseHeaders(status,raw.length);exchange.getResponseBody().write(raw);}catch(Exception ignored){}finally{exchange.close();}});
  server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/embed";response=valid();
 }
 @AfterEach void stop(){server.stop(0);pool.shutdownNow();}
 String valid()throws Exception{double[] vector=new double[512];vector[0]=1;return json.writeValueAsString(Map.of("version",1,"profile_hash",profile().fingerprint(),"vectors",List.of(vector)));}
 EmbeddingHttpClient client(){return new EmbeddingHttpClient(url,TOKEN,true,profile());}
 String failed(){return assertThrows(EmbeddingProvider.Failed.class,()->client().embed(profile(),List.of("合成测试"))).code();}
 @Test void fixedProfileRequestAndUnitVectorResponse()throws Exception{
  var out=client().embed(profile(),List.of("合成测试"));assertEquals(512,out.vectors().get(0).length);assertEquals(profile().fingerprint(),out.profileHash());var body=json.readTree(input);assertEquals(3,body.size());assertEquals("合成测试",body.path("texts").get(0).asText());assertEquals(profile().fingerprint(),body.path("profile_hash").asText());assertEquals("Bearer "+TOKEN,auth);
 }
 @Test void inputIsBoundedBeforeNetwork(){
  for(var texts:List.of(List.<String>of(),Collections.nCopies(5,"x"),List.of("x".repeat(1025)),List.of("\ud800"),List.of("x\u0000"),List.of(" ")))assertEquals("LIMIT_EXCEEDED",assertThrows(EmbeddingProvider.Failed.class,()->client().embed(profile(),texts)).code());
  assertEquals("INVALID_VECTOR",assertThrows(EmbeddingProvider.Failed.class,()->client().embed(EmbeddingFoundationTest.profile(),List.of("x"))).code());assertEquals(0,calls);
 }
 @Test void configurationRejectsArbitraryHostsRoutesAndMissingRuntime(){
  for(String endpoint:List.of("http://example.com/embed",url+"?url=x",url+"/",url.replace("/embed","/transcribe"),url.replace("http://","http://user@")))assertThrows(IllegalArgumentException.class,()->new EmbeddingHttpClient(endpoint,TOKEN,true,profile()));
  assertThrows(IllegalArgumentException.class,()->new EmbeddingHttpClient(url,TOKEN,false,profile()));assertThrows(IllegalArgumentException.class,()->new EmbeddingHttpClient(url,"short",true,profile()));assertThrows(IllegalArgumentException.class,()->new EmbeddingHttpClient(url,TOKEN,true,new EmbeddingProfile(profile().model(),profile().revision(),512,"")));
 }
 @Test void vectorIdentityDimensionsAndNormalizationAreStrict()throws Exception{
  String good=valid();
  for(String bad:List.of(good.replace(profile().fingerprint(),"b".repeat(64)),good.replace("\"version\":1","\"version\":1.0"),good.replace("[1.0,","[0.0,"),good.replace("[1.0,","[true,"),good.replace("[1.0,","[1000.0,"),"{\"version\":1,\"profile_hash\":\""+profile().fingerprint()+"\",\"vectors\":[[1]]}")){
   response=bad;assertEquals("INVALID_VECTOR",failed());
  }
 }
 @Test void duplicateTrailingCompressedAndOversizedResponsesFailClosed()throws Exception{
  for(String bad:List.of(valid()+"{}",valid().replace("\"version\":1","\"version\":1,\"version\":1")," ".repeat(131073))){response=bad;assertEquals("UNAVAILABLE",failed());}
  response=valid();type="text/plain";assertEquals("UNAVAILABLE",failed());
 }
 @Test void safeProviderErrorsPreserveTokenBudgetOnly(){status=413;response="{\"error\":\"LIMIT_EXCEEDED\"}";assertEquals("LIMIT_EXCEEDED",failed());status=422;response="{\"error\":\"INVALID_RESULT\"}";assertEquals("INVALID_VECTOR",failed());status=503;response="{\"error\":\"private diagnostic\"}";assertEquals("UNAVAILABLE",failed());status=302;assertEquals("UNAVAILABLE",failed());}
 @Test void deadlineCoversWholeBodyAndReturnsSafeError(){delay=1000;var c=new EmbeddingHttpClient(url,TOKEN,true,profile(),Duration.ofMillis(100));long start=System.nanoTime();assertEquals("UNAVAILABLE",assertThrows(EmbeddingProvider.Failed.class,()->c.embed(profile(),List.of("x"))).code());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<800);}
 @Test void executorPreservesActualTokenizerBudgetFailure(){try(var executor=new EmbeddingExecutor(profile(),(p,t)->{throw new EmbeddingProvider.Failed("LIMIT_EXCEEDED");},Duration.ofSeconds(1))){assertEquals("LIMIT_EXCEEDED",assertThrows(EmbeddingExecutor.Failed.class,()->executor.call(List.of("x"))).code);}}
 @Test void legacyProfileJsonStaysCompatibleAndRuntimeChangesIdentity(){
  var old=EmbeddingFoundationTest.profile();assertEquals("{\"chunk_policy\":\"UNICODE_256_OVERLAP32_V1\",\"dimension\":3,\"model\":\"synthetic-fixture\",\"normalization\":\"L2_V1\",\"query_prefix\":\"QUERY: \",\"revision\":\""+"a".repeat(40)+"\"}",old.json());
  var p=profile();assertNotEquals(p.fingerprint(),new EmbeddingProfile(p.model(),p.revision(),p.dimension(),p.queryPrefix(),p.encoderPolicy(),"b".repeat(64)).fingerprint());assertThrows(IllegalArgumentException.class,()->new EmbeddingProfile(p.model(),p.revision(),512,"",p.encoderPolicy(),null));
 }
}
