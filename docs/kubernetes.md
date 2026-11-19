# Kubernetes (Helm) deployment and verification

Chart: `infrastructure/helm/cloudscale`. It deploys the API and worker (same image, different settings), a ClusterIP
Service, ConfigMap, a dedicated ServiceAccount with no API access, a CPU-based HorizontalPodAutoscaler, a PodDisruptionBudget
and an optional Ingress. **PostgreSQL and Redis are external** (RDS / ElastiCache): the chart only references them.
Secrets are never rendered by the chart; it reads `DB_PASSWORD` and `JWT_SECRET` from an existing Secret (`existingSecret`).

Hardening in the pod spec: non-root, no privilege escalation, all capabilities dropped, seccomp RuntimeDefault, no mounted
service-account token, resource requests and limits, startup/liveness/readiness probes on the management port, a 8 s
`preStop` sleep so traffic drains before shutdown, 45 s termination grace (Spring's graceful phase is 30 s), and
`maxUnavailable: 0` rolling updates.

## Reproduce locally (kind)

```bash
docker compose build api                                   # builds cloudscale/app:local
kind create cluster --name cloudscale --config infrastructure/kubernetes/kind-cluster.yaml
kind load docker-image cloudscale/app:local --name cloudscale

# metrics-server is required for the HPA (download, review, then apply; kind needs --kubelet-insecure-tls)
curl -sL -o metrics-server.yaml https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.7.2/components.yaml
#   add "- --kubelet-insecure-tls" to the container args, then:
kubectl apply -f metrics-server.yaml

kubectl create namespace cloudscale
kubectl -n cloudscale create secret generic cloudscale-secrets \
  --from-literal=DB_PASSWORD="$(openssl rand -hex 12)" --from-literal=JWT_SECRET="$(openssl rand -hex 32)"
kubectl -n cloudscale apply -f infrastructure/kubernetes/local-deps.yaml     # stand-ins for RDS / ElastiCache (local only)
helm install cs infrastructure/helm/cloudscale -n cloudscale -f infrastructure/helm/values-kind.yaml
```

Verify the HPA can read metrics: `kubectl top pods -n cloudscale` must print CPU/memory and
`kubectl -n cloudscale get hpa` must show a percentage (not `<unknown>`).

Autoscaling load test (in-cluster k6; `LOGIN_RATE=1` makes every request a CPU-heavy BCrypt login):

```bash
kubectl -n cloudscale create configmap k6-script --from-file=load.js=tests/performance/load.js
kubectl -n cloudscale apply -f infrastructure/kubernetes/hpa-load-test.yaml
kubectl -n cloudscale get hpa -w
```

## What was verified (kind v0.33, Kubernetes 1.37, Helm 4.3)

- `helm lint` passes; the rendered manifests pass a **server-side dry run** against the cluster API.
- Both deployments reach Ready; a job submitted through the Service was processed by a worker pod.
- **Autoscaling:** metrics-server provided real CPU numbers (HPA showed `3%/60%` idle). Under the in-cluster login load
  the HPA scaled the API **2 -> 4 -> 6 replicas** (events: "cpu resource utilization above target"); the load run
  finished with 0% errors. **Scale-down was not observed** (the default 5-minute stabilization window was not waited out).
- **Rolling update, rollback and pod restart under continuous traffic:** a probe pod inside the cluster sent 4,038 requests
  while the Deployment was updated (new pod template), rolled back (`helm rollback`), and one API pod was deleted.
  All 4,038 returned the expected response: no connection errors and no 5xx.

## Problems found while testing (fixed)

- Kubernetes injects `REDIS_PORT=tcp://10.x.x.x:6379` for a Service named `redis`, which overrode the app's `REDIS_PORT`
  and crashed startup. Fixed with `enableServiceLinks: false` in the pod spec.
- Mixing `kubectl patch` with Helm (server-side apply) causes field-ownership conflicts on upgrade; change values through Helm.
- Annotation values must be strings: use `--set-string podAnnotations.rev=2`.

## Not tested

Ingress rendering is only dry-run validated (no ingress controller was installed). Worker-pod crash recovery was tested on
Docker Compose, not on Kubernetes. No NetworkPolicies are defined. Node drains against the PodDisruptionBudget were not exercised.
