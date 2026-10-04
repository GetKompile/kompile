# ERP sandbox qualification

Offline fixtures establish connector coverage, not compatibility with every vendor deployment. The opt-in test checks a specific bounded read through named credentials → Camel → local crawl → Markdown → index/search. It is not production synchronization or vendor certification.

## Prepare

- Obtain administrator-approved **nonproduction**, read-only access. Audit the chosen endpoint for side effects, including custom GET handlers. Odoo uses only the fixed read-only `search_read` POST.
- Seed synthetic records outside this runner with stable scalar/composite keys and unique searchable markers. Keep data static throughout the test; use deterministic ordering where the profile permits it.
- Save a connection using `kompile auth source erp`. Credentials are bound to source type, root and tenant. Supply secrets through existing wizard/env/file/stdin mechanisms, not configuration JSON or literal secret command arguments. Bearer tokens are externally provisioned; no acquisition/refresh is performed.
- Keep configuration outside the repository with owner-private permissions. For manual CI, provision the named connection in an isolated user home. Do not share or concurrently test the account.

## Configuration

Example Acumatica configuration; replace these values with your sandbox's actual contract and known records:

```json
{
  "sourceType": "ACUMATICA",
  "productVersion": "24.200.001",
  "properties": {
    "serviceRoot": "https://sandbox.example/entity/Default/24.200.001/",
    "connectionName": "sandbox-reader",
    "entitySet": "Customer",
    "keyFields": "CustomerID",
    "select": "CustomerName",
    "maxRecords": 10,
    "pageSize": 1,
    "maxPages": 20,
    "timeoutMillis": 10000,
    "maxResponseBytes": 1048576
  },
  "expectedRecords": [
    {"keys": {"CustomerID": "QA0001"}, "searchText": "qualification-cobalt-puffin-one"},
    {"keys": {"CustomerID": "QA0002"}, "searchText": "qualification-cobalt-puffin-two"}
  ]
}
```

Only `sourceType`, `productVersion`, `properties`, and `expectedRecords` are accepted. Input is capped at 64 KiB; duplicate JSON fields and trailing values are rejected. `productVersion` is an operator-declared short label, **not remotely verified**.

Allowed properties: `serviceRoot`, `connectionName`, `entitySet`, `tenant`, `select`, `filter`, `orderBy`, `keyFields`, `sapClient`, `maxRecords`, `pageSize`, `maxPages`, `timeoutMillis`, `maxResponseBytes`. No inline credentials, arbitrary pipelines/actions, or channel connections. Non-numeric properties must be strings. Shared [vendor profile restrictions](erp-crawling.md) still apply. Live roots require HTTPS.

Explicit root, connection, entity, keys, record/timeout/byte bounds are required. Qualification-specific ceilings are deliberately smaller than production connector ceilings:

| Limit | Ceiling |
|---|---:|
| maxRecords / pageSize | 100 |
| maxPages | 20 |
| timeoutMillis | 30,000 |
| maxResponseBytes | 1 MiB |

All configured bounds must be positive integers. Paged profiles require explicit page options and more expected records than the configured page size. Allow enough pages to terminate, including Acumatica's empty final page. JD Edwards is a single bounded table read: omit page options and specify at least one known record.

Expectations must have unique keys containing exactly the configured comma-separated `keyFields`, with correctly typed JSON scalar values. For Acumatica use unwrapped values, not `{"value": ...}`. Each `searchText` must appear in that record's generated Markdown. Include marker fields in `select` when selection applies. NetSuite/JD Edwards reject select; Infor requires tenant/select; Odoo/Salesforce require select.

## Run explicitly

From the repository root, with current locally installed dependencies:

```sh
/home/agibsonccc/dev-apps/mvn/bin/mvn -nsu -Dnd4j.backend=nd4j-native \
  -pl :kompile-cli-main -Dtest=ErpSandboxQualificationTest \
  -Derp.qualification.live=true \
  -Derp.qualification.config=/private/path/qualification.json test
```

Without the live flag the test is **skipped, not passed**. Explicit opt-in with missing, unreadable or invalid configuration fails and emits a sanitized receipt. Do not enable the flag in normal CI or across the reactor. Use a manually triggered secrets-backed job with administrator approval and an outer job timeout.

The runner performs three bounded reads: baseline, repeat, and a one-record sample in a separate project so it cannot replace baseline materialization. NetSuite also performs detail GETs. Transport timeouts are not total deadlines. Live execution retains normal subprocess-watchdog policy.

## Evidence and receipts

Receipts are written under the CLI module's `target/erp-qualification/qualification-<UUID>.json`. `PASS_BOUNDED_READ` requires:

1. Named credential/profile validation and a wrong-tenant binding rejected **locally**, without an invalid-password vendor request.
2. A nonempty read within the record cap.
3. Expected typed record keys and marker text in Markdown.
4. Search evidence for the corresponding document/chunk whose returned text contains the marker.
5. Stable source-to-materialized-document identities on repeat.
6. A one-record cap in the separate project.
7. No configured password/token/Basic encoding in inspected text artifacts, search output or the receipt.

Inspection is bounded: 16 MiB per inspected file, 32 Mi characters retained Markdown per snapshot, 10,000 files / 256 MiB aggregate workspace artifacts. Exceeding those limits fails qualification rather than silently skipping checks.

Receipts contain fixed checks, status/failure stage, source type, declared version, timestamps, counts and retrieval mode. No raw exceptions, URLs, account/connection names, record keys or business text are copied. Keep user home, configuration, crawl data and process logs private; only sanitized receipts are intended for sharing. JUnit normally removes temporary crawl projects. Secret scanning is not a general PII, binary-store or external-log audit.

`beyondConfiguredPageSize` compares returned record count to configuration. **`paginationRequestsObserved` remains false**: this runner does not capture vendor HTTP traces. Salesforce's batch-size header is advisory (200–2000), and this small harness cannot force Salesforce paging. Actual paging qualification needs larger administrator-controlled datasets and request logs, outside this bounded test. Offline reader fixtures separately verify continuation behavior.

Search may use `lexical-fallback` rather than hybrid retrieval; the receipt distinguishes them. A pass does not qualify graph extraction, models/native execution, full sync, CDC, writes, every deployment/version, or native/managed-server packaging. Retain deployment/version and administrator-side paging evidence separately before marking a connector live-qualified.

## Routine offline regression

```sh
/home/agibsonccc/dev-apps/mvn/bin/mvn -nsu -Dnd4j.backend=nd4j-native \
  -pl :kompile-cli-main \
  -Dtest=ErpSandboxQualificationRunnerTest,ErpSandboxQualificationTest,ErpConnectionCredentialsTest,ErpCrawlToolTest,ErpCrawlRegistryTest,LocalExternalSourceLoaderRegistryTest test
```

Runner fixtures use loopback HTTP, isolated credentials and synthetic data. They verify real ingestion/search, repeated identities, caps, redaction, malformed configuration, missing expectations, and preservation of prior Markdown after a failed reread. Authentication failures and malformed-response probes belong in offline fixtures, not against live accounts.
