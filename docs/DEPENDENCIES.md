# Dependency Inventory (Phase 2)

Approval status and internal availability are UNKNOWN until you supply them. Nothing here is a vulnerability scan.

| Item | Version | Purpose | Required | Transitives | Availability | Approval |
|------|---------|---------|----------|-------------|--------------|----------|
| JDK (with module `jdk.httpserver`) | 17 or newer | Compile and run | Yes | n/a | Unknown | Unknown |
| Gradle | 8.x (provisional) | Build | Yes | n/a | Unknown | Unknown |
| Gradle `java` plugin | built in | Compile, jar | Yes | none | Part of Gradle | Follows Gradle |

There are no libraries, no third-party Gradle plugins, no annotation processors, no native code and no repositories.
`settings.gradle` and `build.gradle` configure none. Dependency locking and verification metadata are therefore not needed;
if libraries are added later, enable Gradle's built-in locking and verification at that point.

Features that would need a library (not enabled): XLSX/DOCX (Apache POI), PDF (Apache PDFBox), YAML parsing (for example Jackson YAML),
HTML (JSoup), general SQL parsing (JSqlParser), official schema validation (a validator library), JavaFX (OpenJFX).
