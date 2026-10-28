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

/** Loopback transport and explicit synthetic transcript; never model accuracy evidence. */
class AsrHttpClientTest {
    static final String TOKEN="synthetic-asr-client-credential-0000000001";
    static final String ANSWER="{\"version\":1,\"task\":\"ASR\",\"source\":\"FASTER_WHISPER\",\"status\":\"TRANSCRIBED\",\"language\":\"zh\",\"duration_ms\":1008,\"segments\":[{\"start_ms\":0,\"end_ms\":900,\"text\":\"合成声音文字\",\"confidence\":null}],\"requires_confirmation\":true,\"audio_recognition_performed\":true}";
    HttpServer server; ExecutorService pool; final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newCachedThreadPool();server.setExecutor(pool);}
    @AfterEach void close(){server.stop(0);pool.shutdownNow();}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/transcribe";}
    @Test void binds_binary_input_duration_and_server_credential_without_query()throws Exception{
        var seen=new CompletableFuture<Boolean>();
        server.createContext("/transcribe",exchange->{try{var body=mapper.readTree(exchange.getRequestBody());seen.complete(body.size()==4&&body.path("version").asInt()==1&&body.path("task").asText().equals("ASR")&&body.path("duration_ms").asInt()==1008&&body.path("audio_base64").asText().equals("AQID")&&exchange.getRequestURI().getRawQuery()==null&&("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")));byte[] out=ANSWER.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,out.length);exchange.getResponseBody().write(out);}finally{exchange.close();}});server.start();
        var result=new AsrHttpClient(endpoint(),TOKEN,true).transcribe(new byte[]{1,2,3},1008);assertEquals("合成声音文字",result.path("segments").get(0).path("text").asText());assertTrue(seen.get(2,TimeUnit.SECONDS));
    }
    @Test void destination_credentials_and_deadlines_fail_before_network(){
        for(String url:List.of("http://example.com/transcribe","https://user@example.com/transcribe","https://example.com/transcribe?x=1","https://example.com/transcribe#x","https://example.com/other"))assertThrows(IllegalArgumentException.class,()->new AsrHttpClient(url,TOKEN,true));
        assertThrows(IllegalArgumentException.class,()->new AsrHttpClient(endpoint(),TOKEN,false));
        for(String token:List.of("short","x".repeat(513),TOKEN+"\n"))assertThrows(IllegalArgumentException.class,()->new AsrHttpClient(endpoint(),token,true));
        for(Duration deadline:List.of(Duration.ZERO,Duration.ofMillis(90001)))assertThrows(IllegalArgumentException.class,()->new AsrHttpClient(endpoint(),TOKEN,true,deadline));
    }
    @Test void total_body_deadline_and_size_limit_never_leak_supplier_details(){
        AtomicInteger mode=new AtomicInteger();server.createContext("/transcribe",exchange->{try{exchange.getResponseHeaders().set("Content-Type","application/json");if(mode.get()==0){exchange.sendResponseHeaders(200,70000);exchange.getResponseBody().write(new byte[70000]);}else{exchange.sendResponseHeaders(200,100);Thread.sleep(3000);}}catch(Exception ignored){}finally{exchange.close();}});server.start();
        assertThrows(AsrProvider.Unavailable.class,()->new AsrHttpClient(endpoint(),TOKEN,true).transcribe(new byte[]{1},1008));mode.set(1);long start=System.nanoTime();var error=assertThrows(AsrProvider.Unavailable.class,()->new AsrHttpClient(endpoint(),TOKEN,true,Duration.ofMillis(150)).transcribe(new byte[]{1},1008));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);assertNull(error.getCause());assertFalse(error.toString().contains(endpoint()));
    }
    @Test void malformed_json_framing_and_wrong_duration_are_not_transcripts(){
        AtomicInteger mode=new AtomicInteger();server.createContext("/transcribe",exchange->{try{String out=switch(mode.get()){case 0->"{\"x\":1,\"x\":2}";case 1->ANSWER+"{}";case 2->ANSWER.replace("1008","2000");default->ANSWER;};byte[] bytes=out.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");if(mode.get()==3)exchange.getResponseHeaders().set("Content-Encoding","gzip");if(mode.get()==4)exchange.getResponseHeaders().add("Content-Type","text/plain");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        for(int i=0;i<5;i++){mode.set(i);assertThrows(AsrProvider.Unavailable.class,()->new AsrHttpClient(endpoint(),TOKEN,true).transcribe(new byte[]{1},1008));}
    }
    @Test void redirects_errors_and_invalid_payloads_have_zero_followups(){
        var calls=new AtomicInteger();server.createContext("/other",exchange->{calls.incrementAndGet();exchange.close();});server.createContext("/transcribe",exchange->{try{byte[] bytes="private-audio-diagnostic".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Location","/other");exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(302,bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}});server.start();
        var client=new AsrHttpClient(endpoint(),TOKEN,true);var error=assertThrows(AsrProvider.Unavailable.class,()->client.transcribe(new byte[]{1},1008));assertEquals(0,calls.get());assertFalse(error.toString().contains("private"));
        for(byte[] bytes:new byte[][]{null,new byte[0],new byte[3145729]})assertThrows(AsrProvider.Unavailable.class,()->client.transcribe(bytes,1008));
        for(int duration:List.of(0,999,60001))assertThrows(AsrProvider.Unavailable.class,()->client.transcribe(new byte[]{1},duration));
    }
    @Test void interrupt_is_preserved_without_payload_or_cause(){Thread.currentThread().interrupt();try{var e=assertThrows(AsrProvider.Unavailable.class,()->new AsrHttpClient(endpoint(),TOKEN,true).transcribe(new byte[]{1},1008));assertTrue(Thread.currentThread().isInterrupted());assertNull(e.getCause());}finally{Thread.interrupted();}}
}
