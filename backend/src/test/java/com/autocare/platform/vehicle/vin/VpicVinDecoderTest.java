package com.autocare.platform.vehicle.vin;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback HTTP; every VIN and provider response is a synthetic fixture. */
class VpicVinDecoderTest {
    static final String VIN=VinProtocolTest.VIN;
    HttpServer server;ExecutorService pool;URI endpoint;
    AtomicInteger calls=new AtomicInteger(),followed=new AtomicInteger(),status=new AtomicInteger(200);
    AtomicReference<String> path=new AtomicReference<>(),mime=new AtomicReference<>("application/json"),body=new AtomicReference<>();
    AtomicLong delay=new AtomicLong();CountDownLatch entered;
    AtomicReference<String> encoding=new AtomicReference<>();AtomicBoolean duplicateMime=new AtomicBoolean();
    @BeforeEach void start()throws Exception{
        body.set("{\"Count\":1,\"Results\":[{\"VIN\":\""+VIN+"\",\"ErrorCode\":\"0\",\"Make\":\"Synthetic\",\"Model\":\"Fixture\",\"ModelYear\":\"2026\"}]}");
        entered=new CountDownLatch(1);pool=Executors.newCachedThreadPool();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
        server.createContext("/api/vehicles/DecodeVinValues/",exchange->{
            calls.incrementAndGet();path.set(exchange.getRequestURI().toASCIIString());entered.countDown();
            try{if(mime.get()!=null)exchange.getResponseHeaders().add("Content-Type",mime.get());if(duplicateMime.get())exchange.getResponseHeaders().add("Content-Type","text/html");if(encoding.get()!=null)exchange.getResponseHeaders().add("Content-Encoding",encoding.get());if(status.get()/100==3)exchange.getResponseHeaders().add("Location","/follow");
                byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status.get(),bytes.length);
                if(delay.get()>0)Thread.sleep(delay.get());exchange.getResponseBody().write(bytes);
            }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(java.io.IOException ignored){}finally{exchange.close();}
        });
        server.createContext("/follow",e->{followed.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});server.start();
        endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/vehicles/DecodeVinValues/");
    }
    @AfterEach void stop(){server.stop(0);pool.shutdownNow();}
    VpicVinDecoder client(){return new VpicVinDecoder(true,endpoint,Duration.ofSeconds(3));}
    @Test void sends_one_normalized_vin_and_explicit_year_then_returns_candidate(){var result=client().decode(VIN.toLowerCase(Locale.ROOT),2026,true);assertEquals(VinDecoder.Status.CANDIDATE,result.status());assertEquals(1,calls.get());assertEquals("/api/vehicles/DecodeVinValues/"+VIN+"?format=json&modelyear=2026",path.get());}
    @Test void no_year_hint_is_not_inferred_or_sent(){client().decode(VIN,null,true);assertFalse(path.get().contains("modelyear"));}
    @Test void disabled_and_missing_consent_send_no_requests(){
        assertThrows(VinDecoder.Unavailable.class,()->new VpicVinDecoder().decode(VIN,null,true));
        assertThrows(VinDecoder.Unavailable.class,()->new VpicVinDecoder(false,endpoint,Duration.ofSeconds(1)).decode(VIN,null,true));
        assertThrows(IllegalArgumentException.class,()->client().decode(VIN,null,false));assertEquals(0,calls.get());
    }
    @Test void invalid_input_is_rejected_before_network(){assertThrows(IllegalArgumentException.class,()->client().decode(VIN.substring(1),null,true));assertThrows(IllegalArgumentException.class,()->client().decode(VIN,1979,true));assertEquals(0,calls.get());}
    @Test void no_redirect_or_retry_on_http_failures(){for(int code:List.of(302,404,429,503)){calls.set(0);status.set(code);assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));assertEquals(1,calls.get());assertEquals(0,followed.get());}}
    @Test void missing_or_wrong_mime_is_not_json_success(){for(String value:List.of("text/html","text/plain")){mime.set(value);assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));}mime.set(null);assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));}
    @Test void ambiguous_mime_and_encoded_payload_are_not_accepted(){encoding.set("gzip");assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));encoding.set(null);duplicateMime.set(true);assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));}
    @Test void duplicate_json_and_trailing_document_are_rejected(){String valid=body.get();for(String value:List.of(valid+"{}",valid.replace("\"Count\":1","\"Count\":1,\"Count\":1"))){body.set(value);assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));}}
    @Test void oversized_body_is_bounded_and_fails_without_retry(){body.set("x".repeat(65537));assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));assertEquals(1,calls.get());}
    @Test void total_deadline_includes_body_after_headers(){delay.set(1500);var limited=new VpicVinDecoder(true,endpoint,Duration.ofMillis(200));long start=System.nanoTime();assertThrows(VinDecoder.Unavailable.class,()->limited.decode(VIN,null,true));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1200);assertEquals(1,calls.get());}
    @Test void upstream_diagnostics_and_input_are_not_in_error_or_cause(){body.set("private-upstream-diagnostic "+VIN);var error=assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));assertFalse(error.toString().contains(VIN));assertFalse(error.toString().contains("private-upstream"));assertNull(error.getCause());}
    @Test void preinterrupted_thread_keeps_interrupt_and_sends_nothing(){try{Thread.currentThread().interrupt();assertThrows(VinDecoder.Unavailable.class,()->client().decode(VIN,null,true));assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,calls.get());}finally{Thread.interrupted();}}
    @Test void interruption_during_request_preserves_interrupt()throws Exception{
        delay.set(1500);var thread=new AtomicReference<Thread>();var worker=Executors.newSingleThreadExecutor();
        try{var result=worker.submit(()->{thread.set(Thread.currentThread());try{client().decode(VIN,null,true);return false;}catch(VinDecoder.Unavailable e){return Thread.currentThread().isInterrupted();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));thread.get().interrupt();assertTrue(result.get(2,TimeUnit.SECONDS));
        }finally{worker.shutdownNow();}
    }
    @Test void arbitrary_destinations_and_invalid_deadlines_are_not_configurable(){
        for(String uri:List.of("https://example.invalid/api/vehicles/DecodeVinValues/","http://localhost:123/api/vehicles/DecodeVinValues/",endpoint+"?url=other",endpoint+"#fragment","http://user@127.0.0.1:123/api/vehicles/DecodeVinValues/"))assertThrows(IllegalArgumentException.class,()->new VpicVinDecoder(true,URI.create(uri),Duration.ofSeconds(1)));
        for(Duration limit:List.of(Duration.ZERO,Duration.ofMillis(10001)))assertThrows(IllegalArgumentException.class,()->new VpicVinDecoder(true,endpoint,limit));
    }
}
