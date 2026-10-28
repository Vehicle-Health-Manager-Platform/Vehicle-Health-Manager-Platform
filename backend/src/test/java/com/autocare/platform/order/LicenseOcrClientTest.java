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
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP, synthetic bytes/payloads, no real OCR model. */
class LicenseOcrClientTest {
    static final String TOKEN="synthetic-license-client-credential-0000001";
    HttpServer server;ExecutorService pool;final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newCachedThreadPool();server.setExecutor(pool);}
    @AfterEach void close(){server.stop(0);pool.shutdownNow();}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/recognize";}
    @Test void fixed_license_scene_body_and_server_credential_are_sent()throws Exception{
        var seen=new CompletableFuture<Boolean>();
        server.createContext("/recognize",exchange->{try{var input=mapper.readTree(exchange.getRequestBody());seen.complete(input.size()==2&&input.path("scene").asText().equals("license")&&input.path("image_base64").asText().equals("AQID")&&("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")));byte[] body=mapper.writeValueAsBytes(LicenseTestData.answer(mapper));exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        assertEquals("CANDIDATE",LicenseResult.validate(MileageOcrClient.forLicense(endpoint(),TOKEN,true).recognize(new byte[]{1,2,3})).path("status").asText());assertTrue(seen.get(2,TimeUnit.SECONDS));
    }
    @Test void invalid_destination_credentials_or_license_deadline_never_construct_enabled_client(){
        for(String url:List.of("http://example.com/recognize","http://localhost/other","https://user@example.com/recognize","https://example.com/recognize?token=bad","https://example.com/recognize#fragment"))assertThrows(IllegalArgumentException.class,()->MileageOcrClient.forLicense(url,TOKEN,true));
        assertThrows(IllegalArgumentException.class,()->MileageOcrClient.forLicense(endpoint(),TOKEN,false));assertThrows(IllegalArgumentException.class,()->MileageOcrClient.forLicense(endpoint(),"short",true));
        for(Duration timeout:List.of(Duration.ZERO,Duration.ofMillis(10001)))assertThrows(IllegalArgumentException.class,()->MileageOcrClient.forLicense(endpoint(),TOKEN,true,timeout));
    }
    @Test void response_size_total_body_deadline_and_safe_errors_are_enforced(){
        AtomicInteger mode=new AtomicInteger();
        server.createContext("/recognize",exchange->{try{exchange.getResponseHeaders().set("Content-Type","application/json");if(mode.get()==0){exchange.sendResponseHeaders(200,70000);exchange.getResponseBody().write(new byte[70000]);}else{exchange.sendResponseHeaders(200,100);Thread.sleep(3000);}}catch(Exception ignored){}finally{exchange.close();}});server.start();
        assertThrows(ResponseStatusException.class,()->MileageOcrClient.forLicense(endpoint(),TOKEN,true).recognize(new byte[]{1}));
        mode.set(1);long start=System.nanoTime();var error=assertThrows(ResponseStatusException.class,()->MileageOcrClient.forLicense(endpoint(),TOKEN,true,Duration.ofMillis(200)).recognize(new byte[]{1}));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);assertNull(error.getCause());assertFalse(error.toString().contains(endpoint()));
    }
    @Test void duplicate_trailing_compressed_or_multiple_content_type_responses_are_not_success(){
        AtomicInteger mode=new AtomicInteger();
        server.createContext("/recognize",exchange->{try{byte[] body=(mode.get()==0?"{\"x\":1,\"x\":2}":mode.get()==1?"{}{}":"{}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");if(mode.get()==2)exchange.getResponseHeaders().set("Content-Encoding","gzip");if(mode.get()==3)exchange.getResponseHeaders().add("Content-Type","text/plain");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        for(int i=0;i<4;i++){mode.set(i);assertThrows(ResponseStatusException.class,()->MileageOcrClient.forLicense(endpoint(),TOKEN,true).recognize(new byte[]{1}));}
    }
    @Test void redirect_or_error_never_follows_or_exposes_supplier_detail(){
        var redirected=new AtomicInteger();server.createContext("/other",exchange->{redirected.incrementAndGet();exchange.close();});
        server.createContext("/recognize",exchange->{try{byte[] body="private-certificate-diagnostic".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Location","/other");exchange.sendResponseHeaders(302,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        var error=assertThrows(ResponseStatusException.class,()->MileageOcrClient.forLicense(endpoint(),TOKEN,true).recognize(new byte[]{1}));assertEquals(0,redirected.get());assertFalse(error.toString().contains("private"));
    }
    @Test void empty_oversized_and_preinterrupted_inputs_do_not_become_candidates(){
        var client=MileageOcrClient.forLicense(endpoint(),TOKEN,true);for(byte[] bytes:new byte[][]{null,new byte[0],new byte[4194305]})assertThrows(ResponseStatusException.class,()->client.recognize(bytes));
        Thread.currentThread().interrupt();try{assertThrows(ResponseStatusException.class,()->client.recognize(new byte[]{1}));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
    }
}
