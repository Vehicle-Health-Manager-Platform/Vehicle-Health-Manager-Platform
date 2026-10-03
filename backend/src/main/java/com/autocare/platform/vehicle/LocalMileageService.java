package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

public class LocalMileageService {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    public LocalMileageService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.integrity = integrity;
        this.mapper = mapper;
    }

    public JsonNode update(long ownerId, long vehicleId, String key, int mileage) {
        if (vehicleId <= 0 || mileage < 0) throw WriteIntegrityService.badRequest("车辆或里程无效");
        int[] before = new int[1];
        return integrity.execute(new WriteIntegrityService.Actor("user", ownerId), "POST",
            "/api/demo/vehicles/" + vehicleId + "/mileage", key,
            mapper.valueToTree(Map.of("current_mileage", mileage)), () -> {
                var vehicles = jdbc.query("SELECT user_id,current_mileage FROM vehicle WHERE id=? "
                    + "AND is_deleted=0 FOR UPDATE", (rs, row) -> new long[]{rs.getLong(1), rs.getInt(2)}, vehicleId);
                if (vehicles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "车辆不存在");
                if (vehicles.get(0)[0] != ownerId) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改该车辆");
                before[0] = (int) vehicles.get(0)[1];
            }, () -> {
                if (mileage < before[0]) throw WriteIntegrityService.badRequest("里程不能低于已记录里程");
                jdbc.update("UPDATE vehicle SET current_mileage=? WHERE id=?", mileage, vehicleId);
                return new WriteIntegrityService.Change("VEHICLE_MILEAGE_UPDATE", "vehicle", vehicleId,
                    Map.of("current_mileage", before[0]), Map.of("current_mileage", mileage),
                    Map.of("id", vehicleId, "current_mileage", mileage));
            });
    }
}
