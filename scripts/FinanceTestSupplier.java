import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

/** Isolated LOCAL_TEST fixture only. Never loaded by the application or a production profile. */
class FinanceTestSupplier {
    static final int PORT=18091;
    static final String FAILED="R"+"3".repeat(32);
    static final Map<String,AtomicInteger> counters=new TreeMap<>();
    static final Map<String,String> receipts=new ConcurrentHashMap<>();
    static final String secret=System.getenv("PAYMENT_LOCAL_TEST_SECRET");
    static String sign(String raw,String time,String nonce)throws Exception{
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((time+"\n"+nonce+"\n"+raw+"\n").getBytes(StandardCharsets.UTF_8)));
    }
    static void count(String name){counters.get(name).incrementAndGet();}
    static String field(String raw,String key,boolean number){
        var matcher=Pattern.compile("\\\""+key+"\\\"\\s*:\\s*"+(number?"([0-9]+)":"\\\"([^\\\"]*)\\\"")).matcher(raw);
        if(!matcher.find())throw new IllegalArgumentException();String value=matcher.group(1);if(matcher.find())throw new IllegalArgumentException();return value;
    }
    static String refund(long id,String no,long payment,String amount,String transaction){
        String event=UUID.nameUUIDFromBytes(("finance-refund:"+no).getBytes(StandardCharsets.UTF_8)).toString();
        return "{\"event_id\":\""+event+"\",\"refund_id\":"+id+",\"refund_no\":\""+no+"\",\"payment_id\":"+payment+",\"channel_refund_no\":\""+transaction+"\",\"status\":\"SUCCEEDED\",\"amount\":\""+amount+"\",\"currency\":\"CNY\",\"occurred_at\":\""+Instant.now()+"\"}";
    }
    static void response(HttpExchange e,int code,String body,boolean signed)throws Exception{
        var raw=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");
        if(signed){String time=""+Instant.now().getEpochSecond(),nonce=UUID.randomUUID().toString();e.getResponseHeaders().add("X-Test-Timestamp",time);e.getResponseHeaders().add("X-Test-Nonce",nonce);e.getResponseHeaders().add("X-Test-Signature",sign(body,time,nonce));}
        e.sendResponseHeaders(code,raw.length);e.getResponseBody().write(raw);
    }
    static void handle(HttpExchange e){
        try{
            String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
            if(path.equals("/health")&&method.equals("GET")){response(e,200,"{\"status\":\"UP\"}",false);return;}
            if(path.equals("/stats")&&method.equals("GET")){var rows=new ArrayList<String>();counters.forEach((k,v)->rows.add("\""+k+"\":"+v));response(e,200,"{"+String.join(",",rows)+"}",false);return;}
            if(path.startsWith("/payments/")&&method.equals("GET")){
                String no=path.substring("/payments/".length());int id=no.equals("P"+"1".repeat(32))?1:no.equals("P"+"4".repeat(32))?4:0;
                if(id==0){response(e,404,"{}",false);return;}count("payment_"+id+"_queries");
                String raw=receipts.computeIfAbsent(no,k->"{\"event_id\":\""+UUID.nameUUIDFromBytes(k.getBytes(StandardCharsets.UTF_8))+"\",\"payment_id\":"+id+",\"channel_payment_no\":\"synthetic-finance-paid"+id+"\",\"status\":\"SUCCEEDED\",\"amount\":\""+(id==1?"12.34":"99.99")+"\",\"currency\":\"CNY\",\"order_no\":\"SYNTHFINANCE000"+id+"\",\"occurred_at\":\""+Instant.now()+"\"}");
                response(e,200,raw,true);return;
            }
            if(path.matches("/refunds/R[a-f0-9]{32}")){
                String no=path.substring("/refunds/".length());boolean failed=no.equals(FAILED);
                if(method.equals("GET")){
                    count(failed?"failed_refund_queries":"fresh_refund_queries");
                    if(failed){response(e,200,receipts.computeIfAbsent(no,k->refund(10,k,3,"20.00","synthetic-finance-refund3")),true);return;}
                    String raw=receipts.get(no);response(e,raw==null?404:200,raw==null?"{}":raw,raw!=null);return;
                }
                if(method.equals("POST")){
                    count(failed?"failed_refund_posts":"fresh_refund_posts");
                    String raw=new String(e.getRequestBody().readNBytes(16385),StandardCharsets.UTF_8);
                    String time=e.getRequestHeaders().getFirst("X-Test-Timestamp"),nonce=e.getRequestHeaders().getFirst("X-Test-Nonce"),signature=e.getRequestHeaders().getFirst("X-Test-Signature");
                    var names=new HashSet<String>();var keys=Pattern.compile("\\\"([a-z_]+)\\\"\\s*:").matcher(raw);while(keys.find())if(!names.add(keys.group(1)))throw new IllegalArgumentException();
                    if(failed||raw.length()>16384||!names.equals(Set.of("refund_id","refund_no","payment_id","payment_no","channel_payment_no","amount","currency"))||!no.equals(e.getRequestHeaders().getFirst("Idempotency-Key"))||Math.abs(Instant.now().getEpochSecond()-Long.parseLong(time))>300||!MessageDigest.isEqual(sign(raw,time,nonce).getBytes(StandardCharsets.UTF_8),signature.getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
                    if(!field(raw,"refund_no",false).equals(no)||!field(raw,"payment_id",true).equals("2")||!field(raw,"payment_no",false).equals("P"+"2".repeat(32))||!field(raw,"channel_payment_no",false).equals("synthetic-finance-paid2")||!field(raw,"amount",false).equals("12.34")||!field(raw,"currency",false).equals("CNY"))throw new IllegalArgumentException();
                    String receipt=receipts.computeIfAbsent(no,k->refund(Long.parseLong(field(raw,"refund_id",true)),k,2,"12.34","synthetic-finance-refund2"));response(e,200,receipt,true);return;
                }
            }
            response(e,404,"{}",false);
        }catch(Exception error){count("invalid_submissions");try{response(e,400,"{\"error\":\"fixture_rejected\"}",false);}catch(Exception ignored){} }
        finally{e.close();}
    }
    public static void main(String[] args)throws Exception{
        if(args.length==1&&Set.of("--health","--stats").contains(args[0])){
            var result=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+PORT+(args[0].equals("--health")?"/health":"/stats"))).timeout(java.time.Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=200)throw new IllegalStateException("Fixture unavailable");System.out.println(result.body());return;
        }
        if(secret==null||secret.length()<32)throw new IllegalStateException("Isolated key required");
        for(String key:List.of("payment_1_queries","payment_4_queries","fresh_refund_queries","fresh_refund_posts","failed_refund_queries","failed_refund_posts","invalid_submissions"))counters.put(key,new AtomicInteger());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",PORT),0);server.createContext("/",FinanceTestSupplier::handle);server.setExecutor(Executors.newFixedThreadPool(2));server.start();
    }
}
