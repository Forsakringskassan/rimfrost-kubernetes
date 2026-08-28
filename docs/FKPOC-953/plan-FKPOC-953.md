# Plan: FKPOC-953 — Integration tests for komplettering

## Context

`rimfrost-regel-rtf-manuell` now implements the komplettering flow from
`rimfrost-framework-regel` and `rimfrost-framework-regel-manuell`. When a yrkande
is missing a personnummer (typed `"personnummer"` in `individYrkandeRoller`) or a
non-blank `avsikt`, `RtfService.checkKomplettering()` returns a non-empty list.
Instead of creating the main OUL task, the framework creates a komplettering OUL
task, stores correlation state in `komplettering_tillstand`, and parks the BPMN
process at an `eventBasedGateway` (timer vs. message race).

The three komplettering REST endpoints on `rtf-manuell` are:
- `GET  /regel/rtf-manuell/{handlaggningId}/komplettering`
- `PATCH /regel/rtf-manuell/{handlaggningId}/komplettering`
- `POST /regel/rtf-manuell/{handlaggningId}/komplettering/done`

Integration test infrastructure lives in `rimfrost-kubernetes` (same as `SmokeTestIT`).

---

## Scope

One test class: `KompletteringIT`. Initial scope: **TC1 — personnummer saknas, happy path → JA**.

Scenarios 2 and 3 from the analysis session (NEJ outcome, 422-before-204) are out of scope for now
and can be added later on the same branch.

---

## Steps

### Step 1 — Investigate komplettering trigger (prerequisite, no code) ✅ PARTIALLY RESOLVED

**Goal:** Confirm what yrkande payload causes `checkKomplettering()` to return non-empty in the
running cluster (i.e., what the workflow service stores in the internal `Handlaggning` model).

**Resolved from rtf-manuell WireMock fixtures:**

- `individ.typId` in the Handlaggning model is the **string literal** `"personnummer"` (not a UUID).
- Missing personnummer is represented as `individYrkandeRoller = []` (empty array).
- The existing `sendYrkandeRequest()` sends `typId = IDTYP_TYP_ID ("c5f2e2b4-9143-4160-8f4b-30c172f0ac05")` — the workflow service maps this UUID to the string `"personnummer"` internally.
- `avsikt` in the Handlaggning model is a plain string (e.g. `"NY"`, `"FRAN_ARBETE"`); null when missing.

**Trigger approach:** Send `PostYrkandeRequest` with **empty `individYrkandeRoller`**. This requires
a new overload of `sendYrkandeRequest` (or a boolean parameter) that omits the role list. See OQ-1
for the remaining risk (upstream validation).

**Still open (OQ-2):** Whether `avsiktsId = UUID.randomUUID()` resolves to a non-null `avsikt`
in the running cluster. If yes, TC1 has only personnummer missing and the PATCH supplies only
`personnummer` (avsikt is already non-null). If no, both are missing and the PATCH must supply
both. See OQ-2.

### Step 2 — Add helpers to `RimfrostTestSupport` ✅

Three new methods, all targeting `RTF_MANUELL_BASE_URL`:

```java
// GET /regel/rtf-manuell/{handlaggningId}/komplettering → 200 + body
static RtfKompletteringData sendKompletteringGet(UUID handlaggningId)

// PATCH /regel/rtf-manuell/{handlaggningId}/komplettering → status code
static int sendKompletteringPatch(UUID handlaggningId, String personnummer, String avsikt)

// POST /regel/rtf-manuell/{handlaggningId}/komplettering/done → status code
static int sendKompletteringDone(UUID handlaggningId)
```

Notes:
- These hard-code the `/regel/rtf-manuell/` base path (unlike the main-flow helpers that read
  the URL from the OUL task), because the komplettering endpoints are always at a fixed path.
- `sendKompletteringPatch` builds a `RtfKompletteringData` body; import is already available
  via `rimfrost-regel-rtf-manuell-openapi-jaxrs-spec`.
- `registerSvar()` unconditionally overwrites `avsikt` with `request.getAvsikt()`. Sending
  `avsikt = null` in the PATCH would null out the existing avsikt, causing `checkKomplettering()`
  to fail on the re-run. The PATCH must echo back the avsikt read from the GET. TC1 therefore
  calls `sendKompletteringGet()` first, extracts the existing avsikt, and passes it to
  `sendKompletteringPatch()`.

Also needed: a new overload of `sendYrkandeRequest` that sends `individYrkandeRoller = []`
(omitting the role list entirely).

### Step 3 — Create `KompletteringIT` ✅

New file: `src/it/java/fk/rimfrost/KompletteringIT.java`

`@BeforeAll`: same service-readiness waits as `SmokeTestIT` + `resetOulDatabase()` + open
`handlaggningDoneConsumer`.

`@AfterAll`: close consumer.

#### TC1 — Personnummer saknas, kompletteras, utfall JA

