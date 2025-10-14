#!/bin/bash

set -e

# Remove the Helm release
echo "📦 Removing Helm release..."
helm uninstall template-k8s || echo "Release not found, continuing..."

# Stop Minikube
echo "🛑 Stopping Minikube..."
minikube stop
