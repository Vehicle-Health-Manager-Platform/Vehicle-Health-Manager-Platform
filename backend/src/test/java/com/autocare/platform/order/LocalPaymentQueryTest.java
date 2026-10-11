package com.autocare.platform.order;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalPaymentQueryTest {
    HttpServer server;ExecutorService executor;LocalTestPaymentChannel channel;
    int status=200;byte[] body=LocalTestPaymentChannelTest.body("SUCCEEDED").getBytes(StandardCharsets.UTF_8);boolean signed=true;long delay=0;
    final PaymentQuery query=new PaymentQuery(1,"P"+"a".repeat(32),"Rtest",new java.math.BigDecimal("12.34"),"CNY");
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/payments/",exchange->{
            try{
                assertEquals("/payments/"+query.paymentNo(),exchange.getRequestURI().getPath());assertEquals("GET",exchange.getRequestMethod());
                String time=""+LocalTestPaymentChannelTest.NOW.getEpochSecond(),nonce=UUID.randomUUID().toString();
                if(signed){exchange.getResponseHeaders().add("X-Test-Timestamp",time);exchange.getResponseHeaders().add("X-Test-Nonce",nonce);exchange.getResponseHeaders().add("X-Test-Signature",LocalTestPaymentChannelTest.sign(body,time,nonce));}
                exchange.getResponseHeaders().add("Location","http://example.invalid/receipt");exchange.sendResponseHeaders(status,body.length);
                if(delay>0)Thread.sleep(delay);exchange.getResponseBody().write(body);
            }catch(Exception ignored){}finally{exchange.close();}
        });server.start();channel=new LocalTestPaymentChannel(LocalTestPaymentChannelTest.KEY,Clock.fixed(LocalTestPaymentChannelTest.NOW,ZoneOffset.UTC),"http://127.0.0.1:"+server.getAddress().getPort());
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    @Test void independentlySignedReceiptIsBoundToServerFacts(){assertEquals("SUCCEEDED",channel.query(query).orElseThrow().status());assertThrows(RuntimeException.class,()->channel.query(new PaymentQuery(2,query.paymentNo(),query.orderNo(),query.amount(),query.currency())));assertThrows(RuntimeException.class,()->channel.query(new PaymentQuery(1,query.paymentNo(),"wrong",query.amount(),query.currency())));assertThrows(RuntimeException.class,()->channel.query(new PaymentQuery(1,query.paymentNo(),query.orderNo(),new java.math.BigDecimal("0.01"),query.currency())));}
    @Test void pendingAndUnknownAreNotFailures(){status=202;assertTrue(channel.query(query).isEmpty());status=404;assertTrue(channel.query(query).isEmpty());}
    @Test void unsignedRedirectAndServerErrorNeverReturnReceipt(){signed=false;assertThrows(RuntimeException.class,()->channel.query(query));status=302;assertThrows(RuntimeException.class,()->channel.query(query));status=503;assertThrows(RuntimeException.class,()->channel.query(query));}
    @Test void responseLimitAndBodyDeadlineApplyAfterHeaders(){body=new byte[16385];assertThrows(RuntimeException.class,()->channel.query(query));body=LocalTestPaymentChannelTest.body("SUCCEEDED").getBytes(StandardCharsets.UTF_8);delay=10000;long start=System.nanoTime();assertThrows(RuntimeException.class,()->channel.query(query));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<6000);}
    @Test void queryConfigurationCannotTargetExternalOrCredentialUrl(){for(String url:List.of("https://127.0.0.1","http://example.com","http://user@localhost","http://localhost/x","http://localhost?x=1","http://localhost#x"))assertThrows(IllegalArgumentException.class,()->new LocalTestPaymentChannel(LocalTestPaymentChannelTest.KEY,Clock.systemUTC(),url));assertFalse(new LocalTestPaymentChannel(LocalTestPaymentChannelTest.KEY,Clock.systemUTC()).canQuery());
        var disabled=new PaymentChannels(new org.springframework.mock.env.MockEnvironment(),Clock.systemUTC());assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->disabled.query("LOCAL_TEST")).getStatusCode().value());
    }
}
