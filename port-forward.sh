#!/bin/bash

echo "Finding service matching with '-vah'"
VAH_SERVICE=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" | grep -- '-vah$' | head -n 1 | tr -d '[:space:]')
echo "VAH_SERVICE='$VAH_SERVICE'"
if [ -n "$VAH_SERVICE" ]; then
  echo "Starting port-forward: kubectl port-forward service/$VAH_SERVICE 8888:8080"
  echo "Sleeping 30 sec making sure that application is responding before forwarding"
  sleep 30
  nohup kubectl port-forward service/"$VAH_SERVICE" 8888:8080 > portforward.log 2>&1 &
  echo $! > portforward.pid
else
  echo "No service ending with '-vah' found — skipping port-forward."
fi
