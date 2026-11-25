import marimo

__generated_with = "0.25.2"
app = marimo.App(width="medium")


@app.cell
def _():
    import marimo as mo

    return (mo,)


@app.cell
def _():
    import httpx
    import json
    import time
    import uuid as uuid_module
    from datetime import date

    return httpx, json, time, uuid_module


@app.cell
def _(mo):
    mo.md(r"""
    # Demo: Komplettering via IdentitetUppdateradEvent

    Ett yrkande anländer med `typId="personnummer"` men saknar folkbokföringsdata.
    Kompletteringstjänsten bedömer att komplettering krävs, skapar en kompletteringsuppgift
    i OUL, parkerar processen och registrerar en prenumeration. En extern källa skriver sedan
    folkbokföringsdata och triggar ett `IdentitetUppdateradEvent` — kompletteringen löser sig
    automatiskt utan handläggarinteraktion.

    **Förutsättningar:** minikube körs och port-forwards är aktiva (`./port-forward.sh`).
    """)
    return


@app.cell
def _(mo):
    HANDLAGGNING_BASE_URL = "http://localhost:8888"
    OUL_BASE_URL          = "http://localhost:8889"
    RTF_MANUELL_BASE_URL  = "http://localhost:8890"
    BEKRAFTABESLUT_BASE_URL = "http://localhost:8891"
    RTF_MANUELL_KOMPLETTERING_BASE_URL = "http://localhost:8899"
    HANDLAGGNING_SVC_BASE_URL = "http://localhost:8902"
    IDENTITET_PRENUMERATION_BASE_URL = "http://localhost:8901"

    ERBJUDANDE_ID          = "7d4a6c38-348b-4f46-9278-b1bfeabc0353"
    INDIVID_PNR            = "19900101-9999"
    IDTYP_PERSONNUMMER     = "c5f2e2b4-9143-4160-8f4b-30c172f0ac05"
    FOLKBOKFORING_OBJEKT_TYP_ID = "folkbokforing"
    YRKANDE_ROLL_ID        = "80f5f41f-9e55-4fc2-a076-ad5a651e0a9d"
    YRKANDESTATUS_ID       = "e27da561-a8db-4513-8272-ef652b097b16"
    ERSATTNINGSTYP_ID      = "dee75df2-a6e0-493d-8314-ec4c37b96f9c"
    HANDLAGGARE_TYP_ID     = "116759e4-18fd-4209-849c-90abbd257d22"  # typId för handläggare-identitet
    HANDLAGGARE_VARDE      = "3f439f0d-a915-42cb-ba8f-6a4170c6011f"  # varde för handläggare-identitet
    AUTH                   = f"Bearer {HANDLAGGARE_TYP_ID}:{HANDLAGGARE_VARDE}"  # Bearer <typId>:<varde>

    mo.md(f"""
    **Konfiguration laddad**

    | Tjänst | URL |
    |--------|-----|
    | workflow (handläggning) | `{HANDLAGGNING_BASE_URL}` |
    | uppgiftslager (OUL) | `{OUL_BASE_URL}` |
    | rtf-manuell | `{RTF_MANUELL_BASE_URL}` |
    | rtf-manuell-komplettering | `{RTF_MANUELL_KOMPLETTERING_BASE_URL}` |
    | bekräftabeslut | `{BEKRAFTABESLUT_BASE_URL}` |
    | handlaggning-svc | `{HANDLAGGNING_SVC_BASE_URL}` |
    | identitet-prenumeration | `{IDENTITET_PRENUMERATION_BASE_URL}` |
    """)
    return (
        AUTH,
        BEKRAFTABESLUT_BASE_URL,
        ERBJUDANDE_ID,
        ERSATTNINGSTYP_ID,
        FOLKBOKFORING_OBJEKT_TYP_ID,
        HANDLAGGARE_TYP_ID,
        HANDLAGGARE_VARDE,
        HANDLAGGNING_BASE_URL,
        HANDLAGGNING_SVC_BASE_URL,
        IDENTITET_PRENUMERATION_BASE_URL,
        IDTYP_PERSONNUMMER,
        INDIVID_PNR,
        OUL_BASE_URL,
        RTF_MANUELL_BASE_URL,
        RTF_MANUELL_KOMPLETTERING_BASE_URL,
        YRKANDESTATUS_ID,
        YRKANDE_ROLL_ID,
    )


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Hälsokontroll
    """)
    return


@app.cell
def _(mo):
    kubectl_btn = mo.ui.run_button(label="▶ kubectl get pods")
    return (kubectl_btn,)


@app.cell
def _(kubectl_btn, mo):
    import subprocess as _sp2
    mo.stop(not kubectl_btn.value, kubectl_btn)

    _cmd = ["kubectl", "get", "pods"]
    _result = _sp2.run(_cmd, capture_output=True, text=True, timeout=10)
    mo.output.replace(mo.vstack([
        mo.md(f"```\n$ {' '.join(_cmd)}\n```"),
        mo.md(f"```\n{_result.stdout or _result.stderr}\n```"),
        kubectl_btn,
    ]))
    return


@app.cell
def _(json, mo):
    def _as_json(body):
        """Return body as a parsed dict/list, or None if not JSON."""
        if isinstance(body, (dict, list)):
            return body
        if isinstance(body, str):
            try:
                return json.loads(body)
            except Exception:
                pass
        return None

    def _fmt_req_body(body):
        if body is None:
            return mo.md("_ingen body_")
        parsed = _as_json(body)
        if parsed is not None:
            return mo.json(parsed)
        return mo.md(f"```\n{str(body)[:2000]}\n```")

    def _fmt_resp_body(body):
        if body is None:
            return mo.md("_tom body_")
        parsed = _as_json(body)
        if parsed is not None:
            return mo.json(parsed)
        s = str(body).strip()
        return mo.md(f"```\n{s[:2000]}\n```") if s else mo.md("_tom body_")

    def req_resp_accordion(label, method, url, req_body, resp_status, resp_body, req_headers=None):
        _header_items = [mo.md(f"**Request:** `{method} {url}`")]
        if req_headers:
            _headers_md = "\n".join(f"- `{k}: {v}`" for k, v in req_headers.items())
            _header_items.append(mo.md(f"**Headers:**\n{_headers_md}"))
        return mo.accordion({label: mo.vstack([
            *_header_items,
            _fmt_req_body(req_body),
            mo.md(f"**Response:** HTTP {resp_status}"),
            _fmt_resp_body(resp_body),
        ])})

    return (req_resp_accordion,)


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Nollställ OUL-databasen

    "
        "Tömmer `operativt_uppgiftslager.uppgift` via `kubectl exec` — samma som "
        "`resetOulDatabase()` i IT-testerna. Kör detta innan varje demo-körning för "
        "att undvika att gamla uppgifter från tidigare körningar stör resultaten.
    """)
    return


