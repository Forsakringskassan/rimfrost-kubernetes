#!/bin/bash

set -e

# Remove the Helm release
echo "📦 Removing Helm release..."
helm uninstall rimfrost-k8s || echo "Release not found, continuing..."

echo "removing any Chart.lock"
rm -f helm-chart/Chart.lock

# Stop Minikube
echo "🛑 Stopping Minikube..."
minikube stop

if [ -f portforward.pid ]; then
    echo "🛑 Stopping port-forward..."
    kill "$(cat portforward.pid)" 2>/dev/null || true
    rm -f portforward.pid
fi
