#!/usr/bin/env bash

# Strict mode: safer shells by default
set -Eeuo pipefail
IFS=$'\n\t'

# -----------------------------
# Configuration
# -----------------------------
NAMESPACE="default"
PROFILE="minikube"
RELEASE="rimfrost-k8s"
CHART_PATH="./helm-chart"
VALUES_FILES=()
TIMEOUT="5m"
WAIT=true
PORT_FORWARD=false
CREATE_NS=true
EXTRA_HELM_ARGS=()
KUBE_CONTEXT=""

# -----------------------------
# Logging helpers
# -----------------------------
log()    { printf "\033[1;34m[INFO]\033[0m %s\n" "$*"; }
warn()   { printf "\033[1;33m[WARN]\033[0m %s\n" "$*"; }
error()  { printf "\033[1;31m[ERR ]\033[0m %s\n" "$*"; }
success(){ printf "\033[1;32m[DONE]\033[0m %s\n" "$*"; }

on_exit() {
  local ec=$?
  if [[ $ec -ne 0 ]]; then error "Script failed with exit code $ec"; fi
}
trap on_exit EXIT

# -----------------------------
# Usage
# -----------------------------
usage(){
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  -n, --namespace <ns>     Kubernetes namespace (default: $NAMESPACE)
  -r, --release <name>     Helm release name (default: $RELEASE)
  -p, --profile <name>     Minikube profile (default: $PROFILE)
  -c, --chart <path>       Helm chart path (default: $CHART_PATH)
  -f, --values <file>      Additional Helm values file (repeatable)
  -t, --timeout <dur>      Helm/K8s wait timeout (default: $TIMEOUT)
  --no-wait                Do not wait for resources to be ready
  --no-create-ns           Do not create namespace if missing
  --context <name>         Use this kube context instead of minikube
  --pf                     Run ./port-forward.sh after deploy
  --                       End of options; pass the rest to Helm
USAGE
}

# -----------------------------
# Parse args
# -----------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n|--namespace)    NAMESPACE="$2"; shift 2;;
    -r|--release)      RELEASE="$2"; shift 2;;
    -p|--profile)      PROFILE="$2"; shift 2;;
    -c|--chart)        CHART_PATH="$2"; shift 2;;
    -f|--values)       VALUES_FILES+=("-f" "$2"); shift 2;;
    -t|--timeout)      TIMEOUT="$2"; shift 2;;
    --no-wait)         WAIT=false; shift;;
    --no-create-ns)    CREATE_NS=false; shift;;
    --context)         KUBE_CONTEXT="$2"; shift 2;;
    --pf)              PORT_FORWARD=true; shift;;
    -h|--help)         usage; exit 0;;
    --)                shift; EXTRA_HELM_ARGS+=("$@"); break;;
    *)                 EXTRA_HELM_ARGS+=("$1"); shift;;
  esac
done

# -----------------------------
# Preconditions
# -----------------------------
need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    error "$1 is not installed."
    case "$1" in
      minikube) echo "See https://minikube.sigs.k8s.io/docs/start/";;
      helm)     echo "See https://helm.sh/docs/intro/install/";;
      kubectl)  echo "See https://kubernetes.io/docs/tasks/tools/";;
    esac
    exit 1
  fi
}
need kubectl
need helm
need minikube

# If a context was provided, prefer it; else ensure minikube is running
if [[ -n "$KUBE_CONTEXT" ]]; then
  log "Using kube context: $KUBE_CONTEXT"
  kubectl config use-context "$KUBE_CONTEXT" >/dev/null
else
  if ! minikube status -p "$PROFILE" >/dev/null 2>&1; then
    log "Starting minikube profile '$PROFILE'..."
    minikube start -p "$PROFILE"
  else
    log "Minikube profile '$PROFILE' is running."
  fi
  kubectl config use-context "minikube" >/dev/null
fi

# Enable ingress addon (idempotent)
if minikube addons list -p "$PROFILE" | grep -qE "ingress\s+enabled"; then
  log "Minikube ingress addon already enabled."
