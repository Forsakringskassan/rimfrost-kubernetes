#!/bin/bash

set -e

# Check if minikube is installed
if ! command -v minikube &> /dev/null; then
    echo "❌ Minikube is not installed. Please install it first:"
    echo "   https://minikube.sigs.k8s.io/docs/start/"
    exit 1
fi

# Check if helm is installed
if ! command -v helm &> /dev/null; then
    echo "❌ Helm is not installed. Please install it first:"
    echo "   https://helm.sh/docs/intro/install/"
    exit 1
fi

# Check if kubectl is installed
if ! command -v kubectl &> /dev/null; then
    echo "❌ kubectl is not installed. Please install it first:"
    echo "   https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/"
    echo "   Or install via snap: sudo snap install kubectl --classic"
    exit 1
fi

# Start minikube if not running
echo "🔧 Starting Minikube..."
minikube start --driver=docker --cpus=4 --memory=8192

# Enable ingress addon (skip if already enabled)
echo "🌐 Enabling ingress addon..."
if minikube addons list | awk '/\| ingress/{print}' | grep -q enabled; then
  echo "✅ Ingress addon already enabled — skipping"
else
  minikube addons enable ingress
fi

echo "🔄 Adding strimzi to repo list..."
if ! helm repo list | awk 'NR>1{print $1}' | grep -qx strimzi; then
  helm repo add strimzi https://strimzi.io/charts/
fi

# Wait for ingress controller pod and admission webhook to be ready
echo "⏳ Waiting for ingress controller to be ready..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s

# The admission webhook cert is self-signed and not trusted by the API server in minikube,
# which causes Helm to fail when creating Ingress resources. Patching failurePolicy to Ignore
# prevents cert validation failures from blocking installs in this local dev cluster.
echo "🔑 Patching ingress-nginx admission webhook failurePolicy to Ignore..."
kubectl patch validatingwebhookconfiguration ingress-nginx-admission \
  --type='json' \
  -p='[{"op":"replace","path":"/webhooks/0/failurePolicy","value":"Ignore"}]' \
  2>/dev/null || true

# Deploy the application using Helm
echo "📦 Building dependencies..."
helm dependency build ./helm-chart

helm upgrade --install rimfrost-k8s ./helm-chart --wait --timeout 15m

# Get the ingress IP
echo "🔍 Getting ingress information..."
INGRESS_IP=$(minikube ip)
echo "Ingress IP: $INGRESS_IP"

echo ""
echo "✅ Deployment completed!"
echo ""
echo "🌍 Your applications are available at:"
echo ""
echo "   http://$INGRESS_IP/"
echo ""

if [ "${1:-}" = "--no-pf" ]; then
  echo "Skipping port forwarding."
else
  echo "Starting port forwarding..."
  ./port-forward.sh
fi
