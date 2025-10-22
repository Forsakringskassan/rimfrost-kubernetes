# Rimfrost Kubernetes

Kubernetes deployment of Rimfrost PoC for VAH (Vård av husdjur)

Start it with `./deploy.sh`.
Clean it with `./cleanup.sh`.

## Port forwarding

Start with `./deploy.sh --pf` to trigger port forwarding to open port for accessing the VAH service

## Useful Commands

Check pod status:

```sh
kubectl get pods
```

View ingress proxy logs:

```sh
kubectl logs -f -n ingress-nginx deployment/ingress-nginx-controller
```

Open Kubernetes dashboard:

```sh
minikube dashboard
```

## Integration test

Run `mvn verify` to trigger integration tests.
This launches the kubernetes environment locally for testing, runs a smoke test and takes down the environment.