else
  log "Enabling minikube ingress addon..."
  minikube addons enable ingress -p "$PROFILE"
fi

# --- Ingress readiness and CA bundle repair ---
wait_for_ingress() {
  log "Waiting for ingress-nginx components to be ready..."

  # 0) Wait for namespace to appear (addon may take a moment)
  for i in {1..20}; do
    kubectl get ns ingress-nginx >/dev/null 2>&1 && break
    sleep 2
    [[ $i -eq 20 ]] && warn "ingress-nginx namespace not visible yet"
  done

  # 1) Jobs that create/patch certs (if missing, keep waiting a bit)
  for name in ingress-nginx-admission-create ingress-nginx-admission-patch; do
    for i in {1..10}; do
      kubectl -n ingress-nginx get job "$name" >/dev/null 2>&1 && break
      sleep 2
    done
    kubectl -n ingress-nginx wait --for=condition=complete "job/$name" --timeout="$TIMEOUT" 2>/dev/null || true
  done

  # 2) Controller Deployment healthy
  kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout="$TIMEOUT" || warn "ingress-nginx-controller not fully rolled out"
  kubectl -n ingress-nginx wait --for=condition=Available deployment/ingress-nginx-controller --timeout="$TIMEOUT" 2>/dev/null || true
  kubectl -n ingress-nginx wait --for=condition=Ready pod -l app.kubernetes.io/component=controller --timeout="$TIMEOUT" 2>/dev/null || true

  # 3) Admission service endpoints present
  for i in {1..30}; do
    EP=$(kubectl -n ingress-nginx get endpoints ingress-nginx-controller-admission -o jsonpath='{.subsets[0].addresses[0].ip}' 2>/dev/null || true)
    if [[ -n "$EP" ]]; then
      log "Ingress admission webhook endpoint is present ($EP)."
      break
    fi
    sleep 2
    [[ $i -eq 30 ]] && warn "Timed out waiting for ingress admission endpoints"
  done

  # 4) Ensure Secret with tls.crt exists
  for i in {1..30}; do
    TLSCRT=$(kubectl -n ingress-nginx get secret ingress-nginx-admission -o jsonpath='{.data.tls\.crt}' 2>/dev/null || true)
    [[ -n "$TLSCRT" ]] && break
    sleep 2
    [[ $i -eq 30 ]] && warn "Secret ingress-nginx-admission missing or without tls.crt"
  done

  # 5) Ensure ValidatingWebhookConfiguration has a CA bundle; if empty, patch from secret
  CAB=$(kubectl get validatingwebhookconfiguration ingress-nginx-admission -o jsonpath='{.webhooks[0].clientConfig.caBundle}' 2>/dev/null || true)
  if [[ -z "$CAB" && -n "$TLSCRT" ]]; then
    warn "Webhook CA bundle empty; attempting to patch from 'ingress-nginx-admission' secret"
    COUNT=$(kubectl get validatingwebhookconfiguration ingress-nginx-admission -o jsonpath='{len .webhooks}' 2>/dev/null || echo 0)
    if [[ "$COUNT" -gt 0 ]]; then
      for idx in $(seq 0 $((COUNT-1))); do
        kubectl patch validatingwebhookconfiguration ingress-nginx-admission --type=json \
          -p='[{"op":"replace","path":"/webhooks/'"$idx"'/clientConfig/caBundle","value":"'"$TLSCRT"'"}]' 2>/dev/null || true
      done
      CAB=$(kubectl get validatingwebhookconfiguration ingress-nginx-admission -o jsonpath='{.webhooks[0].clientConfig.caBundle}' 2>/dev/null || true)
      [[ -n "$CAB" ]] && log "Patched ValidatingWebhookConfiguration CA bundle from secret."
    fi
  fi

  # 6) If still empty, recycle addon once to regenerate certs
  if [[ -z "$CAB" ]]; then
    warn "CA bundle still empty. Recycling ingress addon to regenerate admission certs..."
    minikube addons disable ingress -p "$PROFILE" || true
    sleep 2
    minikube addons enable ingress -p "$PROFILE" || true
    # Wait again briefly for patch job
    kubectl -n ingress-nginx wait --for=condition=complete job/ingress-nginx-admission-patch --timeout="$TIMEOUT" 2>/dev/null || true
    # Re-check CA
    CAB=$(kubectl get validatingwebhookconfiguration ingress-nginx-admission -o jsonpath='{.webhooks[0].clientConfig.caBundle}' 2>/dev/null || true)
    [[ -n "$CAB" ]] && log "CA bundle present after addon recycle."
  fi
}
wait_for_ingress

