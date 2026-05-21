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
  local pattern=$1 local_port=$2 log_name=$3
  local svc
  svc=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" \
    | grep -- "$pattern" | head -n 1 | tr -d '[:space:]')
  if [ -n "$svc" ]; then
    echo "Starting port-forward: service/$svc $local_port:8080"
    nohup kubectl port-forward "service/$svc" "$local_port:8080" \
      > "portforward_${log_name}.log" 2>&1 &
    echo $! > "portforward_${log_name}.pid"
  else
    echo "No service matching '$pattern' found — skipping."
  fi
}

forward_service '-handlaggning'  8888 handlaggning
forward_service '-uppgiftslager' 8889 oul
forward_service '-rtf-manuell'   8890 rtf_manuell
forward_service '-bekraftabeslut' 8891 bekraftabeslut

# Port forwarding to kafka external nodeport listener
echo "Starting port-forward: svc/dev-kafka-dev-kafka-combined-0 9094:9094"
nohup kubectl port-forward svc/dev-kafka-dev-kafka-combined-0 9094:9094 >> portforward.log 2>&1 &
echo $! > portforward_kafka.pid

# Debug port-forward for rtf-manuell (opt-in via --debug)
if [ "${1:-}" = "--debug" ]; then
  pod=$(kubectl get pod -n default --no-headers -o custom-columns=":metadata.name" \
    | grep -- '-rtf-manuell' | head -n 1)
  if [ -n "$pod" ]; then
    echo "Starting debug port-forward: pod/$pod 5005:5005"
    nohup kubectl port-forward "pod/$pod" 5005:5005 > portforward_rtf_manuell_debug.log 2>&1 &
    echo $! > portforward_rtf_manuell_debug.pid
  else
    echo "No pod matching '-rtf-manuell' found — skipping debug port-forward."
  fi
fi
