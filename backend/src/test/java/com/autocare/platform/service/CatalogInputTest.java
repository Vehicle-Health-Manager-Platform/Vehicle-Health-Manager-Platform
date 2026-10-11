package com.autocare.platform.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CatalogInputTest {
    @Test void strictTypesMoneyRangesAndUnknownFields(){String valid="{\"project_name\":\"标准保养\",\"category\":1,\"service_content\":\"授权项目内容\",\"quality_standard\":\"\",\"base_price_low\":\"10.00\",\"base_price_high\":\"20.00\",\"sort_order\":0,\"source_note\":\"授权来源\"}";assertNotNull(CatalogInput.parse(valid,"projects",false));for(String raw:new String[]{valid.replace("10.00","30.00"),valid.replace("10.00","1e2"),valid.replace("\"category\":1","\"category\":\"1\""),valid.replace("\"sort_order\":0","\"sort_order\":-1"),valid.replace("授权来源",""),valid.replace("{","{\"status\":1,"),valid+"{}"})assertThrows(org.springframework.web.server.ResponseStatusException.class,()->CatalogInput.parse(raw,"projects",false));}
    @Test void invalidYearAndParentRejected(){String valid="{\"series_id\":1,\"year\":\"2026\",\"config_name\":\"舒适版\",\"power_type\":\"FUEL\",\"sort_order\":0,\"source_note\":\"授权车型资料\"}";assertNotNull(CatalogInput.parse(valid,"models",false));for(String raw:new String[]{valid.replace("2026","2101"),valid.replace("FUEL","guess"),valid.replace("\"series_id\":1","\"series_id\":0")})assertThrows(org.springframework.web.server.ResponseStatusException.class,()->CatalogInput.parse(raw,"models",false));}
}
