package com.example.casc.wizard;

import java.util.Locale;
import java.util.Set;

/**
 * System key policy (provisional, see docs/DECISION_LOG.md D-034): prefix-slug where the prefix names the kind
 * (svc, elem, intf, ent, xform) and the slug is the lower-case name with every run of characters outside a-z and 0-9
 * replaced by one hyphen. A collision with any existing key in the manifest appends -2, -3 and so on.
 * Keys are generated only on request and never replace a key the user entered.
 */
public final class SystemKeys {
    private SystemKeys() {}

    public static String generate(String prefix, String name, Set<String> existing) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "item";
        }
        String base = prefix + "-" + slug;
        String key = base;
        int n = 2;
        while (existing.contains(key)) {
            key = base + "-" + n++;
        }
        existing.add(key);
        return key;
    }
}
