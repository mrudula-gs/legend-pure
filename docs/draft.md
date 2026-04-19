# REST API for Access Points - Phase 1 Implementation Plan

> ?? **TESTING NOTE**: The lazy loading approach (fetching from metadata server at execution time)
> may have authentication issues. During GitLab CI deployment, `CI_JOB_JWT` is used for auth to
> metadata server and Ingest Discovery API. At runtime, the execution server uses Kerberos auth.
> **If lazy loading fails during testing**, we may need to:
> 1. Fall back to eager loading at deploy time (as originally implemented)
> 2. Or add proper auth headers/tokens to `LazyPlanLoader` HTTP calls
>
> The deployment-time approach was tested and worked. Revisit if runtime lazy loading fails.

---

## Scope

**Use Case**: As a producer/consumer, I want my Access Point available as a REST API.

**In Scope**: Default compute resolution, execution plan artifact generation, plan registration to MongoDB, REST endpoint for executing plans, on-prem deployment.

**Out of Scope**: Grammar changes (`REST` hint), ownership declaration on Data Products, explicit Compute ID, LMS/Cloud deployment, ad-hoc REST deployment (SDLC only).

---

## Design Principles (aligned with existing Lakehouse Access Point lifecycle)

The REST API access point lifecycle follows the **same model as the existing DDL-based access points**
(`CREATE OR REPLACE VIEW` / `CREATE OR REPLACE FUNCTION`):

| Principle | DDL Access Points (existing) | REST API Access Points (this work) |
|-----------|------------------------------|-------------------------------------|
| **Versioning** | None ? `CREATE OR REPLACE` overwrites in place | None ? MongoDB document is upserted (overwritten) |
| **Rollback** | Redeploy previous build artifact | Redeploy previous build artifact |
| **Side-by-side** | One VIEW/FUNCTION per AP per schema | One document per DataProduct per server |
| **Schema evolution** | Validated before overwrite; only non-breaking changes allowed | Same ? validate before overwrite (Phase 2) |
| **Environment authority** | Server's `environmentClassification` config | Server's `lakehouse.environment.mode` config |

**Why match the DDL model?**
- Consistency: consumers of REST API access points get the same mental model as Snowflake consumers
- Simplicity: no version management, no ACTIVE/PREVIOUS state machine, no stale version cleanup
- The `CREATE OR REPLACE` semantic is proven at scale in the existing lakehouse

---

## End-to-End Flow

```
?????????????????????????????????????????????????????????????????????????
?                                                                       ?
?  1. BUILD (SDLC build job ? alloy-lakehouse)                          ?
?     RestApiPlanArtifactGenerationExtension generates restApiPlan.json ?
?     with placeholders (single artifact in metadata):                  ?
?       - env: __LAKEHOUSE_ENV_PLACEHOLDER__                            ?
?       - DID: __LAKEHOUSE_DID__                                        ?
?         (used inside SQL schema names like                            ?
?          orgdatacloud$internal$LH_ORG_LISTING___LAKEHOUSE_DID___DP_...)?
?     Artifact auto-stored in Alloy metadata server                     ?
?                                                                       ?
?  2. DEPLOY (GitLab CI ? Alloy deployment extension)                   ?
?     DataProductDefinitionAlloyDeploymentExtension:                    ?
?     ? Deploys DDLs to Snowflake (existing)                            ?
?     ? NO MongoDB writes for REST API plans (lazy loading)             ?
?                                                                       ?
?  3. FIRST EXECUTION (REST API call ? Alloy execution server)          ?
?     AccessPointExecutionApi:                                          ?
?     a) Check MongoDB for plan ? NOT FOUND                             ?
?     b) Fetch restApiPlan.json from metadata server                    ?
?        (version resolved: SNAPSHOT for dev server, latest release     ?
?         for prod server ? server config determines which)             ?
?     c) Resolve base environment via Ingest Discovery API              ?
?     d) Server config determines env suffix + DID:                     ?
?        - PROD_PARALLEL server ? env = {baseEnv}-pp, DID = ppDID      ?
?        - PRODUCTION server    ? env = {baseEnv},    DID = prodDID    ?
?     e) Replace placeholders in plan JSON (env + DID)                  ?
?        - __LAKEHOUSE_ENV_PLACEHOLDER__ ? resolved env                 ?
?        - __LAKEHOUSE_DID__ ? resolved DID                             ?
?        (covers datasourceSpecification.environment AND SQL schema)    ?
?     f) Upsert resolved plan in MongoDB (overwrites any previous)      ?
?     g) Execute plan via PlanExecutor                                  ?
?     h) Return results as JSON/CSV                                     ?
?                                                                       ?
?  4. SUBSEQUENT EXECUTIONS (fast path)                                 ?
?     a) Check MongoDB ? FOUND                                          ?
?     b) Execute plan via PlanExecutor                                  ?
?     c) Return results as JSON/CSV                                     ?
?                                                                       ?
?????????????????????????????????????????????????????????????????????????
```

---

## Deployment Flow