@app.cell
def _(mo):
    reset_btn = mo.ui.run_button(label="▶ Nollställ OUL-databasen")
    return (reset_btn,)


@app.cell
def _(mo, reset_btn):
    import subprocess as _subp
    mo.stop(not reset_btn.value, reset_btn)

    _cmd = [
        "kubectl", "exec", "rimfrost-k8s-postgresql-0", "--",
        "/bin/sh", "-c",
        "PGPASSWORD=rimfrost-test psql -h 127.0.0.1 -U rimfrost-test -d rimfrost-test"
        " -c 'TRUNCATE TABLE operativt_uppgiftslager.uppgift CASCADE'",
    ]
    try:
        _proc = _subp.run(_cmd, capture_output=True, text=True, timeout=30)
        if _proc.returncode == 0:
            mo.output.replace(mo.vstack([mo.md("✅ OUL-databasen nollställd"), reset_btn]))
        else:
            mo.output.replace(mo.vstack([mo.md(f"❌ Misslyckades (exit {_proc.returncode})\n\n```\n{_proc.stdout}{_proc.stderr}\n```"), reset_btn]))
    except Exception as _e:
        mo.output.replace(mo.vstack([mo.md(f"❌ Fel: {_e}"), reset_btn]))
    return


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Handläggarportal

    [Öppna handläggarportalen](http://localhost:8894) — `http://localhost:8894`
    """)
    return


@app.cell
def _(mo):
    _DIAGRAM_STEG_1_3 = """sequenceDiagram
        participant Process
        participant regel_komplettering
        participant handlaggning
        participant OUL
        participant identitet_prenumeration

        Process ->> regel_komplettering: Kafka: RegelRequestMessage (handlaggningId, aktivitetId)

        rect rgb(70, 120, 160)
            note over regel_komplettering: Hämta handläggningsinformation
            regel_komplettering ->> handlaggning: GET /handlaggning/{handlaggningId}
            handlaggning -->> regel_komplettering: (handlaggning)
        end
        note over regel_komplettering: isKompletteringRequired(handlaggning) -> JA
        note over regel_komplettering: getPrenumerationsIdentiteter(handlaggning) -> identiteter

        rect rgb(70, 120, 160)
            regel_komplettering ->> identitet_prenumeration: POST /prenumerationer (prenumerationId=handlaggningId, identiteter, replyTo)
            identitet_prenumeration -->> regel_komplettering: (201 Created)
            note over regel_komplettering: Skapa kompletteringsuppgift
            regel_komplettering ->> OUL: POST /uppgifter (handlaggningId, assignable=false, url, roll, status_topic, ...)
            OUL -->> regel_komplettering: (uppgiftId, handlaggningId, status)
            note over regel_komplettering: Uppdatera handläggningsinformation med uppgiftsinformation
            regel_komplettering ->> handlaggning: PUT /handlaggning/{handlaggningId}
            handlaggning -->> regel_komplettering: (handlaggning)
        end
    """
    mo.vstack([
        mo.md(
            "---\n## Steg 1–3 — Kompletteringsregel initieras\n\n"
            "`regel_komplettering` tar emot ett `RegelRequestMessage`, hämtar handläggningen, "
            "bedömer att komplettering krävs (folkbokföring saknas), registrerar en prenumeration "
            "hos `identitet-prenumeration` och skapar en kompletteringsuppgift i OUL (`assignable=false`). "
            "Processen parkeras."
        ),
        mo.accordion({"Sekvensdiagram": mo.mermaid(_DIAGRAM_STEG_1_3)}),
    ])
    return


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Steg 1 — Skapa yrkande

    "
        "Yrkande med `typId="personnummer"` och känt personnummer. "
        "Folkbokföring saknas → komplettering krävs → prenumeration registreras.
    """)
    return


@app.cell
def _(mo):
    step1_btn = mo.ui.run_button(label="▶ POST /yrkande")
    return (step1_btn,)


