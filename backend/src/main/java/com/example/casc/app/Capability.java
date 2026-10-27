package com.example.casc.app;

import java.util.LinkedHashMap;
import java.util.Map;

public record Capability(String name, Status status, String detail) {

    public enum Status { AVAILABLE, PLANNED, UNAVAILABLE }

    public Map<String, Object> toTree() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("status", status.name());
        t.put("detail", detail);
        return t;
    }
}
