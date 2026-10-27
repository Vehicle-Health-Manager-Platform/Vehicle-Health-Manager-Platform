package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import com.fasterxml.jackson.databind.JsonNode;
public final class DocumentJobs extends CandidateJobs {
 public DocumentJobs(ReservationStore db,boolean enabled){super(db,enabled,Kind.DOCUMENT);}
 public static JsonNode input(String raw){return CandidateJobs.input(raw,Kind.DOCUMENT);}
 boolean workOne(SignedObjectStore store,DocumentProvider provider){return workCandidate(store,provider==null?null:(bytes,type,duration,pages)->provider.extract(bytes,type,pages));}
}
