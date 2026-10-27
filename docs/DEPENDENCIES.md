# Dependency Inventory (Phase 1)

Approval and internal availability are UNKNOWN until supplied. Nothing here is a vulnerability scan.

| Item | Coordinates | Version | Purpose | Required | Transitives | Availability | Approval |
|------|-------------|---------|---------|----------|-------------|--------------|----------|
| JDK | n/a | 17+ (provisional) | Runtime/compile | Yes | n/a | Unknown | Unknown |
| Gradle | n/a | 8.x (provisional) | Build | Yes | n/a | Unknown | Unknown |
| Spring Boot plugin | org.springframework.boot | 3.3.5 | bootJar | Yes | Plugin classpath | Unknown | Unknown |
| Dependency mgmt plugin | io.spring.dependency-management | 1.1.6 | BOM versions | Yes | none notable | Unknown | Unknown |
| starter-web | org.springframework.boot | BOM | REST, Jackson JSON, logging | Yes | Large (Tomcat, Jackson, Spring) | Unknown | Unknown |
| starter-validation | org.springframework.boot | BOM | Input validation | Yes | Hibernate Validator | Unknown | Unknown |
| starter-thymeleaf | org.springframework.boot | BOM | Server-rendered wizard | Optional-ish (fallback: static HTML) | Thymeleaf | Unknown | Unknown |
| starter-test | org.springframework.boot | BOM | Tests only | Dev only | JUnit, Mockito, AssertJ | Unknown | Unknown |

Licenses need organizational review. Later optional libraries (not in graph yet): Jackson YAML, Commons CSV, POI, PDFBox, JSqlParser, JSoup.