# Namespace
if $CREATE_NS; then
  if ! kubectl get ns "$NAMESPACE" >/dev/null 2>&1; then
    log "Creating namespace '$NAMESPACE'..."
    kubectl create ns "$NAMESPACE"
  else
    log "Namespace '$NAMESPACE' exists."
  fi
fi

# Build Helm command
HELM_ARGS=( upgrade --install "$RELEASE" "$CHART_PATH" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  --timeout "$TIMEOUT" \
  --history-max 10 )

if $WAIT; then HELM_ARGS+=( --wait --atomic ); fi
if [[ ${#VALUES_FILES[@]} -gt 0 ]]; then HELM_ARGS+=( "${VALUES_FILES[@]}" ); fi
if [[ ${#EXTRA_HELM_ARGS[@]} -gt 0 ]]; then HELM_ARGS+=( "${EXTRA_HELM_ARGS[@]}" ); fi

# Lint chart if available
if [[ -f "$CHART_PATH/Chart.yaml" ]]; then
  log "Helm lint..."
  helm lint "$CHART_PATH" || warn "helm lint reported issues"
fi

# Deploy
log "Deploying release '$RELEASE' to namespace '$NAMESPACE'..."
sleep 2
helm "${HELM_ARGS[@]}"

# Optionally wait for common workload types to be ready (extra safety)
if $WAIT; then
  log "Waiting for Pods to be Ready..."
  kubectl -n "$NAMESPACE" wait --for=condition=Ready pod --all --timeout="$TIMEOUT" || warn "Some Pods did not reach Ready within $TIMEOUT"
fi

# Discover the local ingress IP/URL (minikube service or node IP)
INGRESS_IP=""
if minikube ip >/dev/null 2>&1; then
  INGRESS_IP=$(minikube ip)
fi

# Try to fetch hosts from Ingress resources
ING_HOSTS=$(kubectl -n "$NAMESPACE" get ingress -o jsonpath='{range .items[*]}{.spec.rules[*].host}{"\n"}{end}' 2>/dev/null || true)

success "Deployment completed!"

# On local minikube, ingress endpoints often require an active tunnel
if [[ -z "$KUBE_CONTEXT" ]]; then
  case "$(uname -s)" in
    MINGW*|CYGWIN*|MSYS* ) warn "On Windows with Minikube + ingress, run 'minikube tunnel' in a separate terminal for 127.0.0.1 access." ;;
    * ) warn "If Ingress hosts aren't reachable locally, run: minikube tunnel" ;;
  esac
fi

if [[ -n "$ING_HOSTS" ]]; then
  log "Ingress host(s):"; printf "  - %s\n" $ING_HOSTS
elif [[ -n "$INGRESS_IP" ]]; then
  log "Cluster IP: http://$INGRESS_IP/"
else
  warn "Could not determine ingress/cluster IP automatically."
fi

# Optional port-forwarding
if $PORT_FORWARD; then
  if [[ -x ./port-forward.sh ]]; then
    log "Starting port-forwarding via ./port-forward.sh ..."
    ./port-forward.sh || warn "port-forward script exited non-zero"
  else
    warn "--pf set, but ./port-forward.sh not found or not executable"
  fi
else
  log "Skipping port-forwarding. Use --pf to enable."
fi
