package com.example.casc.domain;

import java.util.Arrays;
import java.util.Optional;

/** Enum types with exact manifest spellings. Spellings are PROVISIONAL (see docs/DECISION_LOG.md). */
public final class Enums {
    private Enums() {}

    /** Exposes the exact serialized spelling and strict (case-sensitive) lookup. */
    public interface Labeled {
        String label();
    }

    public static <E extends Enum<E> & Labeled> Optional<E> parse(Class<E> type, String text) {
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.label().equals(text)).findFirst();
    }

    public enum InterfaceType implements Labeled {
        SOURCING("SOURCING"), DISTRIBUTION("DISTRIBUTION");
        private final String label;
        InterfaceType(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum TargetTableType implements Labeled {
        TRANSACTION("Transaction"), REFERENCE("Reference");
        private final String label;
        TargetTableType(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Attestation implements Labeled {
        YES("Yes"), NO("No");
        private final String label;
        Attestation(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum DataSubjectCategory implements Labeled {
        CLIENT("Client"), WORKER("Worker"), THIRD_PARTY("Third Party");
        private final String label;
        DataSubjectCategory(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum DatabaseType implements Labeled {
        DB2("DB2"), SYBASE("Sybase"), TERADATA("Teradata"), GREENPLUM("Greenplum"),
        CSV("csv"), XLSX("xlsx"), APACHE_HIVE("Apache Hive"), VDP("VDP"),
        ORACLE("Oracle"), POSTGRESQL("PostgreSQL"), SQL_SERVER("SQL Server"),
        MAINFRAME("Mainframe"), SNOWFLAKE("Snowflake");
        private final String label;
        DatabaseType(String label) { this.label = label; }
        public String label() { return label; }
    }
}