```
GitLab CI/CD
    ?
    ? DataProductDefinitionAlloyDeploymentExtension.deployAll()
    ?
    ??? deployDataProductForEntitlements()           [existing - DDL deployment]
    
    (NO MongoDB writes for REST API plans ? lazy loading at execution time)
```

## Execution Flow (with Lazy Loading)

```
Client Request (First Invocation)
    ?
    ? AccessPointExecutionApi.executeAccessPoint()
    ?
    ??? repository.getPlan(coordinateKey)            ? NOT FOUND
    ?
    ??? fetchFromMetadataServer(group, artifact, "latest")
    ?   ??? GET {metadata}/api/generations/{g}/{a}/latest/types/restApiPlan
    ?   (version comes from artifact metadata ? server validates it; see guard below)
    ?
    ??? resolveEnvironment(owner)
    ?   ??? GET {platform}/api/ingest/discovery/environments/producers/{DID}/...
    ?
    ??? replacePlaceholders(plan, environment, DID)
    ?   (server config determines env suffix + which DID to use)
    ?
    ??? repository.upsertPlan(resolvedPlan)          ? MongoDB (upsert by coordinateKey)
    ?
    ??? planStorage.findExecutionPlan(accessPoint, targetDb)
    ?
    ??? PlanExecutor.execute(plan, params)
    ?
    ??? ResultManager.manageResult(result, format)

Client Request (Subsequent Invocations)
    ?
    ? AccessPointExecutionApi.executeAccessPoint()
    ?
    ??? repository.getPlan(coordinateKey)            ? FOUND
    ?
    ??? planStorage.findExecutionPlan(accessPoint, targetDb)
    ?
    ??? PlanExecutor.execute(plan, params)
    ?
    ??? ResultManager.manageResult(result, format)
```

---

## Architecture Decisions

### Decision 1: Server Config is the Environment Authority (NOT the version suffix)

> **Problem solved**: Previously, the `-SNAPSHOT` suffix in the version string was the sole input
> deciding prod-parallel vs production environment. This meant a user could call
> `exec.alloy.gs.com` (prod) with a `-SNAPSHOT` version and get a prod-parallel plan materialized
> in the production MongoDB ? or vice versa. There was no guard.

**Solution**: Each execution server declares its environment mode via configuration, exactly as the
existing `DataProductDeployService` uses `IngestEnvironmentClassification`:

```yaml
# dev.exec.alloy.gs.com
lakehouse.environment.mode: PROD_PARALLEL

# exec.alloy.gs.com
lakehouse.environment.mode: PRODUCTION
```

**The server config is the single source of truth for:**
1. Which environment suffix to apply (`-pp` or none)
2. Which DID to use (`prodParallelDID` or `productionDID`)
3. Rejecting mismatched requests (hard guard)

**Guard logic:**
```java
// On every lazy-load, validate artifact version matches server mode
if (serverMode == PRODUCTION && sdlcVersion.endsWith("-SNAPSHOT")) {
    throw new BadRequestException(
        "SNAPSHOT artifacts cannot be loaded on the production server. " +
        "Use dev.exec.alloy.gs.com for SNAPSHOT versions.");
}
if (serverMode == PROD_PARALLEL && !sdlcVersion.endsWith("-SNAPSHOT")) {
    throw new BadRequestException(
        "Release artifacts cannot be loaded on the dev server. " +
        "Use exec.alloy.gs.com for release versions.");
}
```

**Placeholder resolution uses server config, not version suffix:**
```java
// Environment resolution
String envSuffix = (serverMode == PROD_PARALLEL) ? "-pp" : "";
String resolvedEnv = baseEnv + envSuffix;
String resolvedDID = (serverMode == PROD_PARALLEL)
    ? environmentInfo.prodParallelDID
    : environmentInfo.productionDID;

// Replace placeholders
planJson = planJson.replace("__LAKEHOUSE_ENV_PLACEHOLDER__", resolvedEnv);
planJson = planJson.replace("__LAKEHOUSE_DID__", resolvedDID);
```

**Precedent**: The existing `DataProductDeployService` (line 682-687) already does this:
```java
EnvironmentType lakehouseEnvironmentType = switch (environmentClassification) {
    case IngestEnvironmentClassification.DEV -> EnvironmentType.DEVELOPMENT;
    case IngestEnvironmentClassification.PROD_PARALLEL -> EnvironmentType.PRODUCTION_PARALLEL;
    case IngestEnvironmentClassification.PROD -> EnvironmentType.PRODUCTION;
};
```

| Aspect | Before (wrong) | After (correct) |
|--------|----------------|-----------------|
| Who decides env? | Version suffix (`-SNAPSHOT`) | Server config (`lakehouse.environment.mode`) |
| Cross-env protection | None | Hard reject on mismatch |
| Version suffix role | Decision input | Validation input (safety net) |
| Precedent | None | Matches `DataProductDeployService.environmentClassification` |

### Decision 2: No Version in the Consumer URL

> **Problem solved**: The previous design had `{version}` in the URL path, which implied
> consumers could pin to specific versions, that multiple versions could coexist, and that
> version management was needed. None of this aligns with the actual lakehouse model.

