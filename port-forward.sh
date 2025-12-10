#!/bin/bash

echo "Finding service matching with '-kundbehovsflode'"
KUNDBEHOVSFLODE_SERVICE=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" | grep -- '-kundbehovsflode$' | head -n 1 | tr -d '[:space:]')
echo "KUNDBEHOVSFLODE_SERVICE='$KUNDBEHOVSFLODE_SERVICE'"
if [ -n "$KUNDBEHOVSFLODE_SERVICE" ]; then
  echo "Starting port-forward: kubectl port-forward service/$KUNDBEHOVSFLODE_SERVICE 8888:8080"
  echo "Sleeping 30 sec making sure that application is responding before forwarding"
  sleep 30
  nohup kubectl port-forward service/"$KUNDBEHOVSFLODE_SERVICE" 8888:8080 > portforward.log 2>&1 &
  echo $! > portforward.pid
else
  echo "No service ending with '-kundbehovsflode' found — skipping port-forward."
fi