@app.cell
def _(
    AUTH,
    ERBJUDANDE_ID,
    ERSATTNINGSTYP_ID,
    HANDLAGGNING_BASE_URL,
    IDTYP_PERSONNUMMER,
    INDIVID_PNR,
    YRKANDESTATUS_ID,
    YRKANDE_ROLL_ID,
    httpx,
    json,
    mo,
    req_resp_accordion,
    step1_btn,
    time,
    uuid_module,
):
    from kafka import KafkaConsumer as _KafkaConsumer, TopicPartition as _TopicPartition
    mo.stop(not step1_btn.value, step1_btn)

    # Anchor consumer at end of komplettering-requests topic before POST so we
    # catch exactly the RegelRequestMessage triggered by this yrkande.
    _TOPIC_REQ = "rtf-manuell-komplettering-requests"
    _kc = _KafkaConsumer(bootstrap_servers=["localhost:9094"])
    _kc_parts = None
    for _ in range(20):
        _kc_parts = _kc.partitions_for_topic(_TOPIC_REQ)
        if _kc_parts:
            break
        time.sleep(0.2)
    _kc_tps = [_TopicPartition(_TOPIC_REQ, p) for p in (_kc_parts or [0])]
    _kc.assign(_kc_tps)
    _kc_end = _kc.end_offsets(_kc_tps)
    for _tp, _off in _kc_end.items():
        _kc.seek(_tp, _off)

    _yrkande_from = "2025-12-24T00:00:00+01:00"
    _yrkande_tom  = "2025-12-24T00:00:00+01:00"
    _ersattning_data = json.dumps({
        "id": str(uuid_module.uuid4()),
        "version": 1,
        "ersattningstyp": {"id": ERSATTNINGSTYP_ID, "namn": "HUNDBIDRAG"},
        "omfattningProcent": 100,
        "belopp": 40000,
        "berakningsgrund": 0,
        "beslutsutfall": "FU",
        "from": "2025-12-24",
        "tom": "2025-12-24",
        "yrkandeStatus": YRKANDESTATUS_ID,
    })
    _url = f"{HANDLAGGNING_BASE_URL}/yrkande"
    _body = {
        "ingangtypId": ERBJUDANDE_ID,
        "yrkandeFrom": _yrkande_from,
        "yrkandeTom": _yrkande_tom,
        "handlaggningspecifikationId": str(uuid_module.uuid4()),
        "avsiktsId": str(uuid_module.uuid4()),
        "replyTo": "handlaggning-done",
        "rollerIYrkande": [{
            "individ": {"typId": IDTYP_PERSONNUMMER, "varde": INDIVID_PNR},
            "yrkandeRollId": YRKANDE_ROLL_ID,
        }],
        "sakfragorStallningstaganden": [{
            "objektTypId": "ERSATTNING",
            "data": _ersattning_data,
        }],
    }
    _r = httpx.post(
        _url,
        headers={"Content-Type": "application/json", "Authorization": AUTH},
        json=_body,
        timeout=10,
    )
    _resp_data = _r.json() if _r.content else {}
    handlaggning_id = _resp_data.get("handlaggning", {}).get("id")

    # Poll for the RegelRequestMessage published to regel_komplettering.
    _regel_request = None
    if handlaggning_id:
        _deadline = time.time() + 8
        while time.time() < _deadline and _regel_request is None:
            for _tp, _msgs in _kc.poll(timeout_ms=500).items():
                for _msg in _msgs:
                    try:
                        _data = json.loads(_msg.value.decode("utf-8"))
                        if str(_data.get("data", {}).get("handlaggningId")) == str(handlaggning_id):
                            _regel_request = _data
                            break
                    except Exception:
                        pass
    _kc.close()

    _kafka_section = (
        [mo.accordion({"Kafka: RegelRequestMessage → regel_komplettering": mo.json(_regel_request)})]
        if _regel_request is not None
        else [mo.md("⚠️ Inget `RegelRequestMessage` mottaget inom 8 sekunder.")]
    )

    mo.output.replace(mo.vstack([
        mo.md(f"**HTTP {_r.status_code}** — `handlaggningId: {handlaggning_id}`"),
        req_resp_accordion("Request / Response", "POST", _url, _body, _r.status_code, _resp_data),
        *_kafka_section,
        step1_btn,
    ]))
    return (handlaggning_id,)


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Steg 2 — Vänta på kompletteringsuppgift i OUL

    "
        "Kompletteringsuppgiften skapas asynkront av `rimfrost-regel-rtf-manuell-komplettering` "
        "efter att det bearbetat Kafka-meddelandet från Steg 1. Söker med "
        "`POST /uppgifter/individ/unassignable` tills uppgiften dyker upp.
    """)
    return


@app.cell
def _(mo):
    step2_btn = mo.ui.run_button(label="▶ Polla POST /uppgifter/individ/unassignable")
    return (step2_btn,)


@app.cell
def _(
    HANDLAGGARE_TYP_ID,
    HANDLAGGARE_VARDE,
    IDTYP_PERSONNUMMER,
    INDIVID_PNR,
    OUL_BASE_URL,
    handlaggning_id,
    httpx,
    mo,
    req_resp_accordion,
    step2_btn,
    time,
):
    mo.stop(not step2_btn.value, step2_btn)
    mo.stop(handlaggning_id is None, mo.md("⚠️ Kör Steg 1 först."))

    _url = f"{OUL_BASE_URL}/uppgifter/individ/unassignable"
    _body = {
        "id_typ": IDTYP_PERSONNUMMER,
        "id_varde": INDIVID_PNR,
        "statuses": ["Ny"],
    }
    _headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {HANDLAGGARE_TYP_ID}:{HANDLAGGARE_VARDE}",
    }
    uppgift_id = None
    _attempts = 0
    _last_status = None
    _last_resp = None
    _last_error = None
    while uppgift_id is None and _attempts < 30:
        try:
            _r = httpx.post(_url, headers=_headers, json=_body, timeout=10)
            _last_status = _r.status_code
            _last_resp = _r.text[:2000]
            if _r.status_code == 200 and _r.content:
                for _t in _r.json().get("operativa_uppgifter", []):
                    if str(_t.get("handlaggning_id")) == str(handlaggning_id):
                        uppgift_id = _t["uppgift_id"]
                        break
            else:
                _last_error = f"HTTP {_r.status_code}"
        except Exception as _e:
            _last_error = str(_e)
            _last_status = None
            _last_resp = None
        if uppgift_id is None:
            _attempts += 1
            time.sleep(1)

    if uppgift_id is None:
        mo.output.replace(mo.vstack([
            mo.md(f"❌ Ingen uppgift hittades efter {_attempts} försök"),
            req_resp_accordion("Request / Response (sista försök)", "POST", _url, _body, _last_status, _last_resp),
            step2_btn,
        ]))
    else:
        mo.output.replace(mo.vstack([
            mo.md(f"✅ **uppgiftId:** `{uppgift_id}` (efter {_attempts + 1} försök)"),
            req_resp_accordion("Request / Response (sista försök)", "POST", _url, _body, _last_status, _last_resp),
            step2_btn,
        ]))
    return (uppgift_id,)


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Steg 3 — Verifiera att folkbokföring saknas

    "
        "Läser handläggningen och bekräftar att `sakfragorStallningstaganden` inte innehåller "
        "någon post med `objektTypId = "folkbokforing"` ännu.
    """)
    return


