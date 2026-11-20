#!/bin/bash
echo "Stopping any existing port-forwards..."
for pid_file in portforward_*.pid; do
  [ -f "$pid_file" ] && kill "$(cat "$pid_file")" 2>/dev/null && rm "$pid_file"
done

echo "Waiting for all deployments to be ready..."
kubectl wait --for=condition=available deployment \
  --all \
  --namespace=default \
  --timeout=120s

forward_service() {
  local pattern=$1 local_port=$2 log_name=$3 remote_port=${4:-8080}
  local svc
  svc=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" \
    | grep -- "$pattern" | head -n 1 | tr -d '[:space:]')
  if [ -n "$svc" ]; then
    echo "Starting port-forward: service/$svc $local_port:$remote_port"
    nohup bash -c "while true; do kubectl port-forward \"service/$svc\" \"$local_port:$remote_port\"; sleep 2; done" \
      > "portforward_${log_name}.log" 2>&1 &
    echo $! > "portforward_${log_name}.pid"
  else
    echo "No service matching '$pattern' found — skipping."
  fi
}

forward_service '-workflow$'  8888 workflow
forward_service '-uppgiftslager$' 8889 oul
forward_service '-rtf-manuell$'   8890 rtf_manuell
forward_service '-bekraftabeslut$' 8891 bekraftabeslut
forward_service '-kommunicering$' 8903 regel_kommunicering
forward_service '-sid$' 8892 sid
forward_service '-identitet-prenumeration$' 8901 identitet_prenumeration
forward_service '-team$' 8893 team
forward_service '-rtf-manuell-komplettering$' 8899 rtf_manuell_komplettering
forward_service '-handlaggning$' 8902 handlaggning

# BFF services — each listens on its own port (not 8080), matching the
# frontends' VITE_BFF_URL defaults (see each *-fe repo's .env)
forward_service '-portal-bff$' 9001 portal_bff 9001
forward_service '-portal-admin-bff$' 9091 portal_admin_bff 9091
forward_service '-rtf-manuell-bff$' 9002 rtf_manuell_bff 9002
forward_service '-bekraftabeslut-bff$' 9003 bekraftabeslut_bff 9003
forward_service '-rtf-manuell-komplettering-bff$' 9004 rtf_manuell_komplettering_bff 9004
forward_service '-template-micro-fe-bff$' 9009 template_micro_fe_bff 9009
forward_service '-process-vah-bff$' 9005 process_vah_bff 9005
forward_service '-process-vab-bff$' 9006 process_vab_bff 9006
forward_service '-kommunicering-bff$' 9007 regel_kommunicering_bff 9007

# FE apps (static httpd containers, no /q/health — see wait_for_http below)
forward_service '-portal-handlaggare$' 8894 portal_handlaggare
forward_service '-portal-admin-fe$' 8895 portal_admin_fe
forward_service '-rtf-manuell-fe$' 8896 rtf_manuell_fe
forward_service '-bekraftabeslut-fe$' 8897 bekraftabeslut_fe
forward_service '-template-micro-fe$' 8898 template_micro_fe
forward_service '-rtf-manuell-komplettering-fe$' 8900 rtf_manuell_komplettering_fe
forward_service '-process-vah-fe$' 8904 process_vah_fe
forward_service '-process-vab-fe$' 8905 process_vab_fe
forward_service '-kommunicering-fe$' 8906 regel_kommunicering_fe

# Port forwarding to kafka external nodeport listener
echo "Starting port-forward: svc/dev-kafka-dev-kafka-combined-0 9094:9094"
nohup kubectl port-forward svc/dev-kafka-dev-kafka-combined-0 9094:9094 >> portforward.log 2>&1 &
echo $! > portforward_kafka.pid

wait_for_health() {
  local port=$1 name=$2 timeout=${3:-300} elapsed=0
  local url="http://localhost:${port}/q/health"
  echo "Waiting for health: $name ($url)"
  while [ $elapsed -lt $timeout ]; do
    if curl -sf "$url" > /dev/null 2>&1; then
      echo "  Ready: $name"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "  Timed out after ${timeout}s waiting for $name ($url)"
  return 1
}

wait_for_http() {
  local port=$1 name=$2 timeout=${3:-300} elapsed=0
  local url="http://localhost:${port}/"
  echo "Waiting for: $name ($url)"
  while [ $elapsed -lt $timeout ]; do
    if curl -sf "$url" > /dev/null 2>&1; then
      echo "  Ready: $name"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo "  Timed out after ${timeout}s waiting for $name ($url)"
  return 1
}

wait_for_health 8888 workflow && wait_for_health 8889 uppgiftslager && wait_for_health 8890 rtf-manuell && wait_for_health 8891 bekraftabeslut && wait_for_health 8892 sid && wait_for_health 8893 team && wait_for_health 8899 rtf-manuell-komplettering && wait_for_health 8901 identitet-prenumeration && wait_for_health 8902 handlaggning && wait_for_health 8903 regel-kommunicering \
  && wait_for_health 9001 portal-bff && wait_for_health 9091 portal-admin-bff && wait_for_health 9002 rtf-manuell-bff && wait_for_health 9003 bekraftabeslut-bff && wait_for_health 9004 rtf-manuell-komplettering-bff && wait_for_health 9009 template-micro-fe-bff && wait_for_health 9005 process-vah-bff && wait_for_health 9006 process-vab-bff && wait_for_health 9007 regel-kommunicering-bff \
  && wait_for_http 8894 portal-handlaggare && wait_for_http 8895 portal-admin-fe && wait_for_http 8896 rtf-manuell-fe && wait_for_http 8897 bekraftabeslut-fe && wait_for_http 8898 template-micro-fe && wait_for_http 8900 rtf-manuell-komplettering-fe && wait_for_http 8904 process-vah-fe && wait_for_http 8905 process-vab-fe && wait_for_http 8906 regel-kommunicering-fe

# Debug port-forward for rtf-manuell (opt-in via --debug)
if [ "${1:-}" = "--debug" ]; then
  pod=$(kubectl get pod -n default --no-headers -o custom-columns=":metadata.name" \
    | grep -- '-rtf-manuell$' | head -n 1)
  if [ -n "$pod" ]; then
    echo "Starting debug port-forward: pod/$pod 5005:5005"
    nohup kubectl port-forward "pod/$pod" 5005:5005 > portforward_rtf_manuell_debug.log 2>&1 &
    echo $! > portforward_rtf_manuell_debug.pid
  else
    echo "No pod matching '-rtf-manuell' found — skipping debug port-forward."
  fi
fi
