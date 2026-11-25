# Demo notebooks

## komplettering-identitet-uppdatering.py

Interactive [Marimo](https://marimo.io) notebook that walks through the komplettering flow driven by an external identity update (FKPOC-1269).

**Scenario:** A yrkande arrives with a known personnummer but without folkbokföring data. The komplettering service registers a prenumeration and parks the process with an unassignable OUL task. An external source then writes the folkbokföring data and fires an `IdentitetUppdateradEvent`. The komplettering service picks up the event, re-evaluates, closes the OUL task automatically, and sends a `RegelResponseMessage` back to the process — no handläggare interaction required.

The notebook steps through the full flow interactively, one step at a time, and shows live Kafka events at key points.

### Steps

| Step | Description |
|------|-------------|
| 1 | POST /yrkande — creates the handläggning; shows the `RegelRequestMessage` Kafka event |
| 2 | Wait for the kompletteringsuppgift to appear in OUL |
| 3 | Verify that folkbokföring data is absent from the handläggning |
| 4 | PUT /handlaggning — simulates external source writing folkbokföring data |
| 5 | Verify that the kompletteringsuppgift is not visible in the OUL handläggare queue |
| 6 | POST trigger — fires `IdentitetUppdateradEvent`; shows the live Kafka event |
| 7 | Verify that the kompletteringsuppgift is closed in OUL |
| 8 | Fetch the rtf-manuell regel task |
| 9 | PATCH beslutsutfall JA |
| 10 | Confirm the decision; await the `handlaggning-done` Kafka message |

### Prerequisites

**Cluster**

- minikube running with the full Rimfrost stack deployed
- Port-forwards active — run `./port-forward.sh` from the repo root

**Python**

Marimo is installed via `uv` with the required extra packages:

```
uv tool install marimo --with kafka-python-ng --with httpx
```

**Ports expected**

| Service | Port |
|---------|------|
| workflow (handläggning) | 8888 |
| uppgiftslager (OUL) | 8889 |
| rtf-manuell | 8890 |
| bekräftabeslut | 8891 |
| rtf-manuell-komplettering | 8899 |
| identitet-prenumeration | 8901 |
| handlaggning-svc | 8902 |
| Kafka bootstrap | 9094 |

### Running

```bash
marimo run demo/komplettering-identitet-uppdatering.py
```

Or open in edit mode to inspect or modify the notebook:

```bash
marimo edit demo/komplettering-identitet-uppdatering.py
```

See the [Marimo documentation](https://docs.marimo.io) for more information.
