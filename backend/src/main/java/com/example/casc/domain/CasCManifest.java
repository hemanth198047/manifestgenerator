package com.example.casc.domain;

import com.example.casc.domain.Enums.*;
import java.util.List;

/**
 * Typed manifest model. Optional values are null; empty lists mean "present but empty".
 * isDeployedOnPrem is a boxed Boolean so "missing" and "false" stay distinct.
 * systemEonId / system name are intentionally NOT here: official placement is unconfirmed.
 */
public record CasCManifest(
        List<LogicalService> logicalServices,
        List<SystemDataElement> systemDataElements,
        List<Interface> interfaces,
        List<DataEntity> dataEntities,
        List<Database> databases,
        List<Transformation> transformations) {

    public record LogicalService(String name, Boolean isDeployedOnPrem, String cloudProvider, String systemKey) {}

    public record SystemDataElement(String name, List<String> logicalServices, String systemKey,
                                    String description, String dgcDataConcept, String dgcDataDomain) {}

    public record PiiElement(String name) {}

    public record Interface(String name, InterfaceType type, List<String> logicalServices,
                            String systemKey, List<InterfaceDataElement> interfaceDataElements) {}

    public record InterfaceDataElement(String physicalName, String systemDataElement, String systemKey,
                                       String description, Attestation sensitiveDataAttestation,
                                       List<PiiElement> piiElements,
                                       List<DataSubjectCategory> dataSubjectCategories) {}

    public record EntityElementRef(String name) {}

    public record DataEntity(String name, List<String> logicalServices, String systemKey,
                             String description, List<EntityElementRef> systemDataElements) {}

    public record Database(String logicalName, DatabaseType databaseType, String systemKey, List<Schema> schemas) {}

    public record Schema(String physicalName, String systemKey, List<Table> tables) {}

    public record Table(String physicalName, TargetTableType targetTableType, String systemKey,
                        String description, List<Column> columns) {}

    /**
     * sourcedFromColumns shape is UNVERIFIED (docs/DECISION_LOG.md D-007); carried as opaque maps
     * and never synthesized until the official schema is available.
     */
    public record Column(String physicalName, String systemDataElement, String systemKey, String description,
                         Attestation sensitiveDataAttestation, List<PiiElement> piiElements,
                         List<DataSubjectCategory> dataSubjectCategories,
                         List<java.util.Map<String, Object>> sourcedFromColumns) {}

    public record Transformation(String name, String systemKey, String logicalDescription,
                                 List<String> sourceDataElements, List<String> targetDataElements) {}
}
