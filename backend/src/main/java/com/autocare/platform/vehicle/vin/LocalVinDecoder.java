package com.autocare.platform.vehicle.vin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Licensed, pinned local rules. No network, plate lookup or registration side effects. */
public final class LocalVinDecoder implements VinDecoder {
    private final Path path;
    private final String sha;
    private final boolean allowSynthetic;
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public LocalVinDecoder(Path path,String sha,boolean allowSynthetic) {
        if(path==null || sha==null || !sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("VIN本地来源必须配置文件及SHA256");
        this.path=path;this.sha=sha;this.allowSynthetic=allowSynthetic;
    }
    public record Evidence(String revision,boolean synthetic) {}
    private JsonNode document() {
        try {
            if(!Files.isRegularFile(path) || Files.size(path)>1048576) throw new Unavailable();
            byte[] raw=Files.readAllBytes(path);
            if(raw.length>1048576 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(sha)) throw new Unavailable();
            var doc=mapper.readTree(raw);
            if(!doc.isObject() || doc.size()!=7 || !doc.path("schema_version").isIntegralNumber() || !doc.path("schema_version").canConvertToInt() || doc.path("schema_version").intValue()!=1 || !doc.path("revision").asText().matches("[A-Za-z0-9_.-]{1,64}")
                || !doc.path("license_reference").isTextual() || doc.path("license_reference").asText().isBlank()
                || doc.path("license_reference").asText().length()>256
                || !doc.path("synthetic").isBoolean() || doc.path("synthetic").asBoolean()&&!allowSynthetic
                || Instant.parse(doc.path("valid_from").asText()).isAfter(Instant.now())
                || !Instant.parse(doc.path("valid_until").asText()).isAfter(Instant.now())
                || !doc.path("rules").isArray() || doc.path("rules").size()>1000) throw new Unavailable();
            for(var rule:doc.path("rules")) {
                if(!rule.isObject() || rule.size()!=5 || !rule.path("vin_prefix").asText().matches("[A-HJ-NPR-Z0-9]{6,11}")) throw new Unavailable();
                var fields=new LinkedHashMap<String,String>();
                for(String key:List.of("make","model","model_year","trim")) {
                    if(!rule.path(key).isTextual()) throw new Unavailable();fields.put(key,rule.path(key).asText());
                }
                new Result(Status.CANDIDATE,"LOCAL_LICENSED",fields,true);
                if(!fields.get("model_year").matches("[0-9]{4}")) throw new Unavailable();
                VinDecoder.checkYear(Integer.parseInt(fields.get("model_year")));
            }
            return doc;
        } catch(Exception e) { throw new Unavailable(); }
    }
    /** Re-read on each use: revoked, expired, removed or edited files fail closed. */
    public Evidence evidence() { var doc=document();return new Evidence(doc.path("revision").asText(),doc.path("synthetic").asBoolean()); }
    @Override public Result decode(String vin,Integer year,boolean consent) {
        vin=VinDecoder.normalize(vin);VinDecoder.checkYear(year);if(!consent) throw new Unavailable();var doc=document();
        Set<Map<String,String>> matches=new HashSet<>();
        for(var rule:doc.path("rules")) if(vin.startsWith(rule.path("vin_prefix").asText()) && (year==null || rule.path("model_year").asText().equals(year.toString()))) {
            var fields=new LinkedHashMap<String,String>();for(String key:List.of("make","model","model_year","trim")) fields.put(key,rule.path(key).asText());matches.add(fields);
        }
        String source=doc.path("synthetic").asBoolean()?"LOCAL_SYNTHETIC":"LOCAL_LICENSED";
        // A prefix may cover multiple configurations. Never turn the first rule into an exact match.
        if(matches.size()!=1) return new Result(Status.NO_MATCH,source,Map.of(),true);
        return new Result(Status.CANDIDATE,source,matches.iterator().next(),true);
    }
}
