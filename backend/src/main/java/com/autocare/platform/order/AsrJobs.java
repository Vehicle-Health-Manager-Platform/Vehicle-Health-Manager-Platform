package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import com.fasterxml.jackson.databind.JsonNode;
/** ASR adapter over the shared, task-isolated candidate lifecycle. */
public final class AsrJobs extends CandidateJobs {
 public AsrJobs(ReservationStore db,boolean enabled){super(db,enabled,Kind.ASR);}
 public static JsonNode input(String raw){return CandidateJobs.input(raw,Kind.ASR);}
 boolean workOne(SignedObjectStore store,AsrProvider provider){return workCandidate(store,provider==null?null:(bytes,type,duration,pages)->provider.transcribe(bytes,duration));}
}
