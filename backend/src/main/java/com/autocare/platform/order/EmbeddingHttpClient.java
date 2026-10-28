package com.autocare.platform.order;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Private fixed-profile batches only. Does not enable public VECTOR retrieval. */
public final class EmbeddingHttpClient implements EmbeddingProvider {
 private final URI endpoint;private final String token;private final EmbeddingProfile expected;private final Duration deadline;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 public EmbeddingHttpClient(String url,String token,boolean privateHttp,EmbeddingProfile expected){this(url,token,privateHttp,expected,Duration.ofSeconds(25));}
 EmbeddingHttpClient(String url,String token,boolean privateHttp,EmbeddingProfile expected,Duration deadline){
  try{endpoint=URI.create(url);
   if(endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null||!"/embed".equals(endpoint.getRawPath())||!("https".equals(endpoint.getScheme())||privateHttp&&"http".equals(endpoint.getScheme())&&Set.of("embedding","localhost","127.0.0.1").contains(endpoint.getHost()))||token==null||token.length()<32||token.length()>512||!token.chars().allMatch(c->c>=33&&c<=126)||expected==null||expected.encoderPolicy()==null||expected.dimension()!=512||deadline==null||deadline.toMillis()<1||deadline.toMillis()>25000)throw new IllegalArgumentException();
   this.token=token;this.expected=expected;this.deadline=deadline;
  }catch(RuntimeException e){throw new IllegalArgumentException("Invalid private embedding configuration");}
 }
 private static void input(List<String> texts){
  if(texts==null||texts.isEmpty()||texts.size()>4)throw new Failed("LIMIT_EXCEEDED");
  for(String text:texts)if(text==null||text.isBlank()||text.length()>1024||text.codePoints().anyMatch(c->Character.getType(c)==Character.SURROGATE||Character.getType(c)==Character.FORMAT||Character.isISOControl(c)&&c!='\n'))throw new Failed("LIMIT_EXCEEDED");
 }
 public Output embed(EmbeddingProfile profile,List<String> texts){
  if(!expected.equals(profile))throw new Failed("INVALID_VECTOR");input(texts);
  try{
   byte[] raw=json.writeValueAsBytes(Map.of("version",1,"profile_hash",expected.fingerprint(),"texts",texts));if(raw.length>32768)throw new Failed("LIMIT_EXCEEDED");
   var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(raw)).build();
   var pending=client.sendAsync(request,info->new Body());HttpResponse<byte[]> response;
   try{response=pending.get(deadline.toMillis(),TimeUnit.MILLISECONDS);}finally{if(!pending.isDone())pending.cancel(true);}
   var types=response.headers().allValues("Content-Type");var encodings=response.headers().allValues("Content-Encoding");
   if(types.size()!=1||!types.get(0).split(";",2)[0].strip().equalsIgnoreCase("application/json")||encodings.size()>1||!encodings.isEmpty()&&!encodings.get(0).strip().equalsIgnoreCase("identity"))throw new Failed("UNAVAILABLE");
   var value=json.readTree(response.body());
   if(response.statusCode()!=200){
    if(Set.of(413,422).contains(response.statusCode())&&value.isObject()&&value.size()==1&&value.path("error").isTextual()){String code=value.path("error").asText();if(code.equals("LIMIT_EXCEEDED"))throw new Failed(code);if(Set.of("INVALID_REQUEST","INVALID_RESULT").contains(code))throw new Failed("INVALID_VECTOR");}
    throw new Failed("UNAVAILABLE");
   }
   if(!value.isObject()||value.size()!=3||!value.has("version")||!value.path("version").isIntegralNumber()||!value.path("version").canConvertToInt()||value.path("version").intValue()!=1||!value.path("profile_hash").isTextual()||!value.path("profile_hash").textValue().equals(expected.fingerprint())||!value.path("vectors").isArray()||value.path("vectors").size()!=texts.size())throw new Failed("INVALID_VECTOR");
   var vectors=new ArrayList<double[]>();
   for(var row:value.path("vectors")){if(!row.isArray()||row.size()!=512)throw new Failed("INVALID_VECTOR");double[] vector=new double[512];double norm=0;for(int i=0;i<512;i++){var n=row.get(i);if(!n.isNumber()||!Double.isFinite(n.doubleValue())||Math.abs(n.doubleValue())>1.00001)throw new Failed("INVALID_VECTOR");vector[i]=n.doubleValue();norm+=vector[i]*vector[i];}if(Math.abs(norm-1)>0.0001)throw new Failed("INVALID_VECTOR");vectors.add(vector);}
   return new Output(expected.fingerprint(),List.copyOf(vectors));
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failed("UNAVAILABLE");}catch(Failed e){throw e;}catch(Exception e){throw new Failed("UNAVAILABLE");}
 }
 private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
  final CompletableFuture<byte[]> value=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
  public CompletionStage<byte[]> getBody(){return value;}
  public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
  public void onNext(List<ByteBuffer> buffers){try{for(var buffer:buffers){if((long)bytes.size()+buffer.remaining()>131072)throw new IllegalArgumentException();byte[] raw=new byte[buffer.remaining()];buffer.get(raw);bytes.writeBytes(raw);}subscription.request(1);}catch(RuntimeException e){subscription.cancel();value.completeExceptionally(new Failed("UNAVAILABLE"));}}
  public void onError(Throwable e){value.completeExceptionally(new Failed("UNAVAILABLE"));}
  public void onComplete(){value.complete(bytes.toByteArray());}
 }
}
