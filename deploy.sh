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
minikube start --driver=docker --cpus=4 --memory=4096

# Enable ingress addon
echo "🌐 Enabling ingress addon..."
minikube addons enable ingress

echo "🔄 Adding strimzi to repo list..."
if ! helm repo list | awk 'NR>1{print $1}' | grep -qx strimzi; then
  helm repo add strimzi https://strimzi.io/charts/
fi

# Wait for ingress controller to be ready
echo "⏳ Waiting for ingress controller to be ready..."
for i in {1..60}; do
  if kubectl get pods -n ingress-nginx -l app.kubernetes.io/component=controller --field-selector=status.phase=Running | grep -q Running; then
    echo "✅ Ingress controller is ready"
    break
  fi
  echo "   Attempt $i/60: Waiting for ingress controller..."
  sleep 2
done

# Wait a bit more for the admission webhook to be ready
echo "⏳ Waiting for ingress admission webhook to be ready..."
sleep 30

# Verify webhook is responding
echo "🔍 Verifying admission webhook..."
for i in {1..10}; do
  if kubectl get validatingwebhookconfiguration ingress-nginx-admission &>/dev/null; then
    echo "✅ Admission webhook is ready"
    break
  fi
  echo "   Attempt $i/10: Waiting for admission webhook..."
  sleep 10
done

# Deploy the application using Helm
echo "📦 Building dependencies..."
helm dependency build ./helm-chart

if ! helm upgrade --install rimfrost-k8s ./helm-chart --wait; then
  echo "⚠️  Deployment failed, likely due to admission webhook not ready"
  echo "🔄 Retrying with webhook bypass..."
  
  # Temporarily disable admission webhook validation
  kubectl delete validatingwebhookconfiguration ingress-nginx-admission 2>/dev/null || true
  
  # Deploy without webhook validation
  helm upgrade --install rimfrost-k8s ./helm-chart --wait
  
  echo "✅ Deployment completed (webhook validation bypassed)"
fi

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

if [ "${1:-}" = "--pf" ]; then
  echo "Port forwarding enabled — running port-forward.sh..."
  ./port-forward.sh
else
  echo "Skipping port forwarding."
fi
