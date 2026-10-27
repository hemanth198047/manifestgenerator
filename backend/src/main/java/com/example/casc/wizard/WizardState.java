package com.example.casc.wizard;

import com.example.casc.domain.Enums.Attestation;
import com.example.casc.domain.Enums.DataSubjectCategory;
import com.example.casc.domain.Enums.DatabaseType;
import com.example.casc.domain.Enums.InterfaceType;
import com.example.casc.domain.Enums.TargetTableType;
import com.example.casc.ingest.RawMetadataBundle;
import com.example.casc.ingest.SourceInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the user has entered in the five steps. Held in memory for one browser session and optionally saved as
 * a project file; never written to disk by the server. Not thread safe: callers synchronize on the instance.
 * Empty strings mean "not set". The system EON ID and name stay here and are never written into the manifest.
 */
public final class WizardState {

    public static final class SystemConfig {
        public String eonId = "";
        public String name = "";
        public String databaseLogicalName = "";
        public DatabaseType databaseType;
        public String defaultSchema = "";
    }

    public static final class Service {
        public String name = "";
        public Boolean onPrem;
        public String cloudProvider = "";
        public String systemKey = "";
    }

    public static final class Element {
        public String name = "";
        public String description = "";
        public String concept = "";
        public String domain = "";
        public String systemKey = "";
        public List<String> services = new ArrayList<>();
    }

    /** Decision for one table or interface, keyed by AssetKeys. */
    public static final class Asset {
        public boolean included = true;
        public TargetTableType tableType;
        public InterfaceType direction;
        public String systemKey = "";
        public List<String> services = new ArrayList<>();
        public Set<String> excludedFields = new LinkedHashSet<>();
    }

    /** Governance entered for one column or interface field. */
    public static final class Gov {
        public String description = "";
        public String element = "";
        public Attestation attestation;
        public List<String> pii = new ArrayList<>();
        public List<DataSubjectCategory> categories = new ArrayList<>();
        /** Raw JSON for sourcedFromColumns, passed through unverified (its official shape is not confirmed). */
        public String lineageJson = "";

        public boolean isEmpty() {
            return description.isBlank() && element.isBlank() && attestation == null && pii.isEmpty()
                    && categories.isEmpty() && lineageJson.isBlank();
        }
    }

    public static final class Entity {
        public String name = "";
        public String description = "";
        public String systemKey = "";
        public List<String> services = new ArrayList<>();
        public List<String> elements = new ArrayList<>();
    }

    public static final class Transform {
        public String name = "";
        public String systemKey = "";
        public String description = "";
        public List<String> sources = new ArrayList<>();
        public List<String> targets = new ArrayList<>();
    }

    public final List<SourceInput> sources = new ArrayList<>();
    /** Sources that could not be fetched in the last step 1 submission; shown as problems, not kept as sources. */
    public final List<SourceInput> failedSources = new ArrayList<>();
    public final Map<String, String> options = new LinkedHashMap<>();
    public final SystemConfig system = new SystemConfig();
    public final List<Service> services = new ArrayList<>();
    public final Map<String, Asset> assets = new LinkedHashMap<>();
    public final List<Element> elements = new ArrayList<>();
    public final Map<String, Gov> columns = new LinkedHashMap<>();
    public final Map<String, Gov> fields = new LinkedHashMap<>();
    public final List<Entity> entities = new ArrayList<>();
    public final List<Transform> transformations = new ArrayList<>();
    public final Set<String> acknowledged = new LinkedHashSet<>();
    public String csrf = "";
    public String flash = "";
    /** Derived from sources and options; recomputed by WizardService. */
    public RawMetadataBundle bundle;

    /** Replaces everything except the CSRF token with the content of another state (used to load or reset a project). */
    public void copyFrom(WizardState o) {
        sources.clear();
        sources.addAll(o.sources);
        failedSources.clear();
        failedSources.addAll(o.failedSources);
        options.clear();
        options.putAll(o.options);
        system.eonId = o.system.eonId;
        system.name = o.system.name;
        system.databaseLogicalName = o.system.databaseLogicalName;
        system.databaseType = o.system.databaseType;
        system.defaultSchema = o.system.defaultSchema;
        services.clear();
        services.addAll(o.services);
        assets.clear();
        assets.putAll(o.assets);
        elements.clear();
        elements.addAll(o.elements);
        columns.clear();
        columns.putAll(o.columns);
        fields.clear();
        fields.putAll(o.fields);
        entities.clear();
        entities.addAll(o.entities);
        transformations.clear();
        transformations.addAll(o.transformations);
        acknowledged.clear();
        acknowledged.addAll(o.acknowledged);
        bundle = o.bundle;
    }

    public Asset asset(String key) {
        return assets.computeIfAbsent(key, k -> new Asset());
    }

    public Gov column(String key) {
        return columns.computeIfAbsent(key, k -> new Gov());
    }

    public Gov field(String key) {
        return fields.computeIfAbsent(key, k -> new Gov());
    }

    public List<String> serviceNames() {
        List<String> out = new ArrayList<>();
        for (Service s : services) {
            if (!s.name.isBlank()) {
                out.add(s.name);
            }
        }
        return out;
    }

    public List<String> elementNames() {
        List<String> out = new ArrayList<>();
        for (Element e : elements) {
            if (!e.name.isBlank()) {
                out.add(e.name);
            }
        }
        return out;
    }

    /** Removes empty governance entries so the state and project files stay small. */
    public void compact() {
        columns.values().removeIf(Gov::isEmpty);
        fields.values().removeIf(Gov::isEmpty);
    }
}
