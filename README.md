# Template Kubernetes

This is intended to be used as a template local setup.

This will deploy:

- [template-quarkus](https://github.com/Forsakringskassan/template-quarkus)
- [designsystem-user-app](https://github.com/Forsakringskassan/designsystem-user-app)

So that it can be accessed on localhost.

Start it with `./deploy.sh`.

Clean it with `./cleanup.sh`.

## Useful Commands

Check pod status:

```sh
kubectl get pods
```

View logs:

```sh
kubectl logs -f deployment/template-k8s-quarkus
kubectl logs -f deployment/template-k8s-apache
```

View ingress proxy logs:

```sh
kubectl logs -f -n ingress-nginx deployment/ingress-nginx-controller
```

Open Kubernetes dashboard:

```sh
minikube dashboard
```
