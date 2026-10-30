package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.vehicle.vin.VinDecoder;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Read-only business operation; admission counters never contain VIN or provider output. */
public final class VinRecognition {
    public static final String CONSENT_VERSION = "VIN_VPIC_V1";
    public record Input(String vin, Integer modelYear,String consentVersion) {
        public Input(String vin,Integer modelYear){this(vin,modelYear,CONSENT_VERSION);}
    }
    private final ReservationStore db;
    private final VinDecoder decoder;
    private final boolean enabled;
    private final Semaphore permits = new Semaphore(2);

    public VinRecognition(ReservationStore db, VinDecoder decoder, boolean enabled) {
        this.db = db; this.decoder = decoder; this.enabled = enabled;
    }

    public static Input input(String raw) {
        try {
            if (raw == null || raw.length() > 1024) throw invalid();
            var body = CouponInput.parse(raw);
            if (body.has("model_year")) CouponInput.exact(body,"vin","model_year","consent","consent_version");
            else CouponInput.exact(body,"vin","consent","consent_version");
            if (!body.get("vin").isTextual() || !body.get("consent").isBoolean() || !body.get("consent").booleanValue()
                    || !body.get("consent_version").isTextual() || !java.util.Set.of(CONSENT_VERSION,"VIN_LOCAL_V1").contains(body.get("consent_version").textValue())) throw invalid();
            Integer year = null;
            if (body.has("model_year")) {
                var value = body.get("model_year");
                if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
                year = value.intValue();
            }
            VinDecoder.checkYear(year);
            return new Input(VinDecoder.normalize(body.get("vin").textValue()),year,body.get("consent_version").textValue());
        } catch (RuntimeException e) { throw invalid(); }
    }

    public JsonNode recognize(VehicleOwner owner, Input input) {
        // Also enforce the protocol for in-process callers, not only controller callers.
        if (input == null) throw invalid();
        try { if (!VinDecoder.normalize(input.vin()).equals(input.vin())) throw invalid(); VinDecoder.checkYear(input.modelYear()); }
        catch (IllegalArgumentException e) { throw invalid(); }
        db.reads.executeWithoutResult(t -> db.owner(owner,false));
        if (!enabled || decoder == null) throw unavailable();
        boolean local=decoder instanceof com.autocare.platform.vehicle.vin.LocalVinDecoder;
        if(!(local?"VIN_LOCAL_V1":CONSENT_VERSION).equals(input.consentVersion())) throw invalid();
        if (!permits.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"VIN服务繁忙，请稍后重试或手动录车");
        try {
            boolean admitted = db.transactions.execute(t -> {
                db.owner(owner,true);
                String hash = AuthTokens.sha256("owner:" + owner.id());
                var window = ReservationStore.time(Instant.ofEpochSecond(Math.floorDiv(db.now().getEpochSecond(),60)*60));
                db.jdbc.update("DELETE FROM auth_rate_limit WHERE scope='vin_decode' AND key_hash=? AND window_start<?",hash,ReservationStore.time(db.now().minusSeconds(86400)));
                db.jdbc.update("INSERT IGNORE INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES('vin_decode',?,?,0)",hash,window);
                int count = db.jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='vin_decode' AND key_hash=? AND window_start=? FOR UPDATE",Integer.class,hash,window);
                if (count >= 5) return false;
                db.jdbc.update("UPDATE auth_rate_limit SET attempts=attempts+1 WHERE scope='vin_decode' AND key_hash=? AND window_start=?",hash,window);
                return true;
            });
            if (!admitted) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"VIN查询过于频繁，请下一分钟重试或手动录车");
            VinDecoder.Result result = null;
            try { result = decoder.decode(input.vin(),input.modelYear(),true); }
            catch (RuntimeException ignored) { /* Never propagate supplier text or a VIN-bearing URL. */ }
            db.reads.executeWithoutResult(t -> db.owner(owner,false));
            if (result == null || !(local?java.util.Set.of("LOCAL_LICENSED","LOCAL_SYNTHETIC"):java.util.Set.of("NHTSA_VPIC")).contains(result.source())) throw unavailable();
            String year = result.fields().get("model_year");
            if (year != null && (!year.matches("[0-9]{4}") || Integer.parseInt(year)<1980 || Integer.parseInt(year)>2100
                    || input.modelYear()!=null && !year.equals(input.modelYear().toString()))) throw unavailable();
            var response = db.mapper.createObjectNode().put("vin",input.vin()).put("status",result.status().name())
                .put("source",result.source()).put("coverage",local?"PINNED_LOCAL_RULES_ONLY":"US_MARKET_REGISTRATION_ONLY").put("requires_confirmation",true);
            if (input.modelYear()==null) response.putNull("model_year_hint"); else response.put("model_year_hint",input.modelYear());
            response.set("fields",db.mapper.valueToTree(result.fields()));
            if(local) {
                com.autocare.platform.vehicle.vin.LocalVinDecoder.Evidence evidence;
                try { evidence=((com.autocare.platform.vehicle.vin.LocalVinDecoder)decoder).evidence(); }
                catch(RuntimeException ignored) { throw unavailable(); }
                response.put("source_revision",evidence.revision()).put("synthetic",evidence.synthetic());
                var candidates=java.util.List.<java.util.Map<String,Object>>of();
                if(result.status()==VinDecoder.Status.CANDIDATE && result.fields().containsKey("trim")) {
                    var fields=result.fields();
                    candidates=db.reads.execute(t->{db.owner(owner,false);return db.jdbc.query(
                        "SELECT m.id,m.series_id,s.brand_id,b.name AS brand_name,s.name AS series_name,m.year,m.config_name FROM model m JOIN series s ON s.id=m.series_id JOIN brand b ON b.id=s.brand_id "
                        +"WHERE m.status=1 AND m.is_deleted=0 AND s.status=1 AND s.is_deleted=0 AND b.status=1 AND b.is_deleted=0 "
                        +"AND BINARY b.name=? AND BINARY s.name=? AND BINARY m.year=? AND BINARY m.config_name=? ORDER BY m.id LIMIT 11",
                        (rs,n)->java.util.Map.<String,Object>of("model_id",rs.getLong("id"),"series_id",rs.getLong("series_id"),"brand_id",rs.getLong("brand_id"),
                            "brand_name",rs.getString("brand_name"),"series_name",rs.getString("series_name"),"year",rs.getString("year"),"config_name",rs.getString("config_name")),
                        fields.get("make"),fields.get("model"),fields.get("model_year"),fields.get("trim"));});
                }
                // Duplicate catalog descriptions are ambiguous; manual selection remains available.
                response.set("catalog_candidates",db.mapper.valueToTree(candidates.size()==1?candidates:java.util.List.of()));
            }
            return response;
        } finally { permits.release(); }
    }

    static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对17位VIN、可选年款及来源用途同意"); }
    static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"VIN解码未配置或暂不可用，请手动确认车型"); }
}
