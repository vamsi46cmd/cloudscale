{{- define "cloudscale.labels" -}}
app.kubernetes.io/name: cloudscale
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "cloudscale.selector" -}}
app.kubernetes.io/name: cloudscale
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Pod spec shared by api and worker. Call with a dict: root, component, workerEnabled, resources, replicas handled by caller. */}}
{{- define "cloudscale.podspec" -}}
serviceAccountName: {{ .root.Release.Name }}-cloudscale
automountServiceAccountToken: false
# Kubernetes would otherwise inject env vars for every Service (e.g. REDIS_PORT=tcp://10.0.0.1:6379) and clobber app settings.
enableServiceLinks: false
terminationGracePeriodSeconds: {{ .root.Values.terminationGracePeriodSeconds }}
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  seccompProfile:
    type: RuntimeDefault
containers:
  - name: {{ .component }}
    image: "{{ .root.Values.image.repository }}:{{ .root.Values.image.tag }}"
    imagePullPolicy: {{ .root.Values.image.pullPolicy }}
    securityContext:
      allowPrivilegeEscalation: false
      capabilities:
        drop: ["ALL"]
    ports:
      - { name: http, containerPort: 8080 }
      - { name: management, containerPort: 8081 }
    envFrom:
      - configMapRef:
          name: {{ .root.Release.Name }}-cloudscale
    env:
      - name: WORKER_ENABLED
        value: {{ .workerEnabled | quote }}
      - name: WORKER_CONCURRENCY
        value: {{ .root.Values.worker.concurrency | quote }}
      - name: DB_PASSWORD
        valueFrom: { secretKeyRef: { name: {{ .root.Values.existingSecret }}, key: DB_PASSWORD } }
      - name: JWT_SECRET
        valueFrom: { secretKeyRef: { name: {{ .root.Values.existingSecret }}, key: JWT_SECRET } }
    startupProbe:
      httpGet: { path: /actuator/health/liveness, port: management }
      periodSeconds: 3
      failureThreshold: 40
    livenessProbe:
      httpGet: { path: /actuator/health/liveness, port: management }
      periodSeconds: 10
      failureThreshold: 3
    readinessProbe:
      httpGet: { path: /actuator/health/readiness, port: management }
      periodSeconds: 5
      failureThreshold: 3
    lifecycle:
      preStop:
        exec:
          # Give the Service/Ingress time to stop sending traffic before Spring begins its graceful shutdown.
          command: ["sleep", "8"]
    resources:
      {{- toYaml .resources | nindent 6 }}
{{- end -}}
