#!/bin/bash
echo "Sleeping 30 sec making sure that applications are responding before forwarding"
sleep 30
echo "Finding service matching with '-kundbehovsflode'"
KUNDBEHOVSFLODE_SERVICE=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" | grep -- '-kundbehovsflode$' | head -n 1 | tr -d '[:space:]')
echo "KUNDBEHOVSFLODE_SERVICE='$KUNDBEHOVSFLODE_SERVICE'"
if [ -n "$KUNDBEHOVSFLODE_SERVICE" ]; then
  echo "Starting port-forward: kubectl port-forward service/$KUNDBEHOVSFLODE_SERVICE 8888:8080"
  nohup kubectl port-forward service/"$KUNDBEHOVSFLODE_SERVICE" 8888:8080 > portforward_kundbehovsflode.log 2>&1 &
  echo $! > portforward_kundbehovsflode.pid
else
  echo "No service ending with '-kundbehovsflode' found — skipping port-forward."
fi

echo "Finding service matching with '-uppgiftslager'"
OUL_SERVICE=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" | grep -- '-uppgiftslager' | head -n 1 | tr -d '[:space:]')
echo "OUL='$OUL_SERVICE'"
if [ -n "$OUL_SERVICE" ]; then
  echo "Starting port-forward: kubectl port-forward service/$OUL_SERVICE 8889:8080"
  nohup kubectl port-forward service/"$OUL_SERVICE" 8889:8080 > portforward_oul.log 2>&1 &
  echo $! > portforward_oul.pid
else
  echo "No service ending with '-uppgiftslager' found — skipping port-forward."
fi

echo "Finding service matching with '-rtf-manuell'"
RTF_MANUELL_SERVICE=$(kubectl get svc -n default --no-headers -o custom-columns=":metadata.name" | grep -- '-rtf-manuell' | head -n 1 | tr -d '[:space:]')
echo "RTF_MANUELL_SERVICE='$RTF_MANUELL_SERVICE'"
if [ -n "$RTF_MANUELL_SERVICE" ]; then
  echo "Starting port-forward: kubectl port-forward service/$RTF_MANUELL_SERVICE 8890:8080"
  nohup kubectl port-forward service/"$RTF_MANUELL_SERVICE" 8890:8080 > portforward_rtf_manuell.log 2>&1 &
  echo $! > portforward_rtf_manuell.pid
else
  echo "No service ending with '-rtf-manuell' found — skipping port-forward."
fi

# Port forwarding to kafka external nodeport listener
echo "Starting port-forward: kubectl port-forward svc/dev-kafka-dev-kafka-combined-0 9094:9094"
nohup kubectl port-forward svc/dev-kafka-dev-kafka-combined-0 9094:9094 >> portforward.log 2>&1 &

echo "Finding pod matching '-rtf-manuell'"
RTF_MANUELL_POD=$(kubectl get pod -n default --no-headers -o custom-columns=":metadata.name"| grep -- '-rtf-manuell')
echo "RTF_MANUELL_POD='$RTF_MANUELL_POD'"
if [ -n "$RTF_MANUELL_POD" ]; then
  echo "Starting port-forward: kubectl port-forward pod/RTF_MANUELL_POD 5005:5005"
  nohup kubectl port-forward pod/"$RTF_MANUELL_POD" 5005:5005 > portforward_rtf_manuell_debug.log 2>&1 &
  echo $! > portforward_rtf_manuell_debug.pid
else
  echo "No pod matching '-rtf-manuell' found — skipping port-forward."
fi
