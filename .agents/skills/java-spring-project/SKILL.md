---
name: java-spring-project
description: Design, implement, refactor, or test Java 21 Spring Boot backends in Maven projects. Use for controllers, application services, OpenAI integrations, configuration, persistence, and backend tests; do not use for frontend-only work.
---

# Java Spring Project

Inspect `pom.xml`, the existing package layout, configuration, and tests before choosing an implementation shape.

Keep transport, application orchestration, domain values, and external API integration separated. Controllers validate and map HTTP concerns; services own use cases; clients isolate provider-specific request and response formats.

Use Java 21 language features only when they improve clarity. Prefer immutable records for request and response values, constructor injection, explicit timeouts, and configuration properties over scattered environment lookups.

Preserve provider compatibility. For OpenAI models, select supported request parameters per model family and surface upstream errors without exposing credentials or secrets.

Make concurrency bounded and observable. Preserve result ordering when requests execute concurrently, handle partial failures explicitly, and report duration and token usage from measured/provider data rather than estimates.

Test behavior at the narrowest useful level. Cover validation, orchestration, failure mapping, ordering/concurrency invariants, and provider payload compatibility. Run the relevant Maven tests and then the full suite when the change is broad.

Avoid unrelated dependency or build changes. Keep generated frontend assets outside Java source and configure Spring to serve them through an explicit build integration.
