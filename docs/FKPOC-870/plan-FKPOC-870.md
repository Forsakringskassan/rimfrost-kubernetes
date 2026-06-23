# Plan: FKPOC-870 — Integration tests for process restart

## Context

`POST /handlaggning/{handlaggningId}/process` was added to `rimfrost-service-workflow` (FKPOC-869).
The endpoint restarts the erbjudande process for an existing handlaggning without interrupting any
running process. It optionally updates the stored `replyTo` topic.

The integration test infrastructure lives in `rimfrost-kubernetes`:
- Base class: `src/it/java/fk/rimfrost/RimfrostTestSupport.java`
- Workflow service is port-forwarded to `localhost:8888` (constant `HANDLAGGNING_BASE_URL`)
- Kafka bootstrap: `localhost:9094`

---

## Steps

### Step 1 — Add helpers to `RimfrostTestSupport` ✅

Added to `RimfrostTestSupport`:

- `sendRestartProcess(UUID handlaggningId, String replyTo)` — POSTs to
  `HANDLAGGNING_URL/{handlaggningId}/process`; returns raw `HttpResponse<String>`.
  Sets `replyTo` on the request body only when non-null.
- `createKafkaConsumer(String topic)` — creates a consumer with a unique group ID and
  `AUTO_OFFSET_RESET=earliest`.
- `awaitKafkaMessage(KafkaConsumer, String handlaggningId)` — polls up to 15 attempts (~30 s)
  for a message whose `handlaggningId` field matches.
- `sendRegelDone(String baseUrl, String handlaggningId, String regelUrl)` — POSTs
  `{baseUrl}{regelUrl}/{handlaggningId}/done`; returns the HTTP status code.

Also bumped `rimfrost-service-workflow-openapi-jaxrs-spec` from `0.0.1` to `0.1.1` in `pom.xml`
to get `PostHandlaggningProcessRequest` / `PostHandlaggningProcessResponse`, and updated the OUL
import to `se.fk.rimfrost.oul.handlaggning.jaxrsspec.*` (spec 2.1.0 package migration).

### Step 2 — Create `ProcessRestartIT` ✅

New file: `src/it/java/fk/rimfrost/ProcessRestartIT.java`

`@BeforeAll`: waits for all four services (workflow, OUL, rtf-manuell, bekraftabeslut) and creates
a Kafka consumer for `handlaggning-done`.

**Test cases:**

#### TC1 — Restart returns 200 with handlaggning

No prior flow completion required — the endpoint only needs the handlaggning to exist.

1. `sendYrkandeRequest` → capture `handlaggningId`.
2. `sendRestartProcess(handlaggningId, null)` → assert HTTP 200.
3. Deserialise `PostHandlaggningProcessResponse`; assert `handlaggning.id == handlaggningId`.

**Deviation from original plan:** `erbjudandeId` assertion dropped — the `Handlaggning` model
does not expose `erbjudandeId` directly; `id` assertion is sufficient.

#### TC2 — Restart enqueues a new OUL task

The initial flow is driven to completion and the `handlaggning-done` Kafka message is awaited
before restarting. This eliminates a race between workflow's async post-completion processing
and the restart call — the done message is the last thing workflow produces, so receiving it
guarantees workflow has fully settled.

1. `sendYrkandeRequest` → capture `handlaggningId`.
2. `driveFlowToCompletion`; `awaitKafkaMessage` on `handlaggning-done` — waits for full settlement.
3. `sendRestartProcess(handlaggningId, null)`.
4. `sendUppgifterHandlaggare` polls OUL until a task for this `handlaggningId` appears.

#### TC3 — Restarted flow produces handlaggning-done message

The initial flow is driven to completion first to isolate the restarted flow's done message.

1. `sendYrkandeRequest` → capture `handlaggningId`.
2. `driveFlowToCompletion`; `awaitKafkaMessage` on `handlaggning-done` — drains first run.
3. Open a new Kafka consumer for `handlaggning-done`.
4. `sendRestartProcess(handlaggningId, null)`.
5. `driveFlowToCompletion` again.
6. `awaitKafkaMessage` — confirms the restarted flow produced its done message.

#### TC4 — Restart with new `replyTo` sends done to the updated topic

No split needed: "returns 200" and "creates OUL task" are topic-agnostic and already covered by
TC1 and TC2. The only unique behaviour of `replyTo` is message routing, so a single test suffices.

1. `sendYrkandeRequest` → capture `handlaggningId`.
2. `driveFlowToCompletion`; `awaitKafkaMessage` on `handlaggning-done` — drains first run.
3. Open a `try`-with-resources Kafka consumer for `handlaggning-done-alt`.
4. `sendRestartProcess(handlaggningId, "handlaggning-done-alt")`.
5. `driveFlowToCompletion` again.
6. `awaitKafkaMessage` on the alt consumer — confirms done arrived on the new topic.

#### TC5 — Unknown handlaggningId returns 404

1. `sendRestartProcess(UUID.randomUUID(), null)` → assert HTTP 404.

---

## Design notes

- `driveFlowToCompletion(UUID handlaggningId)` is a private helper in `ProcessRestartIT` that
  encapsulates the full VAH flow: `sendUppgifterHandlaggare` → `sendRegelGetData` →
  `sendRegelPatchData(Beslutsutfall.JA)` → `sendRegelDone(RTF_MANUELL_BASE_URL)` →
  `sendUppgifterHandlaggare` → `sendRegelDone(BEKRAFTABESLUT_BASE_URL)`.
- `sendDoneOperation` was renamed to `sendRegelDone` in `RimfrostTestSupport` to avoid a Java
  visibility conflict with the private method of the same name in `SmokeTestIT`.
- `getKafkaMessage` was renamed to `awaitKafkaMessage` for the same reason.
- TC2 consumer is opened inside a `try`-with-resources block to ensure it is closed after the test.
- Topics are auto-created (`auto.create.topics.enable: true` in `helm-chart/templates/kafka.yaml`).

---

## Out of scope

- Error cases for Kafka send failure or storage failure (covered by unit tests in
  `rimfrost-service-workflow`).
- Concurrent restart scenarios.
