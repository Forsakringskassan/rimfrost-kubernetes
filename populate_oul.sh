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

# krav (Lars Persson, Slack, 2026-09-10):
# "Halvdum fundering, men varför inte bara ha fler poster i
# create_yrkande_for_rtf_manuell så att komplettering inte triggas till att
# börja med istället för att trigga komplettering, och sedan lösa det?
# D.v.s., varför inte bara ha en förfrågan som uppfyller villkoren till att
# börja med?"
#
# Reaching the plain "/regel/rtf-manuell" task previously required triggering
# komplettering (personnummer missing) and then resolving it via the
# komplettering PATCH/done endpoints. typId "personnummer" (the literal
# string, not the reference-data UUID) is normally only ever written by
# registerSvar() — the komplettering PATCH handler — once a handläggare
# supplies the missing personnummer. Sending that literal value directly here
# satisfies checkKomplettering() immediately, so this request already meets
# the condition from the start: no komplettering step is triggered at all,
# and the plain rtf-manuell task is created straight away.
function create_yrkande_for_rtf_manuell() {
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
                            "typId": "personnummer",
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
        echo "❌ Could not create rtf-manuell need flow for individ id ${INDIVID_ID}"
        return 1
    fi

    echo "✅ Created rtf-manuell need flow for individ ${INDIVID_ID}"
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
create_yrkande_for_rtf_manuell "19950101-9999" "2025-12-01T08:00:00+01:00" "2025-12-31T17:00:00+01:00"

echo "⏳ Sleeping ${OUL_ENTRY_CREATION_DELAY} seconds to allow for OUL entry creation"
sleep ${OUL_ENTRY_CREATION_DELAY}

assign_case_worker "116759e4-18fd-4209-849c-90abbd257d22" "3f439f0d-a915-42cb-ba8f-6a4170c6011f"
