# Krav — rimfrost-kubernetes

Kraven beskriver de scenarier som integrationstesterna i det här repot verifierar mot den
driftsatta miljön. Krav på tjänsterna själva står i respektive repo och upprepas inte.

Hänvisningar till IA-, FRMM- och FBFF-krav avser kraven i
`rimfrost-service-informationsaccess-asyncapi`, `rimfrost-framework-regel-manuell`
respektive `rimfrost-framework-bff`.

## Funktionella krav

### RK-FR-01 — Scenario: informationsaccess när en regel öppnas

Scenariot verifierar att ett informationsaccess-event publiceras när en handläggare öppnar en
regel, från anrop till publicerat event. Regelns backend returnerar informationsaccessdata i
GET-svaret (FRMM-FR-09), och regelns BFF publicerar eventet.

- **RK-FR-01.1** Scenariot ska hämta regelns data genom regelns BFF, inte direkt från regelns
  backend, eftersom det är BFF:en som publicerar eventet.
- **RK-FR-01.2** Anropet till BFF:en ska bära token för den handläggare som uppgiften är
  tilldelad, i `Authorization`, så att identiteten når regelns backend (FBFF-FR-03.1).
- **RK-FR-01.3** Scenariot ska läsa eventen från topicen `informationsaccess`, utan att läsa
  BFF:ens interna tillstånd (IA-NFR-03.1). Topicen har samma namn som kanalen i asyncapi:t
  och sätts statiskt för regelns BFF i miljöns konfiguration (IA-FR-09.2).

### RK-FR-02 — Förväntade event

- **RK-FR-02.1** Ett anrop som hämtar regelns data ska ge ett event för uppgiften, med allt
  som visades vid anropet. Om samma event levereras flera gånger har det samma `id`
  (IA-FR-02.1, IA-FR-08.1).
- **RK-FR-02.2** Två anrop som hämtar regelns data ska ge två event med olika `id`, eftersom
  varje laddning loggas för sig (IA-FR-01.2, IA-FR-08.1).
- **RK-FR-02.3** Ett anrop som inte returnerar personuppgifter, t.ex. hämtning av regelns
  uppgiftsbeskrivning, ska inte ge något event inom scenariots väntetid (IA-FR-01.1).

### RK-FR-03 — Eventets innehåll

Kraven gäller det event som ett anrop enligt RK-FR-02.1 ger.

- **RK-FR-03.1** `anvandare` ska motsvara identiteten i den token som scenariot skickade
  (IA-FR-05.1).
- **RK-FR-03.2** `kalla` ska vara BFF:ens applikationsnamn, för rtf-manuell
  `rimfrost-regel-rtf-manuell-bff` som i asyncapi:ts exempel (IA-FR-05.3).
- **RK-FR-03.3** `handlaggningId` ska vara den handläggning som scenariot skapade, och
  `uppgiftId` ska vara id:t på den OUL-uppgift som handläggaren fick (IA-FR-06.1,
  FRMM-FR-09.2).
- **RK-FR-03.4** `regelId` och `regelVersion` ska vara desamma som i regelns `config.yaml`
  (IA-FR-06.2, FRMM-FR-09.3).
- **RK-FR-03.5** `tidpunkt` ska ligga mellan när scenariot skickade anropet och när svaret
  kom, med marginal för klockskillnad mellan miljön och testet (IA-FR-05.2).
- **RK-FR-03.6** `visadInformation` ska vara samma data som BFF:en returnerade till klienten,
  dvs. `data` i regelns GET-svar (IA-FR-01.2, FRMM-FR-09.1).

### RK-FR-04 — Scenario: processvyn för en uppgift från VAH

Scenariot verifierar att portalen kan visa processvyn ovanför en uppgift som VAH har skapat.
Processen skickar med aktivitetens ID och processvy-länken när en regel startas, och OUL sparar
dem på uppgiften. Portalen laddar processens mikrofrontend, som hämtar stegen genom processens
BFF.

- **RK-FR-04.1** En uppgift som VAH skapar ska ha `aktivitet_id` och `processvy_url` i OUL.
  `processvy_url` ska vara `/process/vah`, som i process-vah:s konfiguration.
- **RK-FR-04.2** process-vah ska svara på `GET /processvy/steg` för uppgiftens `aktivitet_id`,
  med processens steg och exakt ett steg som pågår.
- **RK-FR-04.3** process-vah-bff ska nå process-vah i klustret och svara på `GET /api/steg` för
  samma aktivitet.
- **RK-FR-04.4** process-vab-bff ska nå process-vab i klustret. En aktivitet som inte finns i
  processbeskrivningen ska ge processens 4xx-svar, inte ett fel för att process-vab inte nåddes.

## Avgränsning

- Informationsaccess-scenariot omfattar när en regel öppnas. Om portalens uppgiftslista ska loggas är en öppen
  punkt i informationsaccess-kraven och ingår inte.
- Scenariot omfattar `rimfrost-regel-rtf-manuell-bff`. Övriga regel-BFF:er läggs till när de
  publicerar event.
- Mimers konsumtion av eventet och leveransen till ILOGG verifieras inte.
- Beteendet när eventet inte kan publiceras (IA-FR-07) verifieras inte.
- Processvyns scenario omfattar uppgifter från VAH. För VAB verifieras bara att
  process-vab-bff når process-vab. Att processvyn faktiskt visas i portalen verifieras av
  portalens e2e-tester.
- Uppgifter som skapades innan processerna och OUL uppgraderades saknar `aktivitet_id` och
  `processvy_url`. Fälten är valfria, så den lokala miljön behöver inte återställas. Sådana
  uppgifter visas utan processvy.

## Förutsättningar

Scenariot kräver att regelns backend returnerar informationsaccessdata (FKPOC-1103) och att
regelns BFF publicerar eventet (RTFB-FR-04), och att båda är driftsatta i miljön. BFF:en
publicerar via ILogg i `rimfrost-framework-bff` (FKPOC-1098) och behöver Kafka i miljön.

Processvyns scenario kräver att process-vah skickar med aktivitetens ID och processvy-länken
när en regel startas (process-vah 1.2.3), att OUL sparar och returnerar fälten (OUL 1.9.1,
FKPOC-1161), att regeln förmedlar dem till OUL (rtf-manuell 1.4.0), och att process-vah-bff
och process-vab-bff är driftsatta i miljön (FKPOC-1170, FKPOC-1171).

## Öppna punkter

| Punkt | Berör |
|---|---|
| Om scenariot även ska verifiera att inget event publiceras när regeln nekar åtkomst på grund av skyddad identitet (FRMM-FR-09.4, IA-FR-01.1). I miljön har en individ skyddad identitet när personnumret slutar på `4444`, men en sådan individ når inte rtf-manuell eftersom den maskinella regeln då ger utfall Ja. Med åtkomstkontrollen i regelramverken släpps dessutom en tilldelad handläggare med SID-behörighet igenom, så fallet behöver formuleras om när de versionerna driftsätts här | RK-FR-02 |
