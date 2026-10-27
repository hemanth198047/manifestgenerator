# Manual Recreation Handbook

## Phase 1 file order
1. settings.gradle (~15 lines) 2. gradle.properties (~5) 3. build.gradle (~50) 4. .gitignore 5. .gitattributes
6. src/main/resources/application.properties 7. CascGeneratorApplication.java 8. profile/SchemaProfile.java
9. domain/Enums.java 10. domain/CasCManifest.java 11. test EnumsTest.java 12. docs/*

Type each file in 30-60 line chunks. Do not compile until a file is complete.

Generate the wrapper with your approved Gradle (not typed):
- Linux: `gradle wrapper --gradle-version <approved>`
- Windows: `gradle.bat wrapper --gradle-version <approved>`
Edit gradle/wrapper/gradle-wrapper.properties to point distributionUrl at your internal mirror.

Checkpoint: `./gradlew test` (Windows: `gradlew.bat test`) runs EnumsTest, 2 tests. Not yet executed by the author.
Recovery: wrong JAVA_HOME -> set it to an approved JDK 17+; plugin resolution failure -> check internalPluginRepoUrl and REPO_USER/REPO_PASSWORD env vars.
