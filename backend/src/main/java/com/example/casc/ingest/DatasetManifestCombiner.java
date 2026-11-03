package com.example.casc.ingest;

import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.validation.Finding;
import com.example.casc.validation.Severity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Combines the "datasets" of several dataset manifests (such as the per-table files made by "ingest --manifest-input")
 * into one manifest. All datasets are combined, or only a selection: the first N (count) or the named tables (tables).
 * The order of the inputs and of the datasets inside them is kept; a dataset name that is already in the result is not added twice.
 */
public final class DatasetManifestCombiner {
    private DatasetManifestCombiner() {}

    /** Both fields null: combine everything. At most one of them may be set. */
    public record Selection(Integer count, List<String> tables) {
        public static Selection all() {
            return new Selection(null, null);
        }
    }

    public record Result(String content, int datasets, List<Finding> findings) {
        public boolean hasErrors() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        }
    }

    public static Result combine(List<SourceInput> inputs, Selection selection) {
        List<Finding> findings = new ArrayList<>();
        if (selection.count() != null && selection.tables() != null && !selection.tables().isEmpty()) {
            throw new IllegalArgumentException("Use either a count or a list of tables, not both.");
        }
        if (selection.count() != null && selection.count() < 1) {
            throw new IllegalArgumentException("The count must be at least 1.");
        }
        List<Map<String, Object>> found = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int files = 0;
        for (SourceInput in : inputs) {
            if (in.readError() != null || in.text() == null) {
                findings.add(Finding.error("COMBINE_FILE_UNREADABLE", in.name(), in.readError() == null ? "The file has no text." : in.readError(),
                        "Fix or remove the file and run the command again."));
                continue;
            }
            Object root;
            try {
                root = Json.parse(in.text());
            } catch (JsonException e) {
                findings.add(Finding.error("COMBINE_FILE_UNREADABLE", in.name(), "The file is not valid JSON: " + e.getMessage(),
                        "Fix the JSON syntax, or leave the file out of the input."));
                continue;
            }
            if (!(root instanceof Map<?, ?> m) || !(m.get("datasets") instanceof List<?> ds) || ds.isEmpty()) {
                findings.add(Finding.warning("COMBINE_FILE_SKIPPED", in.name(), "The file has no \"datasets\" section with entries, so nothing was taken from it.",
                        "Give dataset manifests only (such as the manifest_<table>.json files)."));
                continue;
            }
            files++;
            for (Object k : m.keySet()) {
                if (!"datasets".equals(k)) {
                    findings.add(Finding.warning("COMBINE_FIELD_DROPPED", in.name() + ":" + k, "The top-level field '" + k
                            + "' is not part of the combined manifest, which holds only \"datasets\"; it was left out.",
                            "If it matters, add it to the combined file yourself."));
                }
            }
            for (Object d : ds) {
                if (!(d instanceof Map<?, ?> dm)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> dataset = (Map<String, Object>) dm;
                Object name = dataset.get("name");
                if (name instanceof String s && !names.add(s.toLowerCase(Locale.ROOT))) {
                    findings.add(Finding.warning("COMBINE_DUPLICATE_DATASET", in.name(), "The dataset '" + s + "' is already in the combined manifest; this copy was not added.",
                            "Remove the duplicate from the input, or check that the files are different tables."));
                    continue;
                }
                found.add(dataset);
            }
        }
        int available = found.size();
        List<Map<String, Object>> chosen = new ArrayList<>();
        if (selection.tables() != null && !selection.tables().isEmpty()) {
            Set<String> wanted = new HashSet<>();
            for (String t : selection.tables()) {
                wanted.add(t.trim().toLowerCase(Locale.ROOT));
            }
            Set<String> hit = new HashSet<>();
            for (Map<String, Object> d : found) {
                String n = String.valueOf(d.get("name")).toLowerCase(Locale.ROOT);
                String table = n.substring(n.lastIndexOf('.') + 1);
                if (wanted.contains(n) || wanted.contains(table)) {
                    chosen.add(d);
                    hit.add(wanted.contains(n) ? n : table);
                }
            }
            for (String t : selection.tables()) {
                if (!hit.contains(t.trim().toLowerCase(Locale.ROOT))) {
                    findings.add(Finding.warning("COMBINE_TABLE_NOT_FOUND", t.trim(), "No dataset for table '" + t.trim() + "' was found in the input, so it is not in the combined manifest.",
                            "Check the spelling (matching ignores case; use the table name or schema.table)."));
                }
            }
        } else if (selection.count() != null) {
            chosen.addAll(found.subList(0, Math.min(selection.count(), available)));
        } else {
            chosen.addAll(found);
        }
        if (chosen.isEmpty()) {
            if (findings.stream().noneMatch(f -> f.severity() == Severity.ERROR)) {
                findings.add(Finding.error("COMBINE_NOTHING_SELECTED", "input", "No datasets were selected, so nothing was written.",
                        "Check the input files, and the count or table names."));
            }
            return new Result(null, 0, findings);
        }
        findings.add(Finding.info("COMBINE_SUMMARY", "input", "Read " + available + " dataset(s) from " + files + " file(s); combined "
                + chosen.size() + (selection.count() != null ? " (the first " + selection.count() + ")" : selection.tables() != null && !selection.tables().isEmpty()
                        ? " (the named tables)" : " (all)") + " into one manifest.", "None needed."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("datasets", new ArrayList<Object>(chosen));
        return new Result(Json.write(root) + "\n", chosen.size(), findings);
    }
}
