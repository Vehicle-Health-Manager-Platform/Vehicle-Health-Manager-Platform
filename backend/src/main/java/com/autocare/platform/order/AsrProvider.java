package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
public interface AsrProvider {
    JsonNode transcribe(byte[] audio,int durationMs);
    final class Unavailable extends RuntimeException { public Unavailable(){super("ASR unavailable");} }
}
