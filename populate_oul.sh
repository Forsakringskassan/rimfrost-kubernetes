#!/bin/bash

OUL_ENTRY_CREATION_DELAY=10

function create_customer_need_flow() {
    SSN="$1"
    PERIOD_START="$2"
    PERIOD_STOP="$3"

    CUSTOMER_NEED=`curl --fail -X 'POST' \
        'http://localhost:8888/kundbehov' \
        -H 'accept: application/json' \
        -H 'Content-Type: application/json' \
        -d '{
            "persnr": "'"${SSN}"'",
            "formanstyp": "VAH",
            "period": {
                "start": "'"${PERIOD_START}"'",
                "slut": "'"${PERIOD_STOP}"'"
            }
        }' 2>/dev/null`

    if [ $? -ne 0 ]; then
        echo "❌ Could not create customer need for ssn ${SSN}"
        return 1
    fi

    CUSTOMER_NEED_ID=`echo "${CUSTOMER_NEED}" | jq -e '.kundbehov["id"]' | sed -E 's/"(.*)"/\1/'`

    if [ $? -ne 0 ]; then
        echo "❌ Could not extract customer need id from customer need for ssn ${SSN}"
        return 1
    fi

    curl --fail -X 'POST' \
        'http://localhost:8888/kundbehovsflode' \
        -H 'accept: application/json' \
        -H 'Content-Type: application/json' \
        -d '{
            "kundbehovId": "'"${CUSTOMER_NEED_ID}"'"
        }' > /dev/null 2>&1

    if [ $? -ne 0 ]; then
        echo "❌ Could not create customer need flow for ssn ${SSN}"
        return 1
    fi

    echo "✅ Created customer need flow for ssn ${SSN}"
    return 0
}

function assign_case_worker() {
    CASE_WORKER_ID="${1}"

    RESPONSE=`curl --fail -X 'POST' \
        "http://localhost:8889/uppgifter/handlaggare/${CASE_WORKER_ID}" \
        -H 'accept: application/json' 2>/dev/null`

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
create_customer_need_flow "19850307-9999" "2025-03-12T10:22:53+02:00" "2025-03-12T16:00:00+02:00"
create_customer_need_flow "19940712-9999" "2025-08-01T08:00:00+01:00" "2025-09-02T17:00:00+01:00"

echo "⏳ Sleeping ${OUL_ENTRY_CREATION_DELAY} seconds to allow for OUL entry creation"
sleep ${OUL_ENTRY_CREATION_DELAY}

assign_case_worker "469ddd20-6796-4e05-9e18-6a95953f6cb3"
