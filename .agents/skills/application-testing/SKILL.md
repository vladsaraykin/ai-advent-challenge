---
name: application-testing
description: Plan, write, and run automated tests across the finance assistant's Spring backend, React frontend, database, and AI/MCP integrations. Use when adding tests, investigating failures, or validating a user workflow.
---

# Application Testing

Inspect the repository's test frameworks, fixtures, package scripts, and CI configuration before adding or running checks. Prefer the narrowest test that proves the behavior; run broader suites when a change crosses module boundaries. Do not add test dependencies or infrastructure without a concrete need.

## Test layers

- **Unit tests:** cover domain rules, validation, transformations, and service decisions without starting external systems.
- **Integration tests:** verify Spring wiring, persistence, migrations, API contracts, and PostgreSQL queries using the repository's configured test database or container setup.
- **Frontend tests:** verify user-visible React behavior, form validation, loading/error/success states, and API error handling through accessible interactions.
- **End-to-end tests:** reserve for critical workflows spanning the UI and API, such as recording transactions and viewing a report. Keep them few and deterministic.

## Finance invariants

Include cases for decimal precision, rounding, currency handling, timezone and period boundaries, category aggregation, and corrections. Verify transfers between a user's own accounts do not affect income or expense totals. Test that users cannot read or change another user's data, and that retrying the same message does not create duplicate transactions.

## AI, MCP, and RAG

Test tool schemas, argument validation, authorization, result mapping, and error handling independently from the model. Use deterministic fake model/provider responses for ordinary automated tests; tests should not require live API credentials or incur external usage. Verify the model can invoke only approved business tools and that financial facts and totals come from the database. For RAG, test user-scoped retrieval and document filtering separately from answer generation.

## Running and reporting checks

Use the commands documented by the project (for example, Maven test goals and npm scripts), and report exactly which checks ran and whether they passed. When a check fails, preserve the useful error output and distinguish a product defect from unavailable infrastructure or external credentials. Avoid weakening assertions or skipping tests solely to get a green run.