**The lakehouse model is: one version, always the latest, always overwritten.**
- A Snowflake consumer queries `SELECT * FROM schema.MY_VIEW` ? no version
- A REST API consumer should call `/execute/.../MY_ACCESS_POINT` ? no version

**New URL design (version removed from consumer-facing URL):**
```
GET /api/lakehouse/v1/execute/{group}/{artifact}/{dpPath}/{accessPoint}?params...
```

**Example:**
```
GET /api/lakehouse/v1/execute/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST/DATA_BROWSER_UNIVERSE
```

**The SDLC version is an internal implementation detail**, not a consumer concern:
- The server knows which version to fetch from the metadata server (latest for the server's mode)
- The version is stored in MongoDB for audit/traceability
- The consumer never sees or specifies a version

**Why this is better:**

| Aspect | With version in URL (before) | Without version in URL (after) |
|--------|------------------------------|-------------------------------|
| Consumer experience | Must know SDLC version | Just knows the DataProduct + AccessPoint |
| URL stability | URL changes on every release | URL is permanent |
| Bookmarkable | No ? version changes | Yes ? stable URL |
| Consistency with DDL | DDL has no version concept | Matches DDL model |
| Side-by-side | Implies multi-version support | Clearly single-version |
| Rollback | Implies version rollback is possible | Clearly: redeploy to rollback |
| Lazy loading | Must know which version to fetch | Server resolves latest automatically |

**How does the server know which artifact version to fetch?**
- The metadata server API supports a `latest` version alias
- The server fetches the latest artifact for the given `group:artifact`
- The SDLC version from the fetched artifact is stored in MongoDB for audit

**Internal version tracking (for audit, not for consumers):**
The MongoDB document stores `sdlcVersion` for traceability, but it's never exposed in the URL:
```json
{
  "coordinateKey": "com.gs.alloy:anumam-query:usage_stats::data_product::Usage_Stats_Data_Product_REST",
  "sdlcVersion": "0.0.1-SNAPSHOT",
  "sdlcCoordinates": { "group": "com.gs.alloy", "artifact": "anumam-query" },
  ...
}
```

### Decision 3: Separate Hosts for Prod vs Prod-Parallel

| Server | Environment Mode | MongoDB Database | Accepts |
|--------|-----------------|------------------|---------|
| `dev.exec.alloy.gs.com` | `PROD_PARALLEL` | `alloy-execution-service-dev` | `*-SNAPSHOT` only |
| `exec.alloy.gs.com` | `PRODUCTION` | `alloy-execution-service` | non-SNAPSHOT only |

**How it works**:
- Build generates a single plan per Access Point per TargetDB with placeholder environment
- Each server has its own MongoDB database ? complete isolation (same pattern as existing services)
- Server config (not version suffix) determines environment resolution
- Hard guard rejects mismatched version types

**Benefits**:
- No ambiguity or risk of cross-environment mistakes
- Simpler API contract (no env parameter, no version parameter)
- PP writes don't affect prod reads (separate databases)
- Follows existing infrastructure pattern for services

### Decision 4: Single Document Per DataProduct, Upsert Semantic

**One document per DataProduct per server.** On redeploy, the document is **overwritten** (upsert),
not versioned. This matches `CREATE OR REPLACE`:

```
MongoDB upsert by coordinateKey:
  filter:  { coordinateKey: "com.gs.alloy:my-dp:test::MyDP" }
  update:  { $set: { <entire new document> } }
  options: { upsert: true }
```

- No `status` field (ACTIVE/PREVIOUS) ? there's only one document, always the current one
- No version-based lookup ? there's only one version
- No cleanup of old versions ? nothing to clean up

### Single Plan Per Access Point Per Target DB

Each Access Point has one or more target database types (e.g., Snowflake, Databricks). For each target DB, ONE execution plan is pre-generated with a placeholder environment:

```json
{
  "targetDbPlans": [{
    "targetDbType": "Snowflake",
    "executionPlan": {
      "executionPlan": "{...serialized plan...}",
      "environment": "__LAKEHOUSE_ENV_PLACEHOLDER__",
      "warehouse": "LAKEHOUSE_CONSUMER_DEFAULT_WH"
    }
  }]
}
```

At first execution time (lazy loading), the placeholder is replaced based on **server config**:
- `PROD_PARALLEL` server ? `__LAKEHOUSE_ENV_PLACEHOLDER__` becomes `dataeng-pp`
- `PRODUCTION` server ? `__LAKEHOUSE_ENV_PLACEHOLDER__` becomes `dataeng`

### Placeholder Strategy (Environment + Deployment ID)

Build-time plan generation cannot know the final execution context (prod vs prod-parallel), so the artifact is produced with placeholders:

- `__LAKEHOUSE_ENV_PLACEHOLDER__` in the connection datasource specification
- `__LAKEHOUSE_DID__` embedded in the org-listing schema name inside SQL, e.g.
  `orgdatacloud$internal$LH_ORG_LISTING___LAKEHOUSE_DID___DP_...`

**Runtime substitution (Alloy execution server, before storing to MongoDB):**

Substitution must happen in the *raw plan JSON string* so it covers both:
- `datasourceSpecification.environment`
- SQL schema name occurrences like `LH_ORG_LISTING___LAKEHOUSE_DID___`

Resolution is driven by **server config** (`lakehouse.environment.mode`):

- If server mode = `PROD_PARALLEL` (dev.exec.alloy.gs.com)
   - env = `{baseEnv}-pp`
   - DID = `prodParallelDID`

- If server mode = `PRODUCTION` (exec.alloy.gs.com)
   - env = `{baseEnv}`
   - DID = `productionDID`

Notes:
- We still store only **one artifact** in metadata.
- Each execution server materializes (and persists) only the plan for its environment.
- The version suffix is validated (guard) but is NOT the decision input for environment resolution.

### Lazy Loading to MongoDB (Phase 1 Decision)

**Decision**: Plans are loaded to MongoDB on first REST API invocation (lazy), not at deployment time (eager).

```
???????????????????????????????????????????????????????????????????
?                        LAZY LOADING FLOW                        ?
???????????????????????????????????????????????????????????????????
?                                                                 ?
?  BUILD TIME (alloy-lakehouse)                                   ?
?    ??? Generate restApiPlan.json ? Metadata Server              ?
?                                                                 ?
?  DEPLOY TIME (Alloy deployment extension)                       ?
?    ??? Deploy DDLs only (NO MongoDB writes for REST API plans)  ?
?                                                                 ?
?  FIRST EXECUTION (REST API call)                                ?
?    1. Check MongoDB for plan (by coordinateKey)                 ?
?    2. If NOT found:                                             ?
?       a. Fetch latest artifact from Metadata Server             ?
?       b. Validate version matches server mode (guard)           ?
?       c. Resolve environment via Ingest Discovery API           ?
?       d. Replace placeholders (server config determines env)    ?
?       e. Upsert to MongoDB (overwrites any previous)            ?
?    3. Execute plan                                              ?
?                                                                 ?
?  SUBSEQUENT EXECUTIONS                                          ?
?    1. Check MongoDB ? Found ? Execute (fast path)               ?
?                                                                 ?
?  REDEPLOY (new build artifact in metadata server)               ?
?    Option A: Invalidation endpoint clears MongoDB entry         ?
?    Option B: Next execution detects stale plan ? re-fetch       ?
?                                                                 ?
???????????????????????????????????????????????????????????????????
```

| Approach | Pros | Cons |
|----------|------|------|
| **Lazy (chosen)** | Only invoked Access Points stored; scales to 1000s of APs; faster deployments; no wasted storage | First invocation has metadata latency (~few hundred ms) |
| Eager | Predictable latency on all calls | All APs written at deploy time even if unused; doesn't scale |

**Why Lazy Loading?**
- **Scale**: Expect 1000s of Access Points across all DataProducts ? most may never be invoked as REST APIs
- **MongoDB is persistent**: Once an Access Point is invoked, it's stored permanently (not just cached). Server restarts don't matter.
- **Deployment speed**: No MongoDB writes during GitLab CI/CD pipeline
- **Storage efficiency**: Only store what's actually used
- **First-call latency is acceptable**: One-time cost per Access Point, subsequent calls are fast

**Handling concurrent first-requests**: If multiple requests hit the same Access Point before it's in MongoDB, use a simple fetch-once pattern (lock or check-then-insert) to avoid duplicate fetches.

**Staleness detection (redeploy scenario)**:
When a DataProduct is redeployed (new build artifact in metadata server), the MongoDB entry becomes stale.
Three mechanisms handle this:

- **Deploy-time version hint (automatic, always runs)**: On every deployment, `DataProductDefinitionAlloyDeploymentExtension` calls `PUT /api/lakehouse/v1/accesspoint/version-hint/{group}/{artifact}/{dpPath}?version={sdlcVersion}` on the execution server. This stores the SDLC version (e.g. `queryDataProduct-SNAPSHOT`) so the lazy loader fetches the correct artifact from the metadata server. This is decoupled from the DELETE invalidation ? it runs regardless of whether a cached plan exists (first deploy, redeploy, etc.).
- **Deploy-time invalidation (automatic, on successful DDL deploy)**: After successful DDL deployment, `DataProductDefinitionAlloyDeploymentExtension` calls `DELETE /api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}` on the execution server. This clears the MongoDB entry so the next execution re-fetches the latest artifact. Requires `EXEC_SERVER_URL_OVERRIDE` env var to be set in the CI pipeline. Best-effort ? if it fails, the other mechanisms serve as backup.
- **`?refresh=true` on execute endpoint (manual, escape hatch)**: Consumers or admins can append `?refresh=true` to any execute URL to force re-fetch from metadata server, bypassing the MongoDB cache. Example: `GET /api/lakehouse/v1/execute/.../MY_AP?refresh=true`
- **Admin invalidation endpoint (manual)**: `DELETE /api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}` ? can be called directly to clear a stale entry

**In-memory cache (Phase 2)**: Can add an in-memory hot cache on top of MongoDB for frequently accessed plans to reduce MongoDB round-trips.

### No Changes to Existing Lakehouse Infrastructure

`DataProductDeployService`, `DataProductDeployConfiguration`, `AlloyMetadataServerHttpClient`, and all other existing alloy-lakehouse deployment code remain untouched. The only new code in alloy-lakehouse is the artifact generation extension.

---

## Component 1: Artifact Generation (alloy-lakehouse)

**Trigger**: The `RestApiPlanArtifactGenerationExtension` is registered via SPI (`ArtifactGenerationExtension`) and auto-triggered during the SDLC build job for every DataProduct element ? same mechanism as `DataProductArtifactGenerationExtension`.

**Output**: `restApiPlan.json` artifact stored in the Alloy metadata server under type key `restApiPlan`.

### Artifact Structure

```
DataProduct Artifact (restApiPlan.json)
??? coordinates: { group, artifact, version, dataProductPath }
??? environmentInfo: { environmentName (placeholder), productionDID, prodParallelDID }
??? accessPointGroups[]
    ??? AccessPointGroup
        ??? accessPointGroupID
        ??? accessPointPlans[]
            ??? AccessPointPlan
                ??? accessPointID
                ??? targetDbPlans[]
                    ??? TargetDbPlan
                        ??? targetDbType: "Snowflake"
                        ??? executionPlan: { executionPlan (JSON), environment, computeWarehouse }
```

### Key Files (alloy-lakehouse)

| File | Purpose |
|------|---------|
| `RestApiPlanArtifactGenerationExtension.java` | SPI extension, generates `restApiPlan.json` with placeholder env |
| `GenerateRestApiPlanArtifact.java` | Java wrapper calling Pure plan generation functions |
| `protocol.pure` | Protocol classes (DataProductExecutionPlanArtifact, TargetDbPlans, etc.) |
| `planGeneration.pure` | Pure functions that build execution plans per Access Point |
| `TestRestApiPlanArtifactGenerationExtension.java` | Tests for artifact structure, placeholder usage, and replacement |

### Plan Generation Logic (Pure)

For each `LakehouseAccessPoint`, the generator:
1. Builds a `DataProductRelationAccessor` (`#P{dataProduct.accessPoint}#`)
2. Wraps it with a `LakehouseRuntime` containing the placeholder environment and warehouse
3. Compiles an execution plan via `meta::pure::executionPlan::executionPlan()`
4. Serializes to JSON

For each target DB type on the Access Point, ONE plan is generated with `__LAKEHOUSE_ENV_PLACEHOLDER__` as the environment. At first execution time (lazy loading), the placeholder is replaced with the actual environment name based on **server config**.

---

## Component 2: Deployment (Alloy) ? DDL Only

**Trigger**: `DataProductDefinitionAlloyDeploymentExtension.deployAll()` ? called during GitLab CI/CD pipeline.

**Deployment handles DDLs only** (no MongoDB registration for REST API plans):

```
deployAll()
??? deployDataProductForEntitlements()           ? existing (DDL deployment to Snowflake)

(REST API plan registration happens lazily at first execution time)
```

**Why no MongoDB write at deploy time?**
- Scale: 1000s of Access Points across all DataProducts ? most may never be invoked as REST APIs
- Storage efficiency: Only persist plans that are actually used
- Faster deployments: No additional HTTP calls or MongoDB writes during GitLab CI/CD

---

## Component 2b: Lazy Loading at Execution Time (Alloy)

**Trigger**: First REST API call for an Access Point that's not yet in MongoDB.

### Lazy Loading Flow (in AccessPointExecutionApi)

```
executeAccessPoint(group, artifact, dpPath, accessPoint)
    ?
    ??? coordinateKey = "{group}:{artifact}:{dpPath}"
    ?
    ??? repository.getPlan(coordinateKey)
    ?   ??? If FOUND ? proceed to execution (fast path)
    ?   ??? If NOT FOUND ? lazy load:
    ?
    ??? fetchRestApiPlanArtifact(group, artifact, "latest")
    ?   GET {metadata}/api/generations/{g}/{a}/latest/types/restApiPlan
    ?   ? Parse JSON array ? extract file.content
    ?   ? Extract sdlcVersion from artifact
    ?
    ??? Validate version matches server mode (GUARD):
    ?   ??? PROD_PARALLEL server + non-SNAPSHOT version ? REJECT 400
    ?   ??? PRODUCTION server + SNAPSHOT version ? REJECT 400
    ?
    ??? resolveEnvironmentName(environmentInfo)
    ?   GET {platform}/api/ingest/discovery/environments/producers/{DID}/DEPLOYMENT/search
    ?   ? Extract "environmentName" (e.g., "dataeng")
    ?
    ??? Determine environment based on SERVER CONFIG:
    ?   ??? PROD_PARALLEL ? resolvedEnv = "{envName}-pp", DID = prodParallelDID
    ?   ??? PRODUCTION    ? resolvedEnv = "{envName}",    DID = productionDID
    ?
    ??? planJson.replace("__LAKEHOUSE_ENV_PLACEHOLDER__", resolvedEnv)
    ?   planJson.replace("__LAKEHOUSE_DID__", resolvedDID)
    ?
    ??? repository.upsertPlan(resolvedPlan)          ? MongoDB (upsert by coordinateKey)
    ?
    ??? proceed to execution
```

### Metadata Server API

The artifact is fetched using:
```
GET {metadataServerUrl}/api/generations/{groupId}/{artifactId}/latest/types/restApiPlan
```

Response is a JSON array:
```json
[{
  "groupId": "com.test",
  "artifactId": "my-dp",
  "versionId": "1.0.0",
  "type": "restApiPlan",
  "path": "...",
  "file": { "path": "restApiPlan.json", "content": "{ ...plan JSON... }" }
}]
```

Extract `[0].file.content` to get the plan JSON, `[0].versionId` for audit.

### MongoDB Storage

**Separate databases, same collection name:**

| Server | Database | Collection |
|--------|----------|------------|
| `dev.exec.alloy.gs.com` (PROD_PARALLEL) | `data-product-accesspoint-dev` | `data-product-accesspoint-plans` |
| `exec.alloy.gs.com` (PRODUCTION) | `data-product-accesspoint` | `data-product-accesspoint-plans` |

> DB names configured in `ServiceConfiguration` (`accessPointProdDb`, `accessPointDevDb`). The server's `LakehouseEnvironmentConfig` resolves which DB to use based on `LAKEHOUSE_ENVIRONMENT_MODE`. The DELETE invalidation endpoint accepts `?version=` and routes to the correct DB based on `-SNAPSHOT`.
- Same schema/indexes, just different database instances

**Document shape** (one document per DataProduct, upserted on each load/reload):
```json
{
   "_id": "...",
   "coordinateKey": "com.gs.alloy:anumam-query:usage_stats::data_product::Usage_Stats_Data_Product_REST",
   "coordinates": {
     "group": "com.gs.alloy",
     "artifact": "anumam-query",
     "dataProductPath": "usage_stats::data_product::Usage_Stats_Data_Product_REST"
   },
   "sdlcVersion": "0.0.1-SNAPSHOT",
   "environmentInfo": {
     "environmentName": "dataeng-pp",
     "resolvedDID": "220267"
   },
   "accessPointGroups": [{
      "accessPointGroupID": "PureAlloyUsage",
      "accessPointPlans": [{
         "accessPointID": "DATA_BROWSER_UNIVERSE",
         "targetDbPlans": [{
            "targetDbType": "Snowflake",
            "executionPlan": {
              "executionPlan": "{...resolved plan JSON...}",
              "environment": "dataeng-pp",
              "computeWarehouse": "LAKEHOUSE_CONSUMER_DEFAULT_WH"
            }
         }]
      }]
   }],
   "deploymentInfo": {
     "loadedAt": "2026-04-17T...",
     "serverVersion": "2026.4.1",
     "serverMode": "PROD_PARALLEL"
   }
}
```

**Key differences from previous design:**
- No `status` field ? one document, always the current one
- No `version` in lookup ? `coordinateKey` is the sole lookup key
- `sdlcVersion` stored for audit only, not for consumer use
- `serverMode` in `deploymentInfo` records which server materialized this plan
- Upsert semantic: `filter: { coordinateKey }, update: { $set: { ... } }, upsert: true`

**Indexes** (3 total ? simplified from 5):
1. `(coordinateKey)` unique ? primary lookup + upsert key
2. `(coordinates.group, coordinates.artifact, coordinates.dataProductPath)` ? query by coordinates
3. `(deploymentInfo.loadedAt desc)` ? recent loads, admin/debugging

---

## Component 3: REST Execution API (Alloy)

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/lakehouse/v1/execute/{group}/{artifact}/{dpPath}/{accessPoint}?params...` | Execute access point |
| `DELETE` | `/api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}` | Invalidate cached plan (force re-fetch on next execution) |
| `GET` | `/api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}` | Get current plan (admin/debug) |

**URL Design Decisions**:

1. **No `apGroup` in URL**: Access Point names are unique within a DataProduct, so `apGroup` is redundant. The lookup searches all groups for the matching `accessPoint` ID.

2. **No version in URL**: Follows the lakehouse model ? one version, always the latest. The SDLC version is an internal detail stored in MongoDB for audit. Consumers don't know or care about versions.

3. **Stable URLs**: A consumer's URL never changes across deployments. This is the same experience as querying a Snowflake VIEW ? the VIEW name is stable, the definition behind it changes.

**Path Parameters**:

| Parameter | Required | Description | Example |
|-----------|----------|-------------|---------|
| `group` | Yes | Maven group ID | `com.gs.alloy` |
| `artifact` | Yes | Maven artifact ID | `anumam-query` |
| `dpPath` | Yes | DataProduct path (URL-encoded) | `usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST` |
| `accessPoint` | Yes | Access Point ID | `DATA_BROWSER_UNIVERSE` |

**Query Parameters**:

| Parameter | Required | Default | Values | Description |
|-----------|----------|---------|--------|-------------|
| `targetDb` | No | `Snowflake` | `Snowflake`, etc. | Target database type |
| `serializationFormat` | No | `DEFAULT` | `DEFAULT`, `PURE`, `RAW`, `CSV` | Output format |
| Additional query params | Depends on AP | ? | Varies | Bound to execution plan variables |

> **Note**: No `?env=` parameter is needed. Each execution server only has plans for its environment:
> - `dev.exec.alloy.gs.com` ? prod-parallel plans only (rejects non-SNAPSHOT)
> - `exec.alloy.gs.com` ? production plans only (rejects SNAPSHOT)

### Execution Flow

```
Request ? AccessPointExecutionApi
  ? Build coordinateKey from path params (group:artifact:dpPath)
  ? repository.getPlan(coordinateKey)
  ? If NOT FOUND:
      ? fetchFromMetadataServer(group, artifact, "latest")
      ? validateVersionMatchesServerMode(sdlcVersion)     // GUARD
      ? resolveEnvironment(serverMode, environmentInfo)
      ? replacePlaceholders(plan, resolvedEnv, resolvedDID)
      ? repository.upsertPlan(resolvedPlan)               // Upsert to MongoDB
  ? planStorage.findExecutionPlan(accessPoint, targetDb)
  ? Build execution variables from parameters
  ? planExecutor.execute(plan, variables)
  ? ResultManager.manageResult()             ? JSON/CSV response