@app.cell
def _(mo):
    step3_btn = mo.ui.run_button(label="▶ GET /handlaggning/{id}")
    return (step3_btn,)


@app.cell
def _(
    FOLKBOKFORING_OBJEKT_TYP_ID,
    HANDLAGGNING_SVC_BASE_URL,
    handlaggning_id,
    httpx,
    mo,
    req_resp_accordion,
    step3_btn,
):
    mo.stop(not step3_btn.value, step3_btn)
    mo.stop(handlaggning_id is None, mo.md("⚠️ Kör Steg 1 först."))

    _url = f"{HANDLAGGNING_SVC_BASE_URL}/handlaggning/{handlaggning_id}"
    _r = httpx.get(_url, timeout=10)
    _r.raise_for_status()
    initial_handlaggning = _r.json()
    _sakfragor = (
        initial_handlaggning.get("handlaggning", {})
        .get("yrkande", {})
        .get("sakfragorStallningstaganden", [])
    )
    _has_fb = any(s.get("objektTypId") == FOLKBOKFORING_OBJEKT_TYP_ID for s in _sakfragor)
    _status = "❌ Folkbokföring finns redan!" if _has_fb else "✅ Folkbokföring saknas (som förväntat)"

    mo.output.replace(mo.vstack([
        mo.md(f"**HTTP {_r.status_code}** — {_status} — {len(_sakfragor)} sakfrågor"),
        req_resp_accordion("Request / Response", "GET", _url, None, _r.status_code, initial_handlaggning),
        step3_btn,
    ]))
    return (initial_handlaggning,)


@app.cell
def _(mo):
    _DIAGRAM_STEG_4_6 = """sequenceDiagram
        participant Process
        participant regel_komplettering
        participant handlaggning
        participant OUL
        participant identitet_prenumeration

        identitet_prenumeration ->> regel_komplettering: Kafka: IdentitetUppdateradEvent (prenumerationId=handlaggningId)
        rect rgb(70, 120, 160)
            note over regel_komplettering: Hämta handläggningsinformation
            regel_komplettering ->> handlaggning: GET /handlaggning/{handlaggningId}
            handlaggning -->> regel_komplettering: (handlaggning)
        end
        note over regel_komplettering: isKompletteringRequired(handlaggning) -> NEJ

        rect rgb(70, 120, 160)
            regel_komplettering ->> OUL: POST /uppgifter/{uppgiftId}/end (reason=Avslutad)
            OUL -->> regel_komplettering: (status)
            regel_komplettering ->> handlaggning: PUT /handlaggning/{handlaggningId}
            handlaggning -->> regel_komplettering: (handlaggning)
        end
        rect rgb(70, 120, 160)
            regel_komplettering ->> identitet_prenumeration: DELETE /prenumerationer/{prenumerationId=handlaggningId}
            identitet_prenumeration -->> regel_komplettering: (204 No Content)
        end
        regel_komplettering ->> Process: Kafka: RegelResponseMessage (handlaggningId, Utfall.JA)
    """
    mo.vstack([
        mo.md(
            "---\n## Steg 4–6 — Extern källa löser kompletteringen\n\n"
            "Extern källa skriver folkbokföringsdata och triggar ett `IdentitetUppdateradEvent`. "
            "`regel_komplettering` tar emot eventet, läser om handläggningen, konstaterar att "
            "komplettering inte längre krävs, avslutar OUL-uppgiften automatiskt och skickar "
            "`RegelResponseMessage` — utan handläggarinteraktion."
        ),
        mo.accordion({"Sekvensdiagram": mo.mermaid(_DIAGRAM_STEG_4_6)}),
    ])
    return


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Steg 4 — Extern källa skriver folkbokföringsdata

    "
        "`PUT /handlaggning/{id}` — simulerar att en extern källa lägger till en "
        "`folkbokforing`-sakfraga innan `IdentitetUppdateradEvent` triggas.
    """)
    return


@app.cell
def _(mo):
    step4_btn = mo.ui.run_button(label="▶ PUT /handlaggning/{id} — skriv folkbokföring")
    return (step4_btn,)


@app.cell
def _(
    FOLKBOKFORING_OBJEKT_TYP_ID,
    HANDLAGGNING_SVC_BASE_URL,
    handlaggning_id,
    httpx,
    initial_handlaggning,
    mo,
    req_resp_accordion,
    step4_btn,
    uuid_module,
):
    mo.stop(not step4_btn.value, step4_btn)
    mo.stop(handlaggning_id is None, mo.md("⚠️ Kör Steg 1 först."))
    mo.stop(initial_handlaggning is None, mo.md("⚠️ Kör Steg 3 först."))

    import copy as _copy
    _updated = _copy.deepcopy(initial_handlaggning)
    _updated["handlaggning"]["yrkande"]["sakfragorStallningstaganden"].append({
        "id": str(uuid_module.uuid4()),
        "objektTypId": FOLKBOKFORING_OBJEKT_TYP_ID,
        "data": (
            '{"id":"fb-demo-1","fornamn":"Demo","efternamn":"Demosson",'
            '"kon":"MAN","utdelningsadress":"Demogatan 1",'
            '"postnummer":"12345","postort":"Demostaden","careOf":""}'
        ),
    })
    _url = f"{HANDLAGGNING_SVC_BASE_URL}/handlaggning/{handlaggning_id}"
    _req_body = {"handlaggning": _updated["handlaggning"]}
    _r = httpx.put(
        _url,
        headers={"Content-Type": "application/json"},
        json=_req_body,
        timeout=10,
    )
    folkbokforing_written = _r.status_code == 200
    _resp_data = _r.json() if _r.content else _r.text

    mo.output.replace(mo.vstack([
        mo.md(
            f"**HTTP {_r.status_code}** — "
            + ("✅ Folkbokföring skriven" if folkbokforing_written else f"❌ Misslyckades")
        ),
        req_resp_accordion("Request / Response", "PUT", _url, _req_body, _r.status_code, _resp_data),
        step4_btn,
    ]))
    return (folkbokforing_written,)


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Steg 5 — Verifiera att kompletteringsuppgiften inte syns i kön

    "
        "`POST /uppgifter/handlaggare` — visar att kompletteringsuppgiften är `assignable=false` "
        "och därför inte dyker upp i den normala handläggarekön. Processen är parkerad och "
        "väntar på extern data.

    "
        "Authorization-headern identifierar handläggaren med formatet "
        "`Bearer <typId>:<varde>` — samma mönster som individ-identiteter (`idTyp:idVarde`).
    """)
    return


