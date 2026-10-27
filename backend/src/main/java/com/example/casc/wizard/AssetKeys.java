package com.example.casc.wizard;

import com.example.casc.ingest.RawMetadataBundle.RawInterface;
import com.example.casc.ingest.RawMetadataBundle.RawTable;
import java.util.Locale;

/** Stable keys that tie a decision to the table or interface it was made for. */
public final class AssetKeys {
    private AssetKeys() {}

    public static String table(RawTable t) {
        return "T|" + t.source() + "|" + (t.schema() == null ? "" : t.schema()) + "|" + t.name() + "|" + t.location();
    }

    public static String iface(RawInterface i) {
        return "I|" + i.source() + "|" + i.name() + "|" + i.location();
    }

    public static String column(String assetKey, String columnName) {
        return assetKey + "#" + columnName;
    }

    /** Case-insensitive schema.name, used to find the same table defined twice. */
    public static String qualified(RawTable t) {
        return ((t.schema() == null ? "" : t.schema() + ".") + t.name()).toLowerCase(Locale.ROOT);
    }
}
