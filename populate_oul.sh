#!/bin/bash

OUL_ENTRY_CREATION_DELAY=10

function create_customer_need_flow() {
    INDIVID_ID="$1"
    FROM="$2"
    TO="$3"

    CUSTOMER_NEED=`curl --fail -X 'POST' \
        'http://localhost:8888/yrkande' \
        -H 'accept: application/json' \
        -H 'Content-Type: application/json' \
        -d '{
                "erbjudandeId": "7d4a6c38-348b-4f46-9278-b1bfeabc0353",
                "yrkandeFrom": "'"${FROM}"'",
                "yrkandeTom": "'"${TO}"'",
                "individYrkandeRoller": [
                    {
                        "individ": {
                            "typId": "c5f2e2b4-9143-4160-8f4b-30c172f0ac05",
                            "varde": "'"${INDIVID_ID}"'"
                        },
                        "yrkandeRollId": "80f5f41f-9e55-4fc2-a076-ad5a651e0a9d"
                    }
                ],
                "produceradeResultat": [
                    {
                        "id": "e7ed3e20-a13d-4978-8523-0426f7ce4b6c",
                        "version": 1,
                        "from": "'"${FROM}"'",
                        "tom": "'"${TO}"'",
                        "yrkandestatus": "e27da561-a8db-4513-8272-ef652b097b16",
                        "typ": "ERSATTNING",
                        "data": "{\"belopp\":40000,\"berakningsgrund\":0,\"ersattningstyp\":{\"id\":\"042bd313-d5ef-4886-97c5-e0a1c828baca\", \"namn\":\"HUNDBIDRAG\"},\"omfattningProcent\":100,\"beslutsutfall\":\"FU\"}"
                    }
                ],
                "handlaggningspecifikationId": "7aa3a1ea-31fd-4049-8a9e-128fe07f4cbe",
                "avsiktsId": "dae2ffc3-07c8-4686-a3d5-58bc942dfe06",
                "replyTo": "handlaggning-done"
            }' 2>/dev/null`

    if [ $? -ne 0 ]; then
        echo "❌ Could not create customer need for individ id ${INDIVID_ID}"
        return 1
    fi

    echo "✅ Created customer need flow for individ ${INDIVID_ID}"
    return 0
}

function assign_case_worker() {
    ID_TYPE="${1}"
    CASE_WORKER_ID="${2}"

    RESPONSE=`curl --fail -X 'POST' \
        "http://localhost:8889/uppgifter/handlaggare" \
        -H 'accept: application/json' -H "authorization: Bearer ${ID_TYPE}:${CASE_WORKER_ID}" 2>/dev/null`

    if [ $? -ne 0 ]; then
        echo "❌ Could not assign task to case worker id ${CASE_WORKER_ID}. Received unexpected HTTP response code."
        return 1
    fi

    ASSIGNED_TASK=`echo "${RESPONSE}" | jq -e '.operativ_uppgift'`

    if [ $? -ne 0 ]; then
        echo "❌ Could not assign task to case worker id ${CASE_WORKER_ID}"
        return 1
    fi

    echo "✅ Assigned task to case worker id ${CASE_WORKER_ID}"
    return 0
}

