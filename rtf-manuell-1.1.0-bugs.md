# rtf-manuell 1.1.0 — Known Bugs (awaiting framework fix)

## Context

Discovered during FKPOC-790 smoke test runs against `rimfrost-regel-rtf-manuell:1.1.0`.
The bugs are in the framework, not the service itself.

---

## Bug 1 — NPE in `OulKafkaProducer.sendOulStatusUpdate` when `uppgiftId` is null

**Symptom:** `/done` endpoint returns HTTP 500. Logs show:

```
se.fk.rimfrost.framework.regel.manuell.logic.DelayedException
Suppressed: java.lang.NullPointerException: Cannot invoke "java.util.UUID.toString()" because "uppgiftId" is null
  at se.fk.rimfrost.framework.oul.integration.kafka.OulKafkaProducer.sendOulStatusUpdate(OulKafkaProducer.java:48)
  at se.fk.rimfrost.framework.regel.manuell.logic.RegelManuellRequestHandler.handleUppgiftDone(RegelManuellRequestHandler.java:273)
```

**Root cause:**
`ManuellRegelCommonData.oulUppgiftId()` is `@Nullable`, but `RegelManuellRequestHandler.handleUppgiftDone` (line 273) passes it directly to `sendOulStatusUpdate(UUID uppgiftId, ...)`. Inside `sendOulStatusUpdate`, `uppgiftId.toString()` is called without a null guard.

**Fix location:** `rimfrost-framework-regel-manuell` — null-check `oulUppgiftId()` before calling `sendOulStatusUpdate`, or guard inside `sendOulStatusUpdate` itself.

---

## Bug 2 — NPE in `CloudEventAttributesMapper.toCloudEventData` when attributes map is null

**Symptom:** Same `/done` 500 as above; can also surface independently.

**Root cause:**
`CloudEventAttributesMapper.toCloudEventData(Map<String, String> attributes)` calls
`UUID.fromString(attributes.get("id"))` (and three other UUID fields) without null-checking
the map or the returned values. If `attributes` is null, or any key is absent, `UUID.fromString(null)` throws NPE.

**Fix location:** `rimfrost-framework-regel-manuell` — add a null-check on `attributes` and on each `attributes.get(key)` before passing to `UUID.fromString`.

---

## Bug 3 — `OulKafkaMapper.toOulStatus` passes null `cloudeventAttributes` downstream

**Symptom:** Feeds Bug 2; health checks return 503 (Kafka consumer marks itself DOWN).

**Root cause:**
`OulKafkaMapper.toOulStatus(OperativtUppgiftslagerStatusMessage)` sets
`cloudeventAttributes(oulStatusMessage.getCloudeventAttributes())` with no null guard.
When the incoming Kafka message lacks the field, a null map is stored and later passed to `CloudEventAttributesMapper.toCloudEventData`, triggering Bug 2.

**Fix location:** `rimfrost-framework-oul` — treat a missing `cloudeventAttributes` as an empty map (or skip the field) rather than propagating null.

---

## Impact on tests

- `SmokeTestIT.smoke_test_vah_request` — fails on the rtf-manuell `/done` call (Bug 1 / Bug 2).
- `PersistenceIT.persisted_data_survives_pod_restart` — fails on the same `/done` call, never reaches the persistence assertion.
- `waitForService` must accept any HTTP response (not just 200) because Bug 3 causes `/q/health` to always return 503.

## Next step

Upgrade to a patched framework release that fixes the above, rebuild `rimfrost-regel-rtf-manuell`,
update the image tag in `helm-chart/values.yaml`, and re-run both integration tests.
