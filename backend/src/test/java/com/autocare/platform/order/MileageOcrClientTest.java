package com.autocare.platform.order;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class MileageOcrClientTest {
    static final String TOKEN="synthetic-ocr-client-credential-0000001";
    HttpServer server;ExecutorService pool;
    @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newCachedThreadPool();server.setExecutor(pool);}
    @AfterEach void close(){server.stop(0);pool.shutdownNow();}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/recognize";}
    @Test void rejects_untrusted_destination_and_bad_credentials(){
        for(String url:new String[]{"http://example.com/recognize","http://localhost/other","https://user@example.com/recognize","https://example.com/recognize?token=bad","https://example.com/recognize#fragment"})assertThrows(IllegalArgumentException.class,()->new MileageOcrClient(url,TOKEN,true));
        assertThrows(IllegalArgumentException.class,()->new MileageOcrClient(endpoint(),TOKEN,false));assertThrows(IllegalArgumentException.class,()->new MileageOcrClient(endpoint(),"short",true));
    }
    @Test void sends_server_credential_and_bytes_without_urls()throws Exception{
        var seen=new CompletableFuture<Boolean>();
        server.createContext("/recognize",exchange->{try{var input=new ObjectMapper().readTree(exchange.getRequestBody());seen.complete(("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))&&input.size()==2&&input.path("scene").asText().equals("mileage")&&input.path("image_base64").asText().equals("AQID"));byte[] b="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);}finally{exchange.close();}});server.start();
        assertTrue(new MileageOcrClient(endpoint(),TOKEN,true).recognize(new byte[]{1,2,3}).path("ok").asBoolean());assertTrue(seen.get(2,TimeUnit.SECONDS));
    }
    @Test void oversized_response_is_cancelled(){server.createContext("/recognize",exchange->{try{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,70000);exchange.getResponseBody().write(new byte[70000]);}catch(Exception ignored){}finally{exchange.close();}});server.start();assertThrows(ResponseStatusException.class,()->new MileageOcrClient(endpoint(),TOKEN,true).recognize(new byte[]{1}));}
    @Test void stalled_response_body_has_hard_deadline(){server.createContext("/recognize",exchange->{try{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,100);Thread.sleep(3000);}catch(Exception ignored){}finally{exchange.close();}});server.start();long start=System.nanoTime();assertThrows(ResponseStatusException.class,()->new MileageOcrClient(endpoint(),TOKEN,true,Duration.ofMillis(200)).recognize(new byte[]{1}));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);}
    @Test void redirects_errors_and_wrong_content_type_are_refused(){
        var mode=new java.util.concurrent.atomic.AtomicInteger(301);var redirected=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/other",exchange->{redirected.incrementAndGet();exchange.close();});
        server.createContext("/recognize",exchange->{try{byte[] b="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",mode.get()==200?"text/plain":"application/json");exchange.getResponseHeaders().set("Location","/other");exchange.sendResponseHeaders(mode.get(),b.length);exchange.getResponseBody().write(b);}finally{exchange.close();}});server.start();
        for(int code:new int[]{301,503,200}){mode.set(code);assertThrows(ResponseStatusException.class,()->new MileageOcrClient(endpoint(),TOKEN,true).recognize(new byte[]{1}));}
        assertEquals(0,redirected.get());
    }
    @Test void duplicate_json_is_not_success(){
        server.createContext("/recognize",exchange->{try{byte[] b="{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);}finally{exchange.close();}});server.start();assertThrows(ResponseStatusException.class,()->new MileageOcrClient(endpoint(),TOKEN,true).recognize(new byte[]{1}));
    }
}