# Same request shape as create_customer_need_flow, but prints only the
# resulting handlaggning_id to stdout (progress/errors go to stderr) so the
# caller can capture it and drive the rtf-manuell flow below.
function create_yrkande_for_rtf_manuell() {
    INDIVID_ID="$1"
    FROM="$2"
    TO="$3"

    RESPONSE=`curl --fail -s -X 'POST' \
        'http://localhost:8888/yrkande' \
        -H 'accept: application/json' \
        -H 'Content-Type: application/json' \
        -d '{
                "erbjudandeId": "7d4a6c38-348b-4f46-9278-b1bfeabc0353",
                "yrkandeFrom": "'"${FROM}"'",
                "yrkandeTom": "'"${TO}"'",
                "individYrkandeRoller": [
                    {
                        "individ": {
                            "typId": "c5f2e2b4-9143-4160-8f4b-30c172f0ac05",
                            "varde": "'"${INDIVID_ID}"'"
                        },
                        "yrkandeRollId": "80f5f41f-9e55-4fc2-a076-ad5a651e0a9d"
                    }
                ],
                "produceradeResultat": [
                    {
                        "id": "e7ed3e20-a13d-4978-8523-0426f7ce4b6c",
                        "version": 1,
                        "from": "'"${FROM}"'",
                        "tom": "'"${TO}"'",
                        "yrkandestatus": "e27da561-a8db-4513-8272-ef652b097b16",
                        "typ": "ERSATTNING",
                        "data": "{\"belopp\":40000,\"berakningsgrund\":0,\"ersattningstyp\":{\"id\":\"042bd313-d5ef-4886-97c5-e0a1c828baca\", \"namn\":\"HUNDBIDRAG\"},\"omfattningProcent\":100,\"beslutsutfall\":\"FU\"}"
                    }
                ],
                "handlaggningspecifikationId": "7aa3a1ea-31fd-4049-8a9e-128fe07f4cbe",
                "avsiktsId": "dae2ffc3-07c8-4686-a3d5-58bc942dfe06",
                "replyTo": "handlaggning-done"
            }' 2>/dev/null`

    if [ $? -ne 0 ]; then
        echo "❌ Could not create rtf-manuell need flow for individ id ${INDIVID_ID}" >&2
        return 1
    fi

    HANDLAGGNING_ID=`echo "${RESPONSE}" | jq -r '.handlaggning.id'`
    if [ -z "${HANDLAGGNING_ID}" ] || [ "${HANDLAGGNING_ID}" = "null" ]; then
        echo "❌ Unexpected response creating rtf-manuell need flow for individ id ${INDIVID_ID}" >&2
        return 1
    fi

    echo "✅ Created rtf-manuell need flow for individ ${INDIVID_ID}" >&2
    echo "${HANDLAGGNING_ID}"
    return 0
}

# Repeatedly calls the "assign next task" endpoint as the given handläggare
# until it returns the expected handlaggning_id — the queue is FIFO by
# sorteringsordning, so a specific fresh task isn't necessarily picked up on
# the first call, especially on a re-run against an already-populated OUL.
# Gives up after MAX_ATTEMPTS.
function assign_specific_task() {
    ID_TYPE="$1"
    CASE_WORKER_ID="$2"
    EXPECTED_HANDLAGGNING_ID="$3"
    MAX_ATTEMPTS=60

    for i in $(seq 1 ${MAX_ATTEMPTS}); do
        RESPONSE=`curl --fail -s -X 'POST' \
            "http://localhost:8889/uppgifter/handlaggare" \
            -H 'accept: application/json' -H "authorization: Bearer ${ID_TYPE}:${CASE_WORKER_ID}" 2>/dev/null`
        if [ $? -eq 0 ]; then
            GOT_ID=`echo "${RESPONSE}" | jq -r '.operativ_uppgift.handlaggning_id // empty'`
            if [ "${GOT_ID}" = "${EXPECTED_HANDLAGGNING_ID}" ]; then
                return 0
            fi
        fi
    done

    echo "❌ Gave up waiting for handlaggning_id ${EXPECTED_HANDLAGGNING_ID} after ${MAX_ATTEMPTS} attempts"
    return 1
}

