package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** Long provider I/O never runs on the shared Spring scheduling thread. */
public final class AsrJobWorker implements AutoCloseable {
    private final AsrJobs jobs;private final SignedObjectStore store;private final AsrProvider provider;private final boolean enabled;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"asr-job-worker");thread.setDaemon(true);return thread;});
    public AsrJobWorker(AsrJobs jobs,SignedObjectStore store,AsrProvider provider,boolean enabled){this.jobs=jobs;this.store=store;this.provider=provider;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${ASR_JOB_INTERVAL_MS:1000}") public void tick(){
        if(!enabled||!busy.compareAndSet(false,true))return;
        try{executor.execute(()->{try{jobs.workOne(store,provider);}catch(RuntimeException ignored){report();}finally{busy.set(false);}});}catch(RejectedExecutionException e){busy.set(false);}
    }
    @Scheduled(fixedDelayString="${ASR_CLEANUP_INTERVAL_MS:60000}") public void cleanup(){try{jobs.cleanup();}catch(RuntimeException ignored){report();}}
    public boolean workOne(){if(!enabled||!busy.compareAndSet(false,true))return false;try{return jobs.workOne(store,provider);}finally{busy.set(false);}}
    private void report(){org.slf4j.LoggerFactory.getLogger(AsrJobWorker.class).warn("event=asr_job_processing_unavailable");}
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