@app.cell
def _(mo):
    step5_btn = mo.ui.run_button(label="▶ POST /uppgifter/handlaggare — verifiera tom kö")
    return (step5_btn,)


@app.cell
def _(
    AUTH,
    OUL_BASE_URL,
    folkbokforing_written,
    handlaggning_id,
    httpx,
    mo,
    req_resp_accordion,
    step5_btn,
):
    mo.stop(not step5_btn.value, step5_btn)
    mo.stop(not folkbokforing_written, mo.md("⚠️ Kör Steg 4 först."))

    _url = f"{OUL_BASE_URL}/uppgifter/handlaggare"
    _req_headers = {"Authorization": AUTH}
    _r = httpx.post(_url, headers=_req_headers, timeout=10)
    _resp_data = _r.json() if (_r.status_code == 200 and _r.content) else None
    _uppgift = (_resp_data or {}).get("operativ_uppgift") or {}
    _task_for_this = str(_uppgift.get("handlaggning_id")) == str(handlaggning_id)
    queue_verified = True

    if _task_for_this:
        _result_md = "⚠️ Oväntad träff — kompletteringsuppgiften syns i kön (borde vara assignable=false)"
    else:
        _result_md = "✅ Ingen uppgift för denna handläggare i kön — kompletteringsuppgiften är korrekt unassignable"

    mo.output.replace(mo.vstack([
        mo.md(f"**HTTP {_r.status_code}** — {_result_md}"),
        req_resp_accordion("Request / Response", "POST", _url, None, _r.status_code, _resp_data, req_headers=_req_headers),
        step5_btn,
    ]))
    return (queue_verified,)


@app.cell
def _(mo):
    mo.md("""
    ---
    ## Steg 6 — Trigga IdentitetUppdateradEvent

    "
        "`POST /test/identitet-data-changed` — simulerar att extern källa publicerar "
        "ett `IdentitetUppdateradEvent`. Kompletteringstjänsten tar emot eventet, läser "
        "om handläggningen (nu med folkbokföring), konstaterar att komplettering inte "
        "längre krävs, stänger OUL-uppgiften automatiskt och skickar `RegelResponseMessage`.
    """)
    return


@app.cell
def _(mo):
    step6_btn = mo.ui.run_button(label="▶ POST /test/identitet-data-changed")
    return (step6_btn,)


