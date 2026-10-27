package com.autocare.platform.order;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
/** One direct-handoff worker, no pending queue or automatic retry. */
final class EmbeddingExecutor implements AutoCloseable {
 static final class Failed extends RuntimeException {final String code;Failed(String code){super(code,null,false,false);this.code=code;}}
 private final EmbeddingProfile profile;private final EmbeddingProvider provider;private final Duration deadline;
 private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"knowledge-embedding");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 EmbeddingExecutor(EmbeddingProfile profile,EmbeddingProvider provider,Duration deadline){if(profile==null||provider==null||deadline==null||deadline.toMillis()<1||deadline.toMillis()>30000)throw new IllegalArgumentException("Invalid embedding execution configuration");this.profile=profile;this.provider=provider;this.deadline=deadline;}
 List<double[]> call(List<String> texts){
  if(texts==null||texts.isEmpty()||texts.size()>64)throw new Failed("LIMIT_EXCEEDED");var inputs=List.copyOf(texts);Future<List<double[]>> future=null;
  try{
   future=executor.submit(()->{var out=new ArrayList<double[]>();for(int i=0;i<inputs.size();i+=4){var batch=inputs.subList(i,Math.min(i+4,inputs.size()));try{out.addAll(EmbeddingVectors.validate(provider.embed(profile,batch),profile,batch.size()));}catch(IllegalArgumentException e){throw new Failed("INVALID_VECTOR");}}return List.copyOf(out);});
   return future.get(deadline.toMillis(),TimeUnit.MILLISECONDS);
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failed("UNAVAILABLE");}
  catch(ExecutionException e){if(e.getCause() instanceof Failed failed)throw failed;throw new Failed("UNAVAILABLE");}
  catch(RejectedExecutionException|TimeoutException e){throw new Failed("UNAVAILABLE");}
  finally{if(future!=null&&!future.isDone())future.cancel(true);}
 }
 public void close(){executor.shutdownNow();}
}
