# ERP crawling from CLI chat

CLI chat/MCP `crawl_documents` supports eleven read-only source profiles through the
existing **folder-local** asynchronous crawl pipeline (no application server):

- `SAP_NETWEAVER`: SAP Gateway OData V2 JSON (`d.results`, `d.__next`).
- `ODATA`: OData V4 JSON (`value`, `@odata.nextLink`).
- `DYNAMICS365`: Finance & Operations OData V4, **not Dataverse**.
- `NETSUITE`: REST record collection plus individually fetched record details.
- `ODOO`: Odoo **19** JSON-2 `search_read`, not legacy XML-RPC/JSON-RPC.
- `SALESFORCE`: REST query/queryMore with a generated, read-only SOQL query.
- `ORACLE_FUSION`: Fusion Cloud Financials REST collections (`items`, `hasMore`).
- `ORACLE_EBS`: E-Business Suite 12.2 ISG deployed open-interface table/view GETs.
- `JD_EDWARDS`: EnterpriseOne AIS v2 Simple Table GET, one bounded sample.
- `INFOR_MONGOOSE`: Mongoose REST v2 IDO LoadCollection through ION.
- `ACUMATICA`: Contract-based REST endpoint collection GETs with wrapped fields.

All use Apache Camel HTTP **4.4.0**. These are typed HTTP collection readers, not
SAP RFC/BAPI, generic Camel endpoint/route execution, or dedicated vendor SDKs.
The tagged NetWeaver producer does not provide the bounded pagination/failure
handling needed here; Olingo is not required. OData-exposing ERP services can use
the ODATA profile, but individual ERP vendors are **not live-certified**.

## Configure credentials locally

Run `kompile auth source erp` without arguments for the credential wizard, or:

```sh
kompile auth source erp --name finance --source-type ODATA \
  --service-root https://erp.example/odata/ --tenant finance \
  --secret-from-env ERP_READ_TOKEN

kompile auth source erp --name sap-reader --source-type SAP_NETWEAVER \
  --service-root https://sap.example/sap/opu/odata/sap/ZORDERS_SRV/ \
  --username reporting --secret-file /private/path/sap-password

kompile auth source erp list
kompile auth source erp status --source-type ODATA --name finance
kompile auth source erp remove --source-type ODATA --name finance --yes
```

`--secret-stdin` is also supported. There is no literal password/token command-line
option. SAP and EBS setup require basic credentials. ODATA, Fusion and JD Edwards
accept basic credentials (with `--username`) or an externally provisioned bearer
token (without it; JDE requires an AIS-compatible JWT).
Dynamics, NetSuite, Odoo, Salesforce, Infor Mongoose and Acumatica require externally provisioned bearer tokens/API keys;
`--username` is rejected. NetSuite token-based authentication signing is not
implemented. For Odoo, `--tenant` optionally supplies `X-Odoo-Database`.
For Infor Mongoose, `--tenant` is required and supplies `X-Infor-MongooseConfig`,
the application-server configuration name; the token must be valid through ION.
Bearer expiry can be recorded with `--expires-at` (epoch milliseconds); default
0 means externally managed expiry. No token acquisition or refresh is performed.

Secrets live in the existing owner-private credential store. Named connections
are bound to source type, normalized service root, tenant and username/auth mode;
request overrides fail closed. They do not use channel connections or ambient
Microsoft/Google OAuth credentials. Do not paste credentials into chat/tool calls.

## Crawl from chat

Ask chat to invoke `crawl_documents` with:

```json
{
  "documents": [{
    "sourceType": "ODATA",
    "url": "https://erp.example/odata/",
    "maxDocuments": 200,
    "properties": {
      "connectionName": "finance",
      "entitySet": "Orders",
      "select": "OrderId,Customer,Total",
      "filter": "Status eq 'Open'",
      "orderBy": "OrderId",
      "keyFields": "OrderId",
      "maxRecords": 200,
      "pageSize": 50,
      "maxPages": 10
    }
  }]
}
```

Use `SAP_NETWEAVER` and optionally `sapClient` (three digits) for SAP V2.
The service root may instead be supplied as `properties.serviceRoot`; explicitly
include it even with a named connection so snapshot scope is known before loading.
`entitySet` must be a single identifier, not a resource path, function or action
(Odoo also accepts dotted model identifiers, such as `res.partner`).
Poll the returned job id through `crawl_control status`, then read `crawl_result`.
Normal extraction, lexical/vector indexing and graph learning remain pipeline
choices; ERP records become Markdown documents with stable record provenance.

### Vendor profile configuration

Use the same credential command and crawl envelope above, changing `sourceType`,
service root, `entitySet` and properties as follows:

