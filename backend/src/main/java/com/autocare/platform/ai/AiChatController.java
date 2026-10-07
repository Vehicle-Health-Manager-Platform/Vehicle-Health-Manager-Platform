package com.autocare.platform.ai;

import com.autocare.platform.common.ApiResponse;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-facing AI conversation. Answers are grounded in the owner's own vehicle archive when a
 * vehicle is selected; without one the model answers as general automotive knowledge.
 */
@RestController
public class AiChatController {
    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_HISTORY = 8;
    private static final int MAX_TOKENS = 1024;
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final Set<String> FIELDS = Set.of("message", "vehicle_id", "history");
    private static final String SYSTEM_PROMPT = String.join("\n",
        "你是「汽车健康管家」小程序里的 AI 管家，为中国车主提供日常用车、保养与维修方面的建议。",
        "回答要求：",
        "1. 使用简体中文，语气友好、务实，先给结论再给理由，必要时分点说明。",
        "2. 结合下方提供的车辆档案作答；没有档案时给出通用建议，并提醒车主可在「档案」页录入信息以获得更准确的判断。",
        "3. 引用档案时说明依据的记录，不要编造档案里没有的信息。",
        "4. 不给出确定性故障结论，不承诺维修价格或工期；涉及安全的问题（制动、转向、轮胎、电池、自燃风险）要提示尽快到店检查。",
        "5. 需要实际施工或检查时，建议车主在「服务」页选择合适的项目与商家。",
        "6. 回答控制在 400 字以内，避免堆砌无关内容。");

    private final DeepSeekClient client;
    private final ObjectProvider<AiContextProvider> contexts;

    public AiChatController(DeepSeekClient client, ObjectProvider<AiContextProvider> contexts) {
        this.client = client;
        this.contexts = contexts;
    }

    private record Turn(String role, String content) {}

    @PostMapping("/api/ai/chat")
    public ResponseEntity<?> chat(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) JsonNode body) {
        VehicleOwner owner = VehicleOwner.from(jwt);
        Request request = Request.parse(body);
        String system = SYSTEM_PROMPT;
        Long vehicleId = null;
        if (request.vehicleId() != null) {
            AiContextProvider provider = contexts.getIfAvailable();
            if (provider != null) {
                try {
                    AiContextProvider.Context context = provider.forVehicle(owner, request.vehicleId());
                    system = SYSTEM_PROMPT + "\n\n【车主当前车辆档案】\n" + context.summary();
                    vehicleId = context.vehicleId();
                } catch (DataAccessException unavailable) {
                    // 档案库读不到时降级为通用回答，不阻断对话。
                }
            }
        }
        List<DeepSeekClient.Message> turns = new ArrayList<>();
        for (Turn turn : request.history()) {
            turns.add(new DeepSeekClient.Message(turn.role(), turn.content()));
        }
        turns.add(new DeepSeekClient.Message("user", request.message()));
        DeepSeekClient.Completion completion = client.complete(system, turns, MAX_TOKENS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reply", completion.content());
        data.put("model", completion.model());
        data.put("grounded", vehicleId != null);
        data.put("vehicle_id", vehicleId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record Request(String message, Long vehicleId, List<Turn> history) {
        static Request parse(JsonNode body) {
            if (body == null || !body.isObject()) throw badRequest("对话请求格式无效");
            Iterator<String> names = body.fieldNames();
            while (names.hasNext()) {
                if (!FIELDS.contains(names.next())) throw badRequest("对话请求格式无效");
            }
            JsonNode message = body.get("message");
            if (message == null || !message.isTextual()) throw badRequest("请输入想要咨询的用车问题");
            String text = message.asText().trim();
            if (text.isEmpty() || text.length() > MAX_MESSAGE) throw badRequest("请输入 1–2000 字的用车问题");

            Long vehicleId = null;
            JsonNode vehicle = body.get("vehicle_id");
            if (vehicle != null && !vehicle.isNull()) {
                if (!vehicle.isIntegralNumber() || !vehicle.canConvertToLong()) throw badRequest("车辆参数无效");
                long id = vehicle.longValue();
                if (id <= 0 || id > 9007199254740991L) throw badRequest("车辆参数无效");
                vehicleId = id;
            }

            List<Turn> history = new ArrayList<>();
            JsonNode turns = body.get("history");
            if (turns != null && !turns.isNull()) {
                if (!turns.isArray() || turns.size() > MAX_HISTORY) throw badRequest("历史对话过长，请开始新的对话");
                for (JsonNode turn : turns) {
                    if (!turn.isObject() || turn.size() != 2 || !turn.get("role").isTextual() || !turn.get("content").isTextual()) {
                        throw badRequest("历史对话格式无效");
                    }
                    String role = turn.get("role").asText();
                    String content = turn.get("content").asText();
                    if (!ROLES.contains(role) || content.isBlank() || content.length() > MAX_MESSAGE) {
                        throw badRequest("历史对话格式无效");
                    }
                    history.add(new Turn(role, content));
                }
            }
            return new Request(text, vehicleId, List.copyOf(history));
        }
    }
}