@app.cell
def _(
    IDENTITET_PRENUMERATION_BASE_URL,
    IDTYP_PERSONNUMMER,
    INDIVID_PNR,
    handlaggning_id,
    httpx,
    json,
    mo,
    queue_verified,
    req_resp_accordion,
    step6_btn,
    time,
):
    from kafka import KafkaConsumer as _KafkaConsumer, TopicPartition as _TopicPartition
    mo.stop(not step6_btn.value, step6_btn)
    mo.stop(not queue_verified, mo.md("⚠️ Kör Steg 5 först."))

    def _render(post_result=None, kafka_status=None, kafka_event=None, all_received=None, show_btn=False):
        """Render current state; called multiple times as the step progresses."""
        _items = []
        if post_result is not None:
            _status_ok, _resp_status, _req_body, _resp_body, _req_url = post_result
            _items += [
                mo.md(
                    f"**HTTP {_resp_status}** — "
                    + ("✅ POST lyckades" if _status_ok else "❌ POST misslyckades")
                ),
                req_resp_accordion("Request / Response", "POST", _req_url, _req_body, _resp_status, _resp_body),
            ]
        if kafka_status == "waiting":
            _items.append(mo.md("⏳ Väntar på `IdentitetUppdateradEvent` på topic `identitet-uppdaterad-rtf-manuell-komplettering`…"))
        elif kafka_status == "received" and kafka_event is not None:
            _items.append(mo.accordion({"✅ Kafka: IdentitetUppdateradEvent": mo.json(kafka_event)}))
        elif kafka_status == "timeout":
            _items.append(mo.md(
                f"⚠️ Inget meddelande mottaget på topic `identitet-uppdaterad-rtf-manuell-komplettering` "
                f"med `prenumerationId={handlaggning_id}` inom 8 sekunder."
            ))
            _items.append(mo.accordion({
                f"Debug: {len(all_received or [])} meddelande(n) mottagna under poll-fönstret": mo.json(all_received or [])
            }))
        if show_btn:
            _items.append(step6_btn)
        mo.output.replace(mo.vstack(_items) if _items else mo.md(""))

    # Position consumer at end of topic before triggering — so we only see new messages.
    # Retry partition discovery until metadata is available, then do a poll(0) to
    # anchor seek_to_end before the POST fires.
    # identitet-prenumeration publishes IdentitetUppdateradEvent to the subscriber-specific
    # reply topic registered during POST /prenumerationer, not the generic identitet.uppdaterad topic.
    _TOPIC = "identitet-uppdaterad-rtf-manuell-komplettering"
    _consumer = _KafkaConsumer(bootstrap_servers=["localhost:9094"])
    _parts = None
    for _ in range(20):
        _parts = _consumer.partitions_for_topic(_TOPIC)
        if _parts:
            break
        time.sleep(0.2)
    _partitions = [_TopicPartition(_TOPIC, p) for p in (_parts or [0])]
    _consumer.assign(_partitions)
    # end_offsets() is synchronous — fetches current end offsets from the broker right now,
    # before the POST fires. seek() to those exact offsets so we only see messages published
    # after this point. seek_to_end() is lazy and would execute on the first poll (after the
    # POST), risking seeking past the new message.
    _end = _consumer.end_offsets(_partitions)
    for _tp, _off in _end.items():
        _consumer.seek(_tp, _off)

    _render(kafka_status="waiting")

    _url = f"{IDENTITET_PRENUMERATION_BASE_URL}/test/identitet-data-changed"
    _body = {"idtyp": IDTYP_PERSONNUMMER, "idvarde": INDIVID_PNR}
    _r = httpx.post(
        _url,
        headers={"Content-Type": "application/json"},
        json=_body,
        timeout=10,
    )
    identitet_event_sent = _r.status_code == 204
    _resp_body = _r.text if _r.content else None
    _post_result = (identitet_event_sent, _r.status_code, _body, _resp_body, _url)

    _render(post_result=_post_result, kafka_status="waiting")

    # Poll for the IdentitetUppdateradEvent published by identitet-prenumeration.
    _kafka_event = None
    _all_received = []
    if identitet_event_sent:
        _deadline = time.time() + 8
        while time.time() < _deadline and _kafka_event is None:
            for _tp, _msgs in _consumer.poll(timeout_ms=500).items():
                for _msg in _msgs:
                    try:
                        _data = json.loads(_msg.value.decode("utf-8"))
                        _all_received.append(_data)
                        if str(_data.get("prenumerationId")) == str(handlaggning_id):
                            _kafka_event = _data
                            break
                    except Exception:
                        pass
        _kafka_status = "received" if _kafka_event is not None else "timeout"
        _render(post_result=_post_result, kafka_status=_kafka_status,
                kafka_event=_kafka_event, all_received=_all_received, show_btn=True)

    _consumer.close()
    return (identitet_event_sent,)


@app.cell
def _(mo):
    mo.md(
        "---\n## Steg 7 — Verifiera att kompletteringsuppgiften är stängd\n\n"
        "`regel_komplettering` anropade `POST /uppgifter/{uppgiftId}/end` när det tog emot "
        "`IdentitetUppdateradEvent`. Verifierar att uppgiften inte längre syns i "
        "`POST /uppgifter/individ/unassignable` — dvs. att den faktiskt stängts."
    )
    return


@app.cell
def _(mo):
    step7_btn = mo.ui.run_button(label="▶ POST /uppgifter/individ/unassignable — verifiera stängd")
    return (step7_btn,)


@app.cell
def _(
    HANDLAGGARE_TYP_ID,
    HANDLAGGARE_VARDE,
    IDTYP_PERSONNUMMER,
    INDIVID_PNR,
    OUL_BASE_URL,
    handlaggning_id,
    httpx,
    identitet_event_sent,
    mo,
    req_resp_accordion,
    step7_btn,
    uppgift_id,
):
    mo.stop(not step7_btn.value, step7_btn)
    mo.stop(not identitet_event_sent, mo.md("⚠️ Kör Steg 6 först."))

    _url = f"{OUL_BASE_URL}/uppgifter/individ/unassignable"
    _body = {"id_typ": IDTYP_PERSONNUMMER, "id_varde": INDIVID_PNR}
    _headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {HANDLAGGARE_TYP_ID}:{HANDLAGGARE_VARDE}",
    }
    _r = httpx.post(_url, headers=_headers, json=_body, timeout=10)
    _resp = _r.json() if (_r.status_code == 200 and _r.content) else {}
    _tasks = _resp.get("operativa_uppgifter", [])
    _still_open = any(
        str(t.get("handlaggning_id")) == str(handlaggning_id)
        for t in _tasks
    )
    komplettering_closed = _r.status_code == 200 and not _still_open

    mo.output.replace(mo.vstack([
        mo.md(
            "✅ Kompletteringsuppgiften är stängd — syns inte längre i unassignable-kön."
            if komplettering_closed
            else f"❌ Uppgiften finns fortfarande i kön (uppgiftId: `{uppgift_id}`)"
        ),
        req_resp_accordion("Request / Response", "POST", _url, _body, _r.status_code, _resp,
                           req_headers=_headers),
        step7_btn,
    ]))
    return (komplettering_closed,)