| Profile | Required service-root suffix | Collection/projection |
|---|---|---|
| DYNAMICS365 | `/data/` | F&O entity, e.g. `CustomersV3`; OData `filter`, `select`, `orderBy` and composite `keyFields` supported |
| NETSUITE | `/services/rest/record/v1/` | Record type, e.g. `customer`; no `filter`, `select` or `orderBy` |
| ODOO | `/json/2/` | Model, e.g. `res.partner`; required `select`, e.g. `name,email` |
| SALESFORCE | `/services/data/v60.0/` (your API version) | Object, e.g. `Account`; required `select`, e.g. `Name,Industry` |
| ORACLE_FUSION | `/fscmRestApi/resources/11.13.18.05/` (your resource version) | Collection, e.g. `invoices`; required `keyFields`, e.g. `InvoiceId`; optional simple `select` and `orderBy` |
| ORACLE_EBS | `/webservices/rest/<deployed_alias>/` | Uppercase interface table/view, e.g. `RA_INTERFACE_LINES_ALL`; required `keyFields`, e.g. `INTERFACE_LINE_ID`; optional simple `select` |
| JD_EDWARDS | `/jderest/v2/dataservice/table/` | Table, e.g. `F0101`; required row-alias `keyFields`, e.g. `F0101_AN8`; `maxRecords` sets `$limit` |
| INFOR_MONGOOSE | `/IDORequestService/ido/` (including ION prefixes) | IDO, e.g. `UserNames`; required `tenant`, `select` and `keyFields`; no `filter` or `orderBy` |
| ACUMATICA | `/entity/<endpoint>/<version>/`, e.g. `/entity/Default/24.200.001/` | Top-level entity, e.g. `Customer`; required business `keyFields`, e.g. `CustomerID`; optional simple `select` |

Odoo reads with fixed `domain: []`, `order: "id asc"`, and bounded limit/offset;
`id` is automatically projected. JSON-2 API access requires the appropriate Odoo
plan/permissions. Salesforce automatically projects `Id` and generates
`SELECT Id,... FROM <object> ORDER BY Id LIMIT <maxRecords>`; continuation uses
queryMore. Neither profile accepts custom domains/SOQL, filters, ordering or
arbitrary methods. NetSuite computes paging offsets and detail URLs itself,
ignoring server-supplied record links; SuiteQL is not supported. Each fetched
NetSuite row costs one additional bounded detail request.

### Acumatica scope and prerequisites

Use an externally provisioned OAuth bearer token with least-privilege access to
an enabled contract-based endpoint and its read entities. The token/account
chooses company scope; optional `tenant` only binds the local credential and
snapshot identity, not a company-selection header. No login/logout session,
OAuth acquisition/refresh, cookies or basic authentication is implemented.

The reader issues only GET `<serviceRoot>/<entitySet>` with `$top` and `$skip`.
It requires a JSON array of entities and explicit comma-separated business
`keyFields`, whose values use Acumatica's `{"value":...}` field wrappers.
Session `id`, `rowNumber`, attachment `files` and `_links` are not used as keys
or persisted. Optional `select` maps to `$select` and automatically includes the
business keys. Filters, ordering, child expansion, custom fields, attachments,
entity actions and writes are not exposed.

Offsets advance by returned row count, and `$top` shrinks to the remaining sample
budget. Short, server-clamped pages continue until an empty page or `maxRecords`;
allow an extra page to establish exhaustion. Page-budget exhaustion, duplicate
business keys, invalid wrapped keys, oversized pages, or record/field errors fail
without publishing partial results. Server links are never followed.
Offset paging is not a consistent snapshot: concurrent changes may omit records,
and duplicate detection cannot prove absence of omissions. Administrators must
audit custom endpoint logic; GET alone cannot certify server-side behavior.

```sh
kompile auth source erp --name acumatica-reader --source-type ACUMATICA \
  --service-root https://erp.example/entity/Default/24.200.001/ \
  --secret-from-env ACUMATICA_READ_TOKEN
```

```json
{"documents":[{"sourceType":"ACUMATICA",
 "url":"https://erp.example/entity/Default/24.200.001/",
 "properties":{"connectionName":"acumatica-reader","entitySet":"Customer",
 "keyFields":"CustomerID","select":"CustomerName","maxRecords":100,"pageSize":50}}]}
```