```

### Plan Invalidation (Redeploy Scenario)

When a DataProduct is redeployed (new build artifact in metadata server), the MongoDB
document needs to be refreshed. Three mechanisms are implemented:

**Mechanism 1: Deploy-time invalidation (automatic, primary)**
After successful DDL deployment, `DataProductDefinitionAlloyDeploymentExtension.deployAll()` calls:
```
DELETE {execServerUrl}/api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}
```
This is automatic and requires `EXEC_SERVER_URL_OVERRIDE` env var in the CI pipeline.
Best-effort ? if it fails (e.g., auth issues), logs a warning and continues. The DDL
deployment is not affected.

**Mechanism 2: `?refresh=true` on execute endpoint (manual escape hatch)**
Consumers or admins can force re-fetch by appending `?refresh=true`:
```
GET /api/lakehouse/v1/execute/{g}/{a}/{dp}/{ap}?refresh=true
```
This deletes the MongoDB entry and re-fetches from metadata server in the same request.
Useful for debugging or when deploy-time invalidation didn't fire.

**Mechanism 3: Admin invalidation endpoint (manual)**
```
DELETE /api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}
```
Deletes the MongoDB document. Next execution triggers lazy re-fetch.

### Plan Management Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/lakehouse/v1/accesspoint/register` | Register/overwrite a plan (admin) |
| `GET` | `/api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}` | Get current plan |
| `DELETE` | `/api/lakehouse/v1/accesspoint/plan/{group}/{artifact}/{dpPath}` | Invalidate plan (force re-fetch) |
| `PUT` | `/api/lakehouse/v1/accesspoint/version-hint/{group}/{artifact}/{dpPath}?version=` | Store SDLC version hint for lazy loading |
| `GET` | `/api/lakehouse/v1/accesspoint/version-hint/{group}/{artifact}/{dpPath}` | Get stored version hint |