@app.cell
def _(mo):
    _DIAGRAM_STEG_8_10 = """sequenceDiagram
        participant Process
        participant regel_rtf_manuell as regel_rtf_manuell
        participant handlaggare as Handläggare
        participant bekrafta_beslut as bekräfta_beslut

        Process ->> regel_rtf_manuell: Kafka: RegelRequestMessage (handlaggningId, aktivitetId)
        note over regel_rtf_manuell: Skapar regeluppgift (assignable=true)
        handlaggare ->> regel_rtf_manuell: GET regeluppgiftens data
        regel_rtf_manuell -->> handlaggare: (handlaggningId, ersättningar, ...)
        handlaggare ->> regel_rtf_manuell: PATCH beslutsutfall (JA/NEJ/DELVIS)
        handlaggare ->> regel_rtf_manuell: POST done
        regel_rtf_manuell ->> Process: Kafka: RegelResponseMessage (Utfall.JA)

        Process ->> bekrafta_beslut: Kafka: RegelRequestMessage
        handlaggare ->> bekrafta_beslut: POST done (bekräftar beslut)
        bekrafta_beslut ->> Process: Kafka: RegelResponseMessage
    """
    mo.vstack([
        mo.md(
            "---\n"
            "## Steg 8–10 — Normal rtf-manuell-flöde\n\n"
            "Kompletteringen är löst. `regel_komplettering` har skickat ett `RegelResponseMessage` "
            "och processen har återupptagits med en vanlig `rtf-manuell`-regeluppgift. "
            "Stegen nedan är identiska med varje annat `rtf-manuell`-flöde — de är inte "
            "specifika för kompletteringsscenariot."
        ),
        mo.accordion({"Sekvensdiagram": mo.mermaid(_DIAGRAM_STEG_8_10)}),
    ])
    return


@app.cell
def _(mo):
    mo.md(
        "---\n## Steg 8 — Hämta regeluppgift\n\n"
        "Polls mot `POST /uppgifter/handlaggare` tills regeluppgiften för denna "
        "handläggning dyker upp i kön."
    )
    return


@app.cell
def _(mo):
    step8_btn = mo.ui.run_button(label="▶ Polla POST /uppgifter/handlaggare — vänta på regeluppgift")
    return (step8_btn,)


@app.cell
def _(
    AUTH,
    OUL_BASE_URL,
    handlaggning_id,
    httpx,
    komplettering_closed,
    mo,
    req_resp_accordion,
    step8_btn,
    time,
):
    mo.stop(not step8_btn.value, step8_btn)
    mo.stop(not komplettering_closed, mo.md("⚠️ Kör Steg 7 först."))

    _url = f"{OUL_BASE_URL}/uppgifter/handlaggare"
    regel_task_url = None
    _attempts8 = 0
    _last_status8 = None
    _last_resp8 = None
    while regel_task_url is None and _attempts8 < 30:
        try:
            _r = httpx.post(_url, headers={"Authorization": AUTH}, timeout=10)
            _last_status8 = _r.status_code
            _last_resp8 = _r.json() if (_r.status_code == 200 and _r.content) else _r.text
            if _r.status_code == 200 and _r.content:
                _uppgift = _r.json().get("operativ_uppgift") or {}
                if str(_uppgift.get("handlaggning_id")) == str(handlaggning_id):
                    _url8 = _uppgift.get("url", "")
                    if "rtf-manuell-komplettering" not in _url8:
                        regel_task_url = _url8
        except Exception:
            pass
        if regel_task_url is None:
            _attempts8 += 1
            time.sleep(1)

    if regel_task_url is None:
        mo.output.replace(mo.vstack([
            mo.md("❌ Ingen regeluppgift hittades efter 30 försök"),
            req_resp_accordion("Request / Response (sista försök)", "POST", _url, None, _last_status8, _last_resp8),
            step8_btn,
        ]))
    else:
        mo.output.replace(mo.vstack([
            mo.md(f"✅ **Regeluppgift** (komplettering auto-resolvad efter {_attempts8 + 1} försök)\n\nURL: `{regel_task_url}`"),
            req_resp_accordion("Request / Response (sista försök)", "POST", _url, None, _last_status8, _last_resp8),
            step8_btn,
        ]))
    return (regel_task_url,)


@app.cell
def _(mo):
    mo.md(
        "---\n## Steg 9 — Sätt beslutsutfall JA\n\n"
        "Hämtar regeldata, sätter beslutsutfall till JA och markerar regelsteget klart."
    )
    return


@app.cell
def _(mo):
    step9_btn = mo.ui.run_button(label="▶ GET + PATCH + done rtf-manuell")
    return (step9_btn,)


