#!/bin/bash
minikube addons enable metrics-server
kubectl patch deployment metrics-server -n kube-system \
  -p '{"spec": {"template": {"spec": {"containers": [{"name": "metrics-server","args": ["--cert-dir=/tmp","--secure-port=4443","--kubelet-preferred-address-types=InternalIP,Hostname,InternalDNS","--kubelet-insecure-tls"]}]}}}}'
minikube addons enable dashboard
minikube dashboard

