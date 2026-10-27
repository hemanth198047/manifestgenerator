package com.example.casc.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/** One validation or extraction result: rule code, severity, field/asset path, message, suggested correction. */
public record Finding(String code, Severity severity, String path, String message, String suggestion) {

    public static Finding error(String code, String path, String message, String suggestion) {
        return new Finding(code, Severity.ERROR, path, message, suggestion);
    }

    public static Finding warning(String code, String path, String message, String suggestion) {
        return new Finding(code, Severity.WARNING, path, message, suggestion);
    }

    public static Finding info(String code, String path, String message, String suggestion) {
        return new Finding(code, Severity.INFO, path, message, suggestion);
    }

    public Map<String, Object> toTree() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", code);
        t.put("severity", severity.name());
        t.put("path", path);
        t.put("message", message);
        t.put("suggestion", suggestion);
        return t;
    }
}
