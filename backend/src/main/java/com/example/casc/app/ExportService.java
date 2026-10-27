package com.example.casc.app;

import com.example.casc.domain.CasCManifest;
import com.example.casc.json.Json;
import com.example.casc.json.JsonException;
import com.example.casc.manifest.ManifestMapper;
import com.example.casc.manifest.ManifestMapper.ReadResult;
import com.example.casc.output.JsonManifestSerializer;
import com.example.casc.output.ManifestSerializer;
import com.example.casc.output.YamlManifestSerializer;
import com.example.casc.profile.SchemaProfile;
import com.example.casc.validation.CasCValidatorService;
import com.example.casc.validation.Finding;
import com.example.casc.validation.ValidationReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared application service used by the web UI, REST API and CLI. */
public final class ExportService {
    /** Validation level reached by this build. Official-schema and FDC levels are not available. */
    public static final String LEVEL = "LOCAL";

    private final Map<String, ManifestSerializer> serializers = new LinkedHashMap<>();
    private final CasCValidatorService validator = new CasCValidatorService();

    public ExportService(List<ManifestSerializer> list) {
        for (ManifestSerializer s : list) {
            serializers.put(s.format(), s);
        }
    }

    public static ExportService standard() {
        return new ExportService(List.of(new JsonManifestSerializer(), new YamlManifestSerializer()));
    }

    public List<String> formats() {
        return new ArrayList<>(serializers.keySet());
    }

    public ReadResult read(String jsonText) throws JsonException {
        return ManifestMapper.fromJson(jsonText);
    }

    /** Reader findings plus validator findings. */
    public ValidationReport validate(ReadResult in, List<String> unresolvedDecisions) {
        List<Finding> all = new ArrayList<>(in.findings());
        all.addAll(validator.validate(in.manifest(), unresolvedDecisions).findings());
        return new ValidationReport(all);
    }

    public ExportResult export(ReadResult in, List<String> unresolvedDecisions, String format)
            throws CapabilityUnavailableException {
        String id = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        ManifestSerializer s = serializers.get(id);
        if (s == null) {
            throw new CapabilityUnavailableException("Output format '" + format + "' is not available. Available: "
                    + String.join(", ", formats()) + ".");
        }
        CasCManifest manifest = in.manifest();
        String content = s.serialize(manifest);
        List<Finding> all = new ArrayList<>(validate(in, unresolvedDecisions).findings());
        all.addAll(roundTrip(s, content));
        ValidationReport report = new ValidationReport(all);
        return new ExportResult(s.format(), content, report, !report.hasErrors(), LEVEL,
                SchemaProfile.provisional(), s.fileExtension(), s.contentType());
    }

    /** Serialize, parse back, serialize again; the two outputs must be identical. JSON only. */
    private List<Finding> roundTrip(ManifestSerializer s, String content) {
        if (!"json".equals(s.format())) {
            return List.of(Finding.info("ROUNDTRIP_UNAVAILABLE", "$",
                    "Round-trip check is not available for " + s.format() + " (no approved parser).",
                    "Check the output with an approved YAML tool, or export JSON."));
        }
        try {
            String again = s.serialize(ManifestMapper.fromTree(Json.parse(content)).manifest());
            if (!again.equals(content)) {
                return List.of(Finding.error("ROUNDTRIP_MISMATCH", "$",
                        "Re-reading the generated JSON produced different output.",
                        "Report this as a defect; the manifest was not changed."));
            }
        } catch (JsonException e) {
            return List.of(Finding.error("ROUNDTRIP_MISMATCH", "$",
                    "Generated JSON could not be read back: " + e.getMessage(), "Report this as a defect."));
        }
        return List.of();
    }
}