### Schema Evolution (Phase 2)

Future work: Before overwriting a MongoDB plan, validate that the schema change is non-breaking
(adding columns, widening types OK; dropping columns, narrowing types rejected). This mirrors
the `DataProductSchemaEvolutionValidator` used in the DDL deployment path.

---

## Key Files (Alloy)

| File | Purpose |
|------|---------|
| `DataProductDefinitionAlloyDeploymentExtension.java` | MODIFIED ? after DDL deploy, calls DELETE on exec server to invalidate stale REST API plan cache |
| `AccessPointPlanStorage.java` | Data model for MongoDB documents |
| `AccessPointPlanRepository.java` | MongoDB CRUD (upsert by coordinateKey), indexes |
| `AccessPointPlanApi.java` | REST API for plan management (get, invalidate) |
| `AccessPointExecutionApi.java` | REST API for plan execution + lazy loading + server-mode guard |
| `MetadataServerClient.java` | Client to fetch artifacts from metadata server |
| `IngestDiscoveryClient.java` | Client to resolve environment via Ingest Discovery API |
| `LakehouseEnvironmentConfig.java` | NEW ? Server environment mode configuration |
| `TestAccessPointPlanRepository.java` | Repository unit tests |
| `TestAccessPointExecutionIntegration.java` | End-to-end REST API tests |
| `TestServerModeGuard.java` | Tests for version/server-mode validation |