@app.cell
def _(
    AUTH,
    RTF_MANUELL_BASE_URL,
    handlaggning_id,
    httpx,
    mo,
    regel_task_url,
    req_resp_accordion,
    step9_btn,
    time,
):
    mo.stop(not step9_btn.value, step9_btn)
    mo.stop(regel_task_url is None, mo.md("⚠️ Kör Steg 8 först."))

    _get_url = f"{RTF_MANUELL_BASE_URL}{regel_task_url}/{handlaggning_id}"
    _get_r = None
    for _i in range(10):
        _get_r = httpx.get(_get_url, headers={"Authorization": AUTH}, timeout=10)
        if _get_r.status_code == 200:
            break
        time.sleep(1)

    _get_resp = _get_r.json() if (_get_r.status_code == 200 and _get_r.content) else {}
    _regel_data = _get_resp.get("data", _get_resp)
    _ersattning_id = (_regel_data.get("ersattningar") or [{}])[0].get("ersattning_id")

    _patch_url = f"{RTF_MANUELL_BASE_URL}{regel_task_url}/{handlaggning_id}"
    _patch_body = {"ersattningar": [{"ersattningId": _ersattning_id, "beslutsutfall": "JA", "avslagsanledning": "-"}]}
    _patch_r = httpx.patch(
        _patch_url,
        headers={"Content-Type": "application/json", "Authorization": AUTH},
        json=_patch_body,
        timeout=10,
    )
    _patch_resp = _patch_r.json() if _patch_r.content else None

    _done_url = f"{RTF_MANUELL_BASE_URL}{regel_task_url}/{handlaggning_id}/done"
    _done_r = httpx.post(_done_url, headers={"Content-Type": "application/json", "Authorization": AUTH}, timeout=10)
    regel_done = _done_r.status_code == 204
    _done_resp = _done_r.text if _done_r.content else None

    mo.output.replace(mo.vstack([
        mo.md(
            f"GET regeldata: **HTTP {_get_r.status_code}** — ersättningId: `{_ersattning_id}`\n\n"
            f"PATCH beslutsutfall JA: **HTTP {_patch_r.status_code}**\n\n"
            f"POST done: **HTTP {_done_r.status_code}** — " + ("✅" if regel_done else "❌")
        ),
        req_resp_accordion("GET regeldata", "GET", _get_url, None, _get_r.status_code, _get_resp),
        req_resp_accordion("PATCH beslutsutfall", "PATCH", _patch_url, _patch_body, _patch_r.status_code, _patch_resp),
        req_resp_accordion("POST done", "POST", _done_url, None, _done_r.status_code, _done_resp),
        step9_btn,
    ]))
    return (regel_done,)


@app.cell
def _(mo):
    mo.md(
        "---\n## Steg 10 — Bekräfta beslut\n\n"
        "Hämtar bekräftabeslutsuppgiften och markerar den klar."
    )
    return


@app.cell
def _(mo):
    step10_btn = mo.ui.run_button(label="▶ Polla + bekräfta beslut")
    return (step10_btn,)


@app.cell
def _(
    AUTH,
    BEKRAFTABESLUT_BASE_URL,
    OUL_BASE_URL,
    handlaggning_id,
    httpx,
    mo,
    regel_done,
    req_resp_accordion,
    step10_btn,
    time,
):
    mo.stop(not step10_btn.value, step10_btn)
    mo.stop(not regel_done, mo.md("⚠️ Kör Steg 9 först."))

    _poll_url = f"{OUL_BASE_URL}/uppgifter/handlaggare"
    _bekrafta_url = None
    _attempts10 = 0
    _last_status10 = None
    _last_resp10 = None
    while _bekrafta_url is None and _attempts10 < 30:
        try:
            _r = httpx.post(_poll_url, headers={"Authorization": AUTH}, timeout=10)
            _last_status10 = _r.status_code
            _last_resp10 = _r.json() if (_r.status_code == 200 and _r.content) else _r.text
            if _r.status_code == 200 and _r.content:
                _uppgift = _r.json().get("operativ_uppgift") or {}
                if str(_uppgift.get("handlaggning_id")) == str(handlaggning_id):
                    _bekrafta_url = _uppgift.get("url")
        except Exception:
            pass
        if _bekrafta_url is None:
            _attempts10 += 1
            time.sleep(1)

    if _bekrafta_url is None:
        mo.output.replace(mo.vstack([
            mo.md("❌ Ingen bekräftabeslutsuppgift hittades efter 30 försök"),
            req_resp_accordion("Request / Response (sista försök)", "POST", _poll_url, None, _last_status10, _last_resp10),
            step10_btn,
        ]))
    else:
        _done_url10 = f"{BEKRAFTABESLUT_BASE_URL}{_bekrafta_url}/{handlaggning_id}/done"
        _done_r10 = httpx.post(
            _done_url10,
            headers={"Content-Type": "application/json", "Authorization": AUTH},
            timeout=10,
        )
        _done10 = _done_r10.status_code == 204
        _done_resp10 = _done_r10.text if _done_r10.content else None
        mo.output.replace(mo.vstack([
            mo.md(
                f"Bekräftabeslutsuppgift: `{_bekrafta_url}`\n\n"
                f"POST done: **HTTP {_done_r10.status_code}** — " + ("✅ Beslut bekräftat" if _done10 else "❌")
            ),
            req_resp_accordion("POST /uppgifter/handlaggare (sista försök)", "POST", _poll_url, None, _last_status10, _last_resp10),
            req_resp_accordion("POST done bekräftabeslut", "POST", _done_url10, None, _done_r10.status_code, _done_resp10),
            step10_btn,
        ]))
    return


@app.cell
def _(mo):
    mo.md(r"""
    ---
    ## ✅ Flödet klart

    Handläggningen är avslutad. Ett `handlaggning-done`-meddelande har publicerats på
    Kafka-topicen `handlaggning-done`.

    **Vad demonstrerades:**
    - Yrkande med `typId="personnummer"` triggar kompletteringskrav och prenumeration
    - Folkbokföringsdata skrivs av extern källa *innan* task-claim (undviker race condition)
    - `IdentitetUppdateradEvent` löser kompletteringen automatiskt — ingen handläggarinteraktion
    - Processen fortsätter direkt till regelsteg och beslutsutfall JA
    """)
    return


if __name__ == "__main__":
    app.run()
