package com.example.casc.profile;

import java.util.List;

/**
 * Names the rule set in use. Shown in UI/reports only; never written into manifests.
 * Status values follow the three-level distinction: LOCAL, OFFICIAL_SCHEMA, FDC_ACCEPTED.
 */
public record SchemaProfile(String name, String version, boolean confirmedAgainstOfficialSpec, List<String> notes) {

    public static SchemaProfile provisional() {
        return new SchemaProfile(
                "provisional-casc-v0", "0.1", false,
                List.of("Derived from the project prompt, not the official CasC Technical Reference.",
                        "Validation level reached: LOCAL only.",
                        "See docs/DECISION_LOG.md for unverified rules."));
    }
}
