package com.autocare.platform.order;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Loopback transport and explicit synthetic document; never model accuracy evidence. */
class DocumentHttpClientTest {
    static final String TOKEN="synthetic-asr-client-credential-0000000001";
    static final String ANSWER=JdbcDocumentJobsTest.answer(new ObjectMapper(),1).toString();
    HttpServer server; ExecutorService pool; final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newCachedThreadPool();server.setExecutor(pool);}
    @AfterEach void close(){server.stop(0);pool.shutdownNow();}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/extract";}
    @Test void binds_binary_input_duration_and_server_credential_without_query()throws Exception{
        var seen=new CompletableFuture<Boolean>();
        server.createContext("/extract",exchange->{try{var body=mapper.readTree(exchange.getRequestBody());seen.complete(body.size()==3&&body.path("version").asInt()==1&&body.path("content_type").asText().equals("application/pdf")&&Base64.getDecoder().decode(body.path("document_base64").asText()).length==6480&&exchange.getRequestURI().getRawQuery()==null&&("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")));byte[] out=ANSWER.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);}finally{exchange.close();}});server.start();
        var result=new DocumentHttpClient(endpoint(),TOKEN,true).extract(JdbcDocumentJobsTest.audio(),"application/pdf",1);assertEquals("合成转写内容",result.path("pages").get(0).path("text").asText());assertTrue(seen.get(2,TimeUnit.SECONDS));
    }
    @Test void destination_credentials_and_deadlines_fail_before_network(){
        for(String url:List.of("http://example.com/extract","https://user@example.com/extract","https://example.com/extract?x=1","https://example.com/extract#x","https://example.com/other"))assertThrows(IllegalArgumentException.class,()->new DocumentHttpClient(url,TOKEN,true));
        assertThrows(IllegalArgumentException.class,()->new DocumentHttpClient(endpoint(),TOKEN,false));
        for(String token:List.of("short","x".repeat(513),TOKEN+"\n"))assertThrows(IllegalArgumentException.class,()->new DocumentHttpClient(endpoint(),token,true));
        for(Duration deadline:List.of(Duration.ZERO,Duration.ofMillis(75001)))assertThrows(IllegalArgumentException.class,()->new DocumentHttpClient(endpoint(),TOKEN,true,deadline));
    }
    @Test void total_body_deadline_and_size_limit_never_leak_supplier_details(){
        AtomicInteger mode=new AtomicInteger();server.createContext("/extract",exchange->{try{exchange.getResponseHeaders().set("Content-Type","application/json");if(mode.get()==0){exchange.sendResponseHeaders(200,70000);exchange.getResponseBody().write(new byte[70000]);}else{exchange.sendResponseHeaders(200,100);Thread.sleep(3000);}}catch(Exception ignored){}finally{exchange.close();}});server.start();
        assertThrows(DocumentProvider.Failed.class,()->new DocumentHttpClient(endpoint(),TOKEN,true).extract(JdbcDocumentJobsTest.audio(),"application/pdf",1));mode.set(1);long start=System.nanoTime();var error=assertThrows(DocumentProvider.Failed.class,()->new DocumentHttpClient(endpoint(),TOKEN,true,Duration.ofMillis(150)).extract(JdbcDocumentJobsTest.audio(),"application/pdf",1));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);assertNull(error.getCause());assertFalse(error.toString().contains(endpoint()));
    }
    @Test void malformed_json_framing_and_wrong_duration_are_not_transcripts(){
        AtomicInteger mode=new AtomicInteger();server.createContext("/extract",exchange->{try{String out=switch(mode.get()){case 0->"{\"x\":1,\"x\":2}";case 1->ANSWER+"{}";case 2->ANSWER.replace("content_sha256\":\"","content_sha256\":\"0");default->ANSWER;};byte[] bytes=out.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");if(mode.get()==3)exchange.getResponseHeaders().set("Content-Encoding","gzip");if(mode.get()==4)exchange.getResponseHeaders().add("Content-Type","text/plain");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        for(int i=0;i<5;i++){mode.set(i);assertThrows(DocumentProvider.Failed.class,()->new DocumentHttpClient(endpoint(),TOKEN,true).extract(JdbcDocumentJobsTest.audio(),"application/pdf",1));}
    }
    @Test void redirects_errors_and_invalid_payloads_have_zero_followups(){
        var calls=new AtomicInteger();server.createContext("/other",exchange->{calls.incrementAndGet();exchange.close();});server.createContext("/extract",exchange->{try{byte[] bytes="private-audio-diagnostic".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Location","/other");exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(302,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        var client=new DocumentHttpClient(endpoint(),TOKEN,true);var error=assertThrows(DocumentProvider.Failed.class,()->client.extract(JdbcDocumentJobsTest.audio(),"application/pdf",1));assertEquals(0,calls.get());assertFalse(error.toString().contains("private"));
        for(byte[] bytes:new byte[][]{null,new byte[0],new byte[10485761]})assertThrows(DocumentProvider.Failed.class,()->client.extract(bytes,"application/pdf",1));
        assertThrows(DocumentProvider.Failed.class,()->client.extract(new byte[]{1},"image/webp",1));
    }
    @Test void interrupt_is_preserved_without_payload_or_cause(){Thread.currentThread().interrupt();try{var e=assertThrows(DocumentProvider.Failed.class,()->new DocumentHttpClient(endpoint(),TOKEN,true).extract(JdbcDocumentJobsTest.audio(),"application/pdf",1));assertTrue(Thread.currentThread().isInterrupted());assertNull(e.getCause());}finally{Thread.interrupted();}}
}