---

## Notable Implementation Details

### Server Mode Configuration

```java
public class LakehouseEnvironmentConfig {
    public enum Mode { PROD_PARALLEL, PRODUCTION }
    
    private final Mode mode;  // from config: lakehouse.environment.mode
    
    public String resolveEnvironment(String baseEnv) {
        return mode == Mode.PROD_PARALLEL ? baseEnv + "-pp" : baseEnv;
    }
    
    public String resolveDID(EnvironmentInfo envInfo) {
        return mode == Mode.PROD_PARALLEL ? envInfo.prodParallelDID : envInfo.productionDID;
    }
    
    public void validateVersion(String sdlcVersion) {
        boolean isSnapshot = sdlcVersion.endsWith("-SNAPSHOT");
        if (mode == PRODUCTION && isSnapshot) {
            throw new BadRequestException("SNAPSHOT versions cannot be executed on production server");
        }
        if (mode == PROD_PARALLEL && !isSnapshot) {
            throw new BadRequestException("Release versions cannot be executed on dev server");
        }
    }
}
```

### Error Handling Strategy

- **Server mode guard**: Returns 400 (Bad Request) with clear message about which server to use
- **Execution API (lazy load)**: If metadata fetch fails, return 503 (Service Unavailable) with retry hint. If plan parsing fails, return 500 with details.
- **Execution API (execution)**: Returns structured error responses (404 for missing plan in metadata, 400 for bad input, 500 for execution failure).
- **Repository**: Uses MongoDB `replaceOne` with `upsert: true` for atomic document replacement.
- **Concurrent first-requests**: Use check-then-insert pattern to handle race conditions when multiple requests hit the same unloaded Access Point.