# Resolves the komplettering step for a handläggning already routed through
# rtf-manuell (see create_yrkande_for_rtf_manuell — folkbokford=false, i.e. a
# personnummer ending "9999", is what triggers the UTREDNING/rtf-manuell path
# per RtfDecisionModel.dmn), leaving the resulting plain "/regel/rtf-manuell"
# task assigned to the portal's mock login identity ("Lisa Tass" — card /
# a1a1a1a1-0000-0000-0000-000000000001) so it shows up immediately in
# rimfrost-portal-handlaggare without any further manual steps.
function resolve_rtf_manuell_task() {
    HANDLAGGNING_ID="$1"
    INDIVID_ID="$2"
    REAL_HANDLAGGARE_TYPID="116759e4-18fd-4209-849c-90abbd257d22"
    REAL_HANDLAGGARE_VARDE="3f439f0d-a915-42cb-ba8f-6a4170c6011f"
    PORTAL_MOCK_TYPID="card"
    PORTAL_MOCK_VARDE="a1a1a1a1-0000-0000-0000-000000000001"

    if ! assign_specific_task "${REAL_HANDLAGGARE_TYPID}" "${REAL_HANDLAGGARE_VARDE}" "${HANDLAGGNING_ID}"; then
        echo "❌ Could not assign komplettering task for handlaggning ${HANDLAGGNING_ID}"
        return 1
    fi

    curl --fail -s -X 'PATCH' \
        "http://localhost:8890/regel/rtf-manuell/${HANDLAGGNING_ID}/komplettering" \
        -H 'Content-Type: application/json' \
        -d '{"personnummer":"'"${INDIVID_ID}"'","avsikt":"dae2ffc3-07c8-4686-a3d5-58bc942dfe06"}' > /dev/null
    if [ $? -ne 0 ]; then
        echo "❌ Could not register komplettering svar for handlaggning ${HANDLAGGNING_ID}"
        return 1
    fi

    curl --fail -s -X 'POST' \
        "http://localhost:8890/regel/rtf-manuell/${HANDLAGGNING_ID}/komplettering/done" > /dev/null
    if [ $? -ne 0 ]; then
        echo "❌ Could not close komplettering for handlaggning ${HANDLAGGNING_ID}"
        return 1
    fi

    if ! assign_specific_task "${PORTAL_MOCK_TYPID}" "${PORTAL_MOCK_VARDE}" "${HANDLAGGNING_ID}"; then
        echo "❌ Could not assign rtf-manuell task for handlaggning ${HANDLAGGNING_ID}"
        return 1
    fi

    echo "✅ rtf-manuell task ready for handlaggning ${HANDLAGGNING_ID}, assigned to Lisa Tass"
    return 0
}

if ! command -v curl &> /dev/null; then
    echo "❌ curl is not installed. Please install it first:"
    echo "sudo apt-get install curl"
    exit 1
fi

if ! command -v jq &> /dev/null; then
    echo "❌ jq is not installed. Please install it first:"
    echo "sudo apt-get install jq"
    exit 1
fi

create_customer_need_flow "19900101-9999" "2025-01-10T12:15:50-04:00" "2025-01-10T17:00:00-04:00"
create_customer_need_flow "19990101-9999" "2025-03-12T10:22:53+02:00" "2025-03-12T16:00:00+02:00"
create_customer_need_flow "19900101-1234" "2025-08-01T08:00:00+01:00" "2025-09-02T17:00:00+01:00"
create_customer_need_flow "19900101-4444" "2025-08-01T08:00:00+01:00" "2025-09-02T17:00:00+01:00"

RTF_MANUELL_INDIVID="19950101-9999"
RTF_MANUELL_HANDLAGGNING_ID=`create_yrkande_for_rtf_manuell "${RTF_MANUELL_INDIVID}" "2025-12-01T08:00:00+01:00" "2025-12-31T17:00:00+01:00"`

echo "⏳ Sleeping ${OUL_ENTRY_CREATION_DELAY} seconds to allow for OUL entry creation"
sleep ${OUL_ENTRY_CREATION_DELAY}

# Resolved before the generic assign_case_worker call below so its own
# assign-next loop can claim the rtf-manuell task deterministically, before
# assign_case_worker has a chance to grab it for the wrong identity first.
if [ -n "${RTF_MANUELL_HANDLAGGNING_ID}" ]; then
    resolve_rtf_manuell_task "${RTF_MANUELL_HANDLAGGNING_ID}" "${RTF_MANUELL_INDIVID}"
fi

assign_case_worker "116759e4-18fd-4209-849c-90abbd257d22" "3f439f0d-a915-42cb-ba8f-6a4170c6011f"
