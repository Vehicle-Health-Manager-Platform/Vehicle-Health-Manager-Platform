package com.autocare.platform.order;
import java.util.List;
/** Explicit adapter seam; no default, hash-vector or keyword implementation. */
@FunctionalInterface public interface EmbeddingProvider {
 record Output(String profileHash,List<double[]> vectors){}
 Output embed(EmbeddingProfile profile,List<String> texts);
}
