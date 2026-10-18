package com.autocare.platform.file;

/** Server-generated scope, never accepted as an arbitrary request path. */
public record InputMaterialScope(String purpose, long vehicleId) {
    public InputMaterialScope {
        if (!("AI".equals(purpose) || "ARCHIVE".equals(purpose)) || vehicleId < 1 || vehicleId > 9007199254740991L)
            throw new UploadHttpException(400, "辅助材料用途或车辆无效");
    }
    public String path() { return "/api/input-materials/" + purpose + "/" + vehicleId + "/upload"; }
    static InputMaterialScope fromPath(String path) {
        if (!path.startsWith("/api/input-materials/")) return null;
        String[] parts = path.split("/");
        if (parts.length != 6 || !"upload".equals(parts[5])) throw UploadHttpException.unavailable();
        try { return new InputMaterialScope(parts[3], Long.parseLong(parts[4])); }
        catch (NumberFormatException e) { throw UploadHttpException.unavailable(); }
    }
}