```
1.  sendYrkandeRequestWithoutPersonnummer(erbjudandeId, yrkandeFrom, yrkandeTom)
        → handlaggningId
        assert: handlaggningId non-null
        (individYrkandeRoller = []; avsiktsId = randomUUID() → avsikt non-null in stored yrkande)

2.  sendUppgifterHandlaggare(HANDLAGGARE_C_VARDE, handlaggningId)
        → kompletteringTask
        assert: task URL contains "/komplettering"
        (main OUL task not yet created; process parked at eventBasedGateway)

3.  kompletteringData = sendKompletteringGet(handlaggningId)
        assert: personnummer is null or blank
        assert: avsikt is non-null and non-blank  [stored avsiktsId UUID]

4.  sendKompletteringPatch(handlaggningId, "19900101-9999", kompletteringData.getAvsikt())
        assert: 204
        (avsikt echoed back from GET to avoid nulling it out on re-run)

5.  sendKompletteringDone(handlaggningId)
        assert: 204
        (komplettering OUL task closed; regel re-triggered via Kafka;
         checkKomplettering() returns empty → personnummer present, avsikt preserved;
         main OUL task created)

6.  sendUppgifterHandlaggare(HANDLAGGARE_C_VARDE, handlaggningId)
        → regelTask
        assert: task URL does NOT contain "/komplettering"
        regelUrl = regelTask.getOperativUppgift().getUrl()

7.  sendRegelGetData(handlaggningId, regelUrl)
        → regelGetDataResponse
        assert: handlaggningId matches
        ersattningId = regelGetDataResponse.getErsattningar().getFirst().getErsattningId()

8.  sendRegelPatchData(handlaggningId, regelUrl, Beslutsutfall.JA, ersattningId)
        assert: 204

9.  sendRegelDone(RTF_MANUELL_BASE_URL, handlaggningId, regelUrl)
        assert: 204

10. sendUppgifterHandlaggare(HANDLAGGARE_C_VARDE, handlaggningId)
        → bekraftaTask
        bekraftaUrl = bekraftaTask.getOperativUppgift().getUrl()

11. sendRegelDone(BEKRAFTABESLUT_BASE_URL, handlaggningId, bekraftaUrl)
        assert: 204

12. awaitKafkaMessage(handlaggningDoneConsumer, handlaggningId)
        assert: message received
```

---

## Open questions and gaps

### OQ-1 — Does empty `individYrkandeRoller = []` in PostYrkandeRequest pass upstream validation? ✅ RESOLVED

`PostYrkandeRequest` in `rimfrost-service-workflow-openapi/openapi.yaml` declares
`individYrkandeRoller` as `required` but imposes no `minItems` constraint. An empty array is
schema-valid.

### OQ-2 — Does `avsiktsId = UUID.randomUUID()` produce a non-null `avsikt`? ✅ RESOLVED

The `Yrkande` schema in `rimfrost-service-workflow-openapi/openapi.yaml` describes `avsikt` as
*"Unikt ID som identifierar avsikt för yrkandet"* — the workflow stores `avsiktsId` directly as
the `avsikt` string without resolving it. Any non-blank `avsiktsId` produces a non-blank `avsikt`.
TC1 therefore has only personnummer missing, not avsikt.

### OQ-3 — Is there an async gap between `POST /komplettering/done` and main OUL task? ✅ RESOLVED (no gap)

`KompletteringController.kompletteringDone()` calls `regelRequestHandler.handleRegelRequest()`
directly and synchronously — it is NOT a Kafka send. `RegelManuellRequestHandler.handleRegelRequest()`
runs inline: fetches the handlaggning, calls `checkKomplettering()` (returns empty), and calls
`oulAdapter.createOperativUppgift()` to create the main OUL task — all before the HTTP response
is returned. By the time `sendKompletteringDone()` returns 204, the main OUL task already exists.
The 120-attempt polling will find it on the first attempt in practice.

### OQ-4 — Whether OUL serves closed tasks from `POST /uppgifter/handlaggare` ⚠ UNVERIFIED ASSUMPTION

`sendUppgifterHandlaggare` polls by calling `POST /uppgifter/handlaggare` (take-next-task for
handläggare) and checking whether the returned task's `handlaggningId` matches. After step 5,
the komplettering OUL task is closed (via `endOperativUppgift`). The assumption is that the
take-next-task endpoint only serves tasks in an open/available state, so the closed task won't
be returned again in step 6. This is almost certainly correct, but has not been verified against
the OUL spec or implementation.

If the assumption is wrong and the closed task is returned, the URL assertion at step 6
(`assert: URL does NOT contain "/komplettering"`) will fail visibly rather than silently, making
the bug easy to diagnose.

**How to resolve:** Check the OUL OpenAPI spec for `POST /uppgifter/handlaggare` to see if there
is a status filter, or rely on the first integration test run to confirm.

### OQ-5 — `RtfKompletteringData` import availability ✅ RESOLVED

`rimfrost-regel-rtf-manuell-openapi-jaxrs-spec` version 1.2.0 is present in `pom.xml` as a
regular compile-scope dependency. `RtfKompletteringData` is available in integration tests
without any pom change.

### OQ-6 — Valid avsikt value for PATCH ✅ RESOLVED

`registerSvar()` stores whatever `avsikt` string is passed and `checkKomplettering()` only
requires it to be non-blank — any non-blank string is safe. TC1 reads the existing avsikt from
`sendKompletteringGet()` and echoes it back in the PATCH, so the original `avsiktsId` UUID is
preserved and no downstream validation concern arises.

### OQ-7 — Does the handlaggning service accept `typId = "personnummer"` (string) on PUT? ✅ RESOLVED

`Idtyp.typId` in `rimfrost-service-handlaggning-openapi` is declared as `type: string` with no
format, enum, or pattern constraint — the handlaggning service treats it as an opaque string.
`registerSvar()` writing `typId = "personnummer"` is valid. Same erbjudandeId works.

---

## Out of scope

- TC2: NEJ outcome after komplettering (flow mechanics are identical; only the PATCH value differs)
- TC3: `POST /done` returns 422 before PATCH supplies all missing data
- Timeout scenario (komplettering times out → avslag)
- Multi-round BPMN loop (komplettering triggers twice before final outcome)
- Error paths: OUL adapter down during initiation, 409 on stale done call
