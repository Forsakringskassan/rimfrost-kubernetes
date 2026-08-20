#!/bin/bash

if ! command -v kubectl &> /dev/null; then
    echo "❌ kubectl is not installed. Please install it first:"
    echo "sudo snap install kubectl --classic"
    exit 1
fi

PG_POD=$(kubectl get pod -n default --no-headers -o custom-columns=":metadata.name" | grep postgresql | head -1)

if [ -z "${PG_POD}" ]; then
    echo "❌ Could not find a postgresql pod in namespace 'default'."
    exit 1
fi

echo "🧹 Clearing stale OUL tasks (operativt_uppgiftslager.uppgift) on pod ${PG_POD}..."

kubectl exec -n default "${PG_POD}" -- env PGPASSWORD=rimfrost-test psql -U rimfrost-test -d rimfrost-test \
    -c "TRUNCATE TABLE operativt_uppgiftslager.uppgift CASCADE;" > /dev/null

if [ $? -ne 0 ]; then
    echo "❌ Failed to clear the uppgift table."
    exit 1
fi

echo "✅ Cleared operativt_uppgiftslager.uppgift"