Verified primary contract: Acumatica's [official REST client](https://github.com/Acumatica/AcumaticaRESTAPIClientForCSharp/tree/6.0),
including [GetListAsync](https://github.com/Acumatica/AcumaticaRESTAPIClientForCSharp/blob/6.0/Acumatica.RESTClient.ContractBasedApi/ApiClientExtensions.cs),
[query parameter composition](https://github.com/Acumatica/AcumaticaRESTAPIClientForCSharp/blob/6.0/Acumatica.RESTClient/Auxiliary/ApiClientHelpers.cs),
[bearer request authorization](https://github.com/Acumatica/AcumaticaRESTAPIClientForCSharp/blob/6.0/Acumatica.RESTClient/Client/ApiClient.cs),
and [value/error field wrappers](https://github.com/Acumatica/AcumaticaRESTAPIClientForCSharp/blob/6.0/Acumatica.RESTClient.ContractBasedApi/Model/FieldTypes/RestValueBase.cs).
Endpoint names, versions and available entities are deployment-specific; this
is fixture-tested contract coverage, not live-account qualification.

### Infor Mongoose scope and prerequisites

This profile targets **REST v2 IDO LoadCollection through ION**, not LN/M3 APIs or
direct Mongoose security-token authentication. Supply the deployment's exact ION
root (including tenant/suite prefixes), ending `/IDORequestService/ido/`, and an
externally provisioned ION OAuth2 bearer token. `tenant` is the required
`X-Infor-MongooseConfig` application-server configuration and is credential-bound.

`entitySet` names a permitted IDO; explicit simple comma-separated `select` and
`keyFields` are required. Keys are automatically included in `properties`; paging
uses fixed key ordering and `loadtype=FIRST`, then `NEXT` with an opaque, encoded
`Bookmark`. `recordcap` is always positive and shrinks to the remaining sample
budget (Infor's `recordcap=0` is unbounded and is never sent). The reader requires
`Success=true`, object `Items`, boolean `MoreRowsExist` and a bounded nonempty
bookmark when more rows exist. Repeated pages/keys and page-budget exhaustion fail.

Every request is GET with `readonly=true`. There is no custom load method (`clm`),
per-row method (`pqc`), filter, custom ordering, method invocation or write API.
Administrators must grant a least-privilege reporting account access to audited
read-only IDOs; client flags cannot certify custom server-side IDO logic.

```json
{"documents":[{"sourceType":"INFOR_MONGOOSE",
 "url":"https://ion.example/TENANT/CSI/IDORequestService/ido/",
 "properties":{"connectionName":"infor-reader","tenant":"site-config",
 "entitySet":"UserNames","keyFields":"UserId","select":"Username,UserDesc",
 "maxRecords":100,"pageSize":50}}]}
```

Contract: [Infor Mongoose REST v2 LoadCollection](https://docs.infor.com/mg/2026.x/en-us/mongooseolh/mgiiea/dwn1576796415221.html).

### Oracle deployment prerequisites and scope

Fusion reads `limit`/`offset` pages with `onlyData=true`, validates returned offset,
limit and `hasMore`, and computes the next offset locally. Server-clamped page
sizes are accepted; a non-progressing page fails. Optional `select` maps to
`fields` and automatically includes all key fields. `orderBy` accepts only simple
comma-separated `field[:asc|:desc]` identifiers and defaults to `keyFields`.
No `q` expressions, child expansions, finders or actions are exposed. The Fusion
account needs the relevant service/data read privileges and enabled features.

EBS requires an administrator-deployed ISG **open interface** table/view with GET
and Basic authentication enabled and a narrowly granted reporting user. Copy the
service alias from its deployed WADL; this is not an arbitrary PL/SQL or concurrent
program endpoint. The loader reads `OutputParameters.Summary` and
`Result.Output.<TABLE>_REC` (singleton or repeated records), validates
Offset/Limit/GetCount/TotalCount and computes pagination. It does not accept
custom filters, sorting, RESTHeader/context overrides or EBS session cookies.
Services requiring explicit responsibility/organization query context beyond
the user's deployed default are not supported by this profile.

JD Edwards requires AIS v2 Simple Table GET access and table/data security for
the reporting user. It sends one `$limit=maxRecords` request and parses the
standard `fs_DATABROWSE_<TABLE>.data.gridData.rowset` response. Record identities
come from configured row aliases, never AIS stack/state/session IDs. Nonempty
AIS/system errors, malformed summaries, oversized results, or `moreRecords=true`
with fewer than the requested sample fail closed. `moreRecords=true` at the
sample cap is intentional. No cursor/session management, table filters,
projection, sorting, forms, orchestrations or transactional requests are exposed;
explicit `pageSize`/`maxPages` are rejected. This is **not a full-table sync**.

Example Oracle sources (configure the named connections using matching roots):

```json
{"documents":[
  {"sourceType":"ORACLE_FUSION","url":"https://fusion.example/fscmRestApi/resources/11.13.18.05/",
   "properties":{"connectionName":"fusion-reader","entitySet":"invoices","keyFields":"InvoiceId","select":"InvoiceNumber,InvoiceAmount","maxRecords":200,"pageSize":50}},
  {"sourceType":"ORACLE_EBS","url":"https://ebs.example/webservices/rest/autoinvoice/",
   "properties":{"connectionName":"ebs-reader","entitySet":"RA_INTERFACE_LINES_ALL","keyFields":"INTERFACE_LINE_ID","maxRecords":100}},
  {"sourceType":"JD_EDWARDS","url":"https://ais.example/jderest/v2/dataservice/table/",
   "properties":{"connectionName":"jde-reader","entitySet":"F0101","keyFields":"F0101_AN8","maxRecords":100}}
]}
```

Contract references:
- [Fusion invoices GET](https://docs.oracle.com/en/cloud/saas/financials/25d/farfa/op-invoices-get.html) and [authentication](https://docs.oracle.com/en/cloud/saas/financials/25d/farfa/Quick_Start.html).
- [EBS 12.2 interface GET example](https://docs.oracle.com/cd/E26401_01/doc.122/e20927/T511473T669558.htm) and [ISG WADL/control parameters](https://docs.oracle.com/cd/E26401_01/doc.122/e20927/T511473T516919.htm).
- [EnterpriseOne AIS v2 Simple Table GET](https://docs.oracle.com/en/applications/jd-edwards/cross-product/9.2/rest-api/op-v2-dataservice-table-tablename-get.html).

Dynamics uses the service's current company scope; cross-company configuration
is not provided. Offset-based collections are not transactionally consistent:
concurrent ERP changes can cause duplicates or omissions. These are bounded
read snapshots, not change-data capture.

## Bounds and failure semantics

| Property | Default | Maximum |
|---|---:|---:|
| maxRecords | 100 | 100000 |
| pageSize (except JD Edwards) | 100 (clamped to maxRecords) | 1000 for NetSuite; 10000 otherwise |
| maxPages (except JD Edwards) | 20 | 1000 |
| timeoutMillis | 30000 per HTTP timeout | 120000 |
| maxResponseBytes | 4194304 per response (including record details) | 16777216 |

`maxDocuments` further caps `maxRecords`; it does not raise its default. Duplicate
keys replace earlier records for the original six profiles; Oracle, Infor Mongoose and Acumatica profiles fail
on duplicate keys so an incorrect key or repeating page cannot silently lose
records. The record budget counts fetched rows. Explicit `keyFields` identifies scalar keys (comma-separated,
composite supported). Otherwise `@odata.id`, SAP `__metadata.uri`, `ID`, `Id` or `id`
is required. Rows without a stable identity fail instead of getting content-based
identities. Credentials/secret-named fields are excluded from generated content.

Only GET is issued, except Odoo's fixed read-only JSON-2 `search_read` POST.
HTTPS is required except literal loopback HTTP for local
fixtures/development. No redirects, automatic retries, cookies, arbitrary route
DSL, or write actions. OData continuations must stay on the exact same origin and
collection; query scope cannot change, omitted scope parameters are retained.
Salesforce queryMore links must stay on the same origin and API-version query
path, with no injected query parameters. NetSuite/Odoo/Fusion/EBS/Acumatica paging is
computed locally; Oracle and Acumatica response links are never followed. Infor bookmarks
are encoded only into the fixed LoadCollection endpoint, never followed as URLs.
Salesforce's batch-size header is advisory (clamped to 200–2000); `maxRecords`
remains the hard fetched-row limit for every profile.
Responses must be uncompressed JSON (the reader requests `Accept-Encoding:
identity`); byte caps are enforced while Camel consumes the response. Timeouts
are transport timeouts, not a total crawl deadline; cancellation is checked
between requests/records and transport is bounded by those timeouts.

Page-budget exhaustion, invalid JSON, transport/auth failures and unsafe links
fail the crawl without publishing partial materialization. Reaching maxRecords
is an intentional bounded sample. Snapshots are isolated by service/account,
tenant/client, collection and query scope. Existing materialization staging
atomically replaces a successful nonempty snapshot, preserving it on failure or
an empty read. This is **not** an authoritative deletion sync or OData delta-token
implementation; smaller successful samples may replace larger prior samples.
ERP transactional writes and reconciliation are deliberately out of scope.

## Validation

For opt-in, named-credential testing of a specific vendor sandbox, see
[ERP sandbox qualification](erp-qualification.md). Routine tests remain offline;
a fixture pass is not live vendor qualification.

The eleven-profile regression run passes **149 tests**: 101 ERP reader fixtures and
48 CLI credential/registry/crawl-to-search tests, including 35 Oracle, 21 Infor and
18 Acumatica reader cases.
Local HTTP fixtures exercise Camel itself, vendor pagination/envelopes/details,
credential headers, caps, redirects, stable IDs and failure/cancellation handling.
CLI tests cover named binding, expiry, setup registration, snapshot isolation and
failure preservation, plus chat crawl-to-local-search for the additional profiles.
No production ERP credentials are used. Native-image,
managed-server ERP loaders and vendor live qualification are not claimed.