---

## Test Sequence (End-to-End)

### On dev.exec.alloy.gs.com (PROD_PARALLEL)

**Step 1: First execution (triggers lazy load)**
```
GET https://dev.exec.alloy.gs.com/api/lakehouse/v1/execute/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST/DATA_BROWSER_UNIVERSE
```
? Fetches artifact from metadata server, resolves env to `{baseEnv}-pp`, DID to `220267`, stores in MongoDB, executes

**Step 2: Verify plan in MongoDB**
```
GET https://dev.exec.alloy.gs.com/api/lakehouse/v1/accesspoint/plan/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST
```
? Confirm `environmentInfo.environmentName` is `{baseEnv}-pp`, SQL has `LH_ORG_LISTING_220267_DP_...`

**Step 3: Subsequent execution (fast path)**
```
GET https://dev.exec.alloy.gs.com/api/lakehouse/v1/execute/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST/DATA_BROWSER_UNIVERSE
```
? Faster (no metadata fetch). Same results.

**Step 4: Test invalidation (simulate redeploy)**
```
DELETE https://dev.exec.alloy.gs.com/api/lakehouse/v1/accesspoint/plan/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST
```
? Deletes MongoDB entry. Next execution re-fetches artifact.

**Step 5: Test refresh=true (alternative to explicit invalidation)**
```
GET https://dev.exec.alloy.gs.com/api/lakehouse/v1/execute/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST/DATA_BROWSER_UNIVERSE?refresh=true
```
? Deletes existing MongoDB entry, re-fetches from metadata server, executes with latest plan.

