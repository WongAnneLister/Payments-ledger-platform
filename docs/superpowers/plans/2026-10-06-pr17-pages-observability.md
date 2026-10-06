# PR17 pages and observability implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development with fresh implementers and independent spec then quality review. Parent alone runs Maven serially, owns docs/evidence/commit and browser smoke. Steps use checkbox tracking.

**Goal:** Deliver trustworthy customer/operations pages and scoped observability through existing APIs.
**Architecture:** Same-origin static page modules consume PR16 and /v1 APIs; a read-only scoped observation endpoint and bounded Micrometer gauges; allowlisted correlation log events. No money-rule change or new migration is planned.
**Tech Stack:** Java21/Spring/JDBC/PostgreSQL/Micrometer, native browser JavaScript/HTML/CSS, Node built-in tests, Testcontainers.

## Task 1 — customer and operations UI
Files: create static/payment-result.html/js, static/ops-console.html/js/css and focused shared page modules; modify static/payment-test.html/js only for safe result navigation/state; create scripts/tests PR17 Node cases and test/pages/PageHttpIT.java; update CI Node test step. Preserve Maven dependency footprint.
- [x] Verify clean baseline AuthenticationFilterTest + OpsApiIT/OpsSchemaIT/PaymentHttpIT in the isolated fc09cb2 worktree.
- [x] Write Node and HTTP regressions before implementation: result status follows local resource instead of URL success; UNKNOWN lacks replacement-pay action; SUCCEEDED+PENDING_EVIDENCE separates payment/financial truth; large int64 displays exact decimal; old response cannot replace newer request/changed identity; malicious text stays inert; retry preserves key/body; partner sees only own shares. Node tests import only intended module APIs; absent-module/import errors are bootstrap limitations, require observable behavior assertion RED after minimal module scaffolding if necessary.
- [x] Parent observes RED using node --test scripts/tests/pr17-*.test.* and JAVA_HOME=<21> ./mvnw -B -Dtest=AuthenticationFilterTest -Dit.test=PageHttpIT verify. Freeze until expected failures are recorded.
- [x] Build responsive console and separate customer result with navigation for all frozen pages, current API roles, controlled errors, exact amounts, status/trace/INCOMPLETE sources and safe drilldowns. Credentials are memory-only. No direct DB access.
- [x] Wire all 12 PR16 reads and 6 writes through one trusted same-origin client. Exact pending command key/body remain stable; after replay show historical-command warning and fetch current GET. Role transitions clear data and prevent stale writes; reviewer must inspect draft independently.
- [x] Add CI Node built-in test invocation using an explicit available Node LTS setup. Node is a test tool, not new production runtime. Parent runs focused GREEN then spec/quality review; fixes get RED evidence before changes.

## Task 2 — scoped metrics and structured logs
Files: create observability service/controller and metrics/log support under ops or observability; test/ops/ObservabilityIT.java and test/observability/*Test.java; modify supported config only as necessary; extend docs/contracts/openapi.yaml.
- [x] Write real PostgreSQL/HTTP RED with two orgs/envs/currencies, unknown/reservation/webhook/outbox/BT/payout/failed-check fixtures, exact clock ages and numeric semantics. Expected 401/403/404 roles, no writes, no public actuator finance exposure, error != zero success.
- [x] Write log RED asserting JSON correlation identifiers and explicit secret/client-secret/card/bank token injections excluded; bounded gauge names/tags and empty-report semantics are independently specified expected values.
- [x] Parent runs focused RED, then authorize production only after expected assertion failures. Service uses OpsContext plus reusable read RBAC and read-only SQL; no global values in scope response. Per-currency residual reports preserve completeness/version/time.
- [x] Bind named persistent gauges, structured allowlist logging, documented explicit alert thresholds and dashboard view. Include association IDs only when known, no raw request/body/exception. Keep actuator public exposure health-only and worker no pages/ops endpoint.
- [x] Parent focused GREEN; independent spec then quality review and regression repair.

## Task 3 — actual browser and delivery
Files: docs/runbook/pages-observability.md, docs/evidence/pr17/, README.md, product roadmap and plan checkboxes.
- [x] Start an isolated fixture API safely for actual browser smoke; never read .env into pages or invoke real provider funding writes. Capture customer UNKNOWN plus ops trace/metrics and verify role denial, XSS text, stale refresh, display semantics and keyboard/responsive behavior.
- [x] Record page/API coverage, fixture screenshot provenance, correlation/redaction and alert samples, AC-V1-15/32 mapping and honest NOT_RUN limits. Browser/client tests supplement real HTTP/PG tests.
- [x] Capture source/build inputs, run all Node tests and fresh Java21 clean verify with command-scoped caffeinate, confirm hashes unchanged. Validate OpenAPI/doclinks/diff/configured-secret scan.
- [x] Check staged hashes against tested inputs; local PR17 commit. Keep main/remote and unrelated untracked content unchanged until authorized.
