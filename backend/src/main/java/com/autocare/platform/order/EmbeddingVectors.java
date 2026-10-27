package com.autocare.platform.order;
import java.util.*;
public final class EmbeddingVectors {
 public static double[] normalized(double[] vector,int dimension){
  if(vector==null||vector.length!=dimension||dimension<1||dimension>1024)throw new IllegalArgumentException("Invalid embedding vector");
  double maximum=0;for(double value:vector){if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid embedding vector");maximum=Math.max(maximum,Math.abs(value));}
  if(maximum==0)throw new IllegalArgumentException("Invalid embedding vector");double sum=0;for(double value:vector){double v=value/maximum;sum+=v*v;}double scale=Math.sqrt(sum);
  double[] out=new double[dimension];for(int i=0;i<dimension;i++)out[i]=(vector[i]/maximum)/scale;return out;
 }
 public static List<double[]> validate(EmbeddingProvider.Output output,EmbeddingProfile profile,int count){
  if(output==null||!profile.fingerprint().equals(output.profileHash())||output.vectors()==null||output.vectors().size()!=count)throw new IllegalArgumentException("Invalid embedding output");
  return output.vectors().stream().map(v->normalized(v,profile.dimension())).toList();
 }
 public static double cosine(double[] a,double[] b){a=normalized(a,a==null?0:a.length);b=normalized(b,a.length);double value=0;for(int i=0;i<a.length;i++)value+=a[i]*b[i];return Math.max(-1,Math.min(1,value));}
 private EmbeddingVectors(){}
}
