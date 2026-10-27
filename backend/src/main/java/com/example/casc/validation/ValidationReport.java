package com.example.casc.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ValidationReport(List<Finding> findings) {

    public ValidationReport {
        findings = List.copyOf(findings);
    }

    private List<Finding> of(Severity s) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (f.severity() == s) {
                out.add(f);
            }
        }
        return out;
    }

    public List<Finding> errors() { return of(Severity.ERROR); }

    public List<Finding> warnings() { return of(Severity.WARNING); }

    public List<Finding> infos() { return of(Severity.INFO); }

    public boolean hasErrors() { return !errors().isEmpty(); }

    public Map<String, Object> toTree() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("valid", !hasErrors());
        t.put("errorCount", errors().size());
        t.put("warningCount", warnings().size());
        t.put("infoCount", infos().size());
        List<Object> list = new ArrayList<>();
        for (Finding f : findings) {
            list.add(f.toTree());
        }
        t.put("findings", list);
        return t;
    }
}
