# Rimfrost Kubernetes

Kubernetes deployment of Rimfrost PoC for VAH (Vård av husdjur)

## Running integration tests

```sh
mvn verify
```

This automatically deploys the environment (`deploy.sh`), runs the integration tests, and tears it down (`cleanup.sh`).

## Starting and stopping the environment manually

To start without running tests (e.g. for exploratory testing):

```sh
./deploy.sh
```

To skip port-forwarding (e.g. in CI):

```sh
./deploy.sh --no-pf
```

To stop and clean up:

```sh
./cleanup.sh
```

## Port forwarding

Port-forwarding is started automatically by `deploy.sh`. To restart it manually:

```sh
./port-forward.sh
```

To also expose the remote debug port (5005) for `rtf-manuell`:

```sh
./port-forward.sh --debug
```

The following ports are forwarded:

| Service | Local port |
|---------|------------|
| handlaggning | 8888 |
| uppgiftslager | 8889 |
| rtf-manuell | 8890 |
| bekraftabeslut | 8891 |
| Kafka | 9094 |

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

### Remote debugging of container

Exposing port 5005 of a pod makes it possible to connect the IDE with Remote JVM debugging using single-step and breakpoints.

This has been done for service _rtf_manuell_ , but can be applied to any service (check settings for this pod in values.yaml and deploy.sh)

### Connecting to kafka broker

Enable minikube tunnel: </n>
```
minikube tunnel
```

You can now connect to the kafka bootstrap server from your local kafka tool, by configuring _Bootstrap server_ as:</n>
```
localhost:9094
```

Tool example: </n>
- https://www.kafkio.com/



#### IntellijIdea

Create a new debug configuration: _Remote JVM Debug_<br>
Select: _Attach to remote JVM_ , connect to localhost:5005

## Develop faster locally (quarkus projects)

Instead of relying on pushing your changes to GitHub and waiting for a workflow to build and publish a docker image, which takes extra time and effort, you can just do it locally.

1. Locate your project's `Dockerfile.jvm` file, usually located at `.\src\main\docker` from the root of the project. Make sure that the OpenJDK version in the `FROM` line matches your project's Java version to minimize build errors. For example, for Java 21 this version would most likely work:

    ```dockerfile
    registry.access.redhat.com/ubi9/openjdk-21:latest
    ```

    For finding an image that works for your project, [this website](https://catalog.redhat.com/en/search?searchType=containers) is a good place to start.


2. Add support for building Docker images in your Quarkus project (if it does not already support it). In the root of your project, run the following:

    ```bash
    ./mvnw quarkus:add-extension -Dextensions="io.quarkus:quarkus-container-image-docker"
    ```


3. Build your local Docker image:

    ```bash
    ./mvnw -s settings.xml clean package \
      -Dquarkus.container-image.build=true \
      -Dquarkus.container-image.image=<image-name> \
      -Dquarkus.container-image.tag=<tag>
    ```

4. Load the Docker image into Minikube so it is available for use in your cluster (Minikube must be running for this to work):

    ```bash
    minikube image load <image-name>
    ```

5. Add your image to a new deployment/pod in the `values.yaml` of this repository:

    ```yaml
    - name: <valfritt-namn-på-podden>
      image:
        repository: <image-namn>
        tag: <tag>
      waitForKafka: true
    ```