**Step 6: Test CSV format**
```
GET https://dev.exec.alloy.gs.com/api/lakehouse/v1/execute/com.gs.alloy/anumam-query/usage_stats%3A%3Adata_product%3A%3AUsage_Stats_Data_Product_REST/DATA_BROWSER_UNIVERSE?serializationFormat=CSV
```

---

## Summary of Changes from Previous Design

| Aspect | Previous Design | New Design | Reason |
|--------|----------------|------------|--------|
| Version in URL | `/{version}/` in path | Removed | Lakehouse has no versioning; one version always |
| Version in MongoDB | Multiple docs per DataProduct (ACTIVE/PREVIOUS) | One doc, upserted | Matches `CREATE OR REPLACE` semantic |
| Environment authority | `-SNAPSHOT` suffix decides | Server config (`lakehouse.environment.mode`) | Prevents cross-env mistakes; matches deploy server pattern |
| Version guard | None | Hard reject on mismatch | Safety net: SNAPSHOT only on dev, release only on prod |
| Status field | `ACTIVE` / `PREVIOUS` | None | No versioning = no status management |
| Indexes | 5 | 3 | Simpler model = fewer indexes |
| Rollback | Flip ACTIVE ? PREVIOUS | Redeploy previous artifact + invalidate | Same as DDL rollback |
| Consumer URL | Changes with each version | Stable, permanent | Better UX; matches Snowflake VIEW access pattern |
| Plan invalidation | Not addressed | Deploy-time auto-invalidation + `?refresh=true` + `DELETE` endpoint | Handles redeploy staleness |

---

## Open Items

| # | Item | Status |
|---|------|--------|
| 1 | Access Point authorization/entitlements on execution endpoints | TBD |
| 2 | MongoDB instance/cluster selection for production | TBD |
| 3 | Staleness detection: deploy-time invalidation + `?refresh=true` + DELETE endpoint | ? Implemented |
| 4 | Schema evolution validation before overwrite (matching DDL path) | Phase 2 |
| 5 | In-memory caching layer on top of MongoDB | Phase 2 |
| 6 | Fragment storage for plans exceeding 16MB | Phase 2 |
| 7 | Rate limiting / throttling on execution endpoints | Phase 2 |
| 8 | Warehouse resolution via Compute ID | Phase 2 |
