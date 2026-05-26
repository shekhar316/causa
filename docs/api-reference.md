---
layout: page
title: API Reference
nav_order: 6
---

# API Reference

Causa RCA provides a comprehensive REST API for triggering analyses, querying results, and integrating with external systems.

---

## Base URL

```
http://<causa-service>:8080
```

For local development:
```
http://localhost:8080
```

---

## Authentication

Currently, the API does not require authentication. For production deployments, consider:
- Using Kubernetes NetworkPolicies to restrict access
- Implementing an API gateway with authentication
- Using service mesh features (Istio, Linkerd)

---

## Analysis Endpoints

### Trigger Manual Analysis

Starts an asynchronous RCA analysis for a specific pod.

**Endpoint:** `POST /api/v1/analyze`

**Content-Type:** `application/json`

**Request Body:**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `namespace` | string | No | `default` | Kubernetes namespace |
| `podName` | string | Yes | - | Pod name to analyze |

**Example Request:**

```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "default",
    "podName": "mongodb-75f4fb68b7-2f2xh"
  }'
```

**Example Response:**

```json
{
  "id": "6a1560d4dfc3b81763d8ea5b",
  "sessionId": "73a2c6f5-01e8-468e-a88d-5652a4479aed",
  "timestamp": "2026-05-26T14:29:00.108522",
  "namespace": "default",
  "podName": "mongodb-75f4fb68b7-2f2xh",
  "status": "INITIATED",
  "currentStep": "Analysis initiated",
  "report": null,
  "collectedArtifacts": null,
  "errorMessage": null,
  "completedAt": null,
  "progressPercent": 0.0,
  "stageTiming": {},
  "anomalyType": null,
  "formattedDuration": "0s",
  "formattedTimestamp": "2026-05-26 14:29:00.108522",
  "duration": 0.360060000,
  "inProgress": true,
  "podIdentifier": "default/mongodb-75f4fb68b7-2f2xh",
  "completed": false
}
```

**Response Codes:**

- `200 OK` - Analysis started successfully
- `400 Bad Request` - Missing or invalid podName parameter
- `500 Internal Server Error` - Analysis failed to start

---

### Webhook Endpoint

Receives Prometheus Alertmanager webhooks and triggers RCA analysis.

**Endpoint:** `POST /api/v1/webhooks/alerts`

**Content-Type:** `application/json`

**Request Body:**

Standard Prometheus Alertmanager webhook payload. Alerts must include `namespace` and `pod` labels.

**Example Request:**

```bash
curl -X POST http://localhost:8080/api/v1/webhooks/alerts \
  -H "Content-Type: application/json" \
  -d '{
    "status": "firing",
    "alerts": [
      {
        "status": "firing",
        "labels": {
          "alertname": "PodMemoryHigh",
          "namespace": "default",
          "pod": "mongodb-75f4fb68b7-2f2xh",
          "severity": "warning"
        },
        "annotations": {
          "summary": "Pod memory usage is high",
          "description": "Memory usage at 85%"
        },
        "startsAt": "2026-05-26T08:00:00Z"
      }
    ]
  }'
```

**Example Response:**

```json
{
  "totalAlerts": 1,
  "processed": 1,
  "message": "Webhook processed",
  "results": [
    {
      "pod": "mongodb-75f4fb68b7-2f2xh",
      "alert": "PodMemoryHigh",
      "namespace": "default",
      "sessionId": "a6ed640d-018c-4561-a943-dc2e68d7ad15",
      "analysisStatus": "INITIATED",
      "status": "in_progress"
    }
  ],
  "errors": 0,
  "skipped": 0
}
```

**Response Codes:**

- `200 OK` - Webhook processed (even if some alerts skipped)
- `400 Bad Request` - Invalid webhook payload
- `500 Internal Server Error` - Processing failed

**Alert Requirements:**

Alerts must include these labels:
- `namespace` - Kubernetes namespace
- `pod` or `pod_name` - Pod name

Alternative label names also supported:
- `exported_namespace` (for namespace)
- `exported_pod` (for pod)

---

## Query Endpoints

### List All Analyses

Lists all RCA analysis sessions with optional filtering and pagination.

**Endpoint:** `GET /api/v1/diagnostics`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `status` | string | No | - | Filter by status (INITIATED, IN_PROGRESS, COMPLETED, FAILED) |
| `namespace` | string | No | - | Filter by namespace |
| `pod` | string | No | - | Filter by pod name |
| `page` | integer | No | `0` | Page number (0-based) |
| `pageSize` | integer | No | `50` | Results per page (1-100) |
| `verbose` | boolean | No | `false` | Return full session details or summary |

**Example Request (Summary):**

```bash
curl "http://localhost:8080/api/v1/diagnostics?verbose=false"
```

**Example Response (Summary):**

```json
{
  "analyses": [
    {
      "issue": "The heap-oom-prom container is experiencing frequent GC pauses, leading to increased latency and potential memory issues.",
      "namespace": "chaos-test",
      "podName": "heap-oom-prom-67d785cb84-p59fx",
      "sessionId": "6da9a75d-c27a-435f-8ddd-72f51fe80f1d",
      "title": "GC Pause in Heap-Oom-Prom Container\n\nSeverity: Medium",
      "timestamp": "2026-05-14T10:19:55.175",
      "status": "COMPLETED"
    }
  ],
  "count": 1,
  "pageSize": 50,
  "page": 0,
  "verbose": false
}
```

**Example Request (With Filters):**

```bash
curl "http://localhost:8080/api/v1/diagnostics?status=COMPLETED&namespace=chaos-test"
```

**Example Request (Verbose):**

```bash
curl "http://localhost:8080/api/v1/diagnostics?verbose=true"
```

<details>
<summary>Example Response (Verbose - Click to expand)</summary>

```json
{
  "analyses": [
    {
      "id": "6a05a1cbcb07c83987c9f6f3",
      "sessionId": "6da9a75d-c27a-435f-8ddd-72f51fe80f1d",
      "timestamp": "2026-05-14T10:19:55.175",
      "namespace": "chaos-test",
      "podName": "heap-oom-prom-67d785cb84-p59fx",
      "status": "COMPLETED",
      "currentStep": "Analysis completed successfully",
      "report": {
        "title": "GC Pause in Heap-Oom-Prom Container\n\nSeverity: Medium",
        "issue": "The heap-oom-prom container is experiencing frequent GC pauses, leading to increased latency and potential memory issues.",
        "highLevelIssue": "The pod is consuming 255MB of memory out of a total 256MB, which is above the 80% threshold defined in the detection logic.",
        "subLevelIssue": "The Summarized Logs indicate frequent Full GC events, with some pauses exceeding 50 milliseconds.",
        "evidence": "* [2026-05-14T10:15:50Z] FailedMount: MountVolume.SetUp failed...",
        "supportedLogs": [],
        "validationChecks": null,
        "assertions": [
          {
            "assertion": "container experienced frequent GC pauses",
            "matchedLogs": ["BackOff restarting failed container", "OOMKilled(137)"],
            "modelAnalysisQuestions": ["Do logs show OOMKilled before the container restart?"],
            "judgmentCall": {
              "decision": "Partially Supported",
              "confidence": 0.7,
              "reasoning": "The logs show a failure to restart the container due to OOMKilled(137).",
              "matchType": "indirect|direct",
              "confidencePercent": 70
            }
          }
        ],
        "finalDecision": {
          "status": "Unsupported",
          "summary": "Assertions validated independently and aggregated deterministically."
        }
      },
      "collectedArtifacts": {
        "namespace": "chaos-test",
        "podName": "heap-oom-prom-67d785cb84-p59fx",
        "metrics": {
          "summary": "mem=255MB/256MB(99.8%) cpu=0.098c/0.500c(19.5%)",
          "memoryUsageBytes": 267771904,
          "memoryLimitBytes": 268435456,
          "memoryUsagePercent": 99.7528076171875,
          "cpuUsageCores": 0.09761382308027255,
          "cpuLimitCores": 0.5,
          "cpuUsagePercent": 19.52276461605451
        },
        "podInfo": {
          "summary": "phase=Running containers=[heap-oom-prom: ready=true restarts=656 state=Running]",
          "phase": "Running",
          "namespace": "chaos-test",
          "podName": "heap-oom-prom-67d785cb84-p59fx",
          "containers": [
            {
              "name": "heap-oom-prom",
              "ready": true,
              "restartCount": 656,
              "currentState": "Running"
            }
          ]
        }
      },
      "progressPercent": 100.0,
      "inProgress": false,
      "completed": true
    }
  ],
  "count": 1,
  "pageSize": 50,
  "page": 0,
  "verbose": true
}
```

</details>

**Response Codes:**

- `200 OK` - Success
- `400 Bad Request` - Invalid parameters
- `500 Internal Server Error` - Query failed

---

### Get Specific Analysis

Retrieves a specific analysis session by its session ID.

**Endpoint:** `GET /api/v1/diagnostics/{sessionId}`

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sessionId` | string | Yes | Unique session identifier |

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `verbose` | boolean | No | `true` | Return full session details or summary |

**Example Request (Summary):**

```bash
curl "http://localhost:8080/api/v1/diagnostics/6da9a75d-c27a-435f-8ddd-72f51fe80f1d?verbose=false"
```

**Example Response (Summary):**

```json
{
  "issue": "The heap-oom-prom container is experiencing frequent GC pauses, leading to increased latency and potential memory issues.",
  "namespace": "chaos-test",
  "podName": "heap-oom-prom-67d785cb84-p59fx",
  "sessionId": "6da9a75d-c27a-435f-8ddd-72f51fe80f1d",
  "title": "GC Pause in Heap-Oom-Prom Container\n\nSeverity: Medium",
  "timestamp": "2026-05-14T10:19:55.175",
  "status": "COMPLETED"
}
```

**Example Request (Verbose):**

```bash
curl "http://localhost:8080/api/v1/diagnostics/6da9a75d-c27a-435f-8ddd-72f51fe80f1d?verbose=true"
```

**Response Codes:**

- `200 OK` - Analysis found
- `404 Not Found` - Analysis not found
- `500 Internal Server Error` - Query failed

---

### Get Analysis Statistics

Retrieves aggregate statistics about all analyses.

**Endpoint:** `GET /api/v1/diagnostics/stats`

**Example Request:**

```bash
curl "http://localhost:8080/api/v1/diagnostics/stats"
```

**Example Response:**

```json
{
  "total": 2,
  "inProgress": 0,
  "healthy": 0,
  "completed": 2,
  "failed": 0
}
```

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `total` | integer | Total number of analyses |
| `inProgress` | integer | Analyses currently running |
| `completed` | integer | Successfully completed analyses |
| `failed` | integer | Failed analyses |
| `healthy` | integer | Analyses with healthy results |

**Response Codes:**

- `200 OK` - Success
- `500 Internal Server Error` - Query failed

---

### List Diagnostic Issues

Lists analyses that identified issues (unhealthy pods).

**Endpoint:** `GET /api/v1/diagnostics/issues`

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | integer | No | `0` | Page number (0-based) |
| `pageSize` | integer | No | `50` | Results per page (1-100) |
| `verbose` | boolean | No | `false` | Return full session details or summary |

**Example Request:**

```bash
curl "http://localhost:8080/api/v1/diagnostics/issues?verbose=false"
```

**Example Response:**

```json
{
  "total": 2,
  "count": 2,
  "pageSize": 50,
  "page": 0,
  "issues": [
    {
      "issue": "The heap-oom-prom container is experiencing frequent GC pauses, leading to increased latency and potential memory issues.",
      "namespace": "chaos-test",
      "podName": "heap-oom-prom-67d785cb84-p59fx",
      "sessionId": "6da9a75d-c27a-435f-8ddd-72f51fe80f1d",
      "title": "GC Pause in Heap-Oom-Prom Container\n\nSeverity: Medium",
      "timestamp": "2026-05-14T10:19:55.175",
      "status": "COMPLETED"
    },
    {
      "issue": "The heap memory usage in the heap-oom-prom container exceeded 90% during peak hours, leading to frequent GC pauses and OOMKilled events.",
      "namespace": "chaos-test",
      "podName": "heap-oom-prom-67d785cb84-p59fx",
      "sessionId": "1fc3bdd2-5000-481a-b169-88e0ef1c9b10",
      "title": "Root Cause Analysis Report",
      "timestamp": "2026-05-08T06:20:21.416",
      "status": "COMPLETED"
    }
  ],
  "verbose": false
}
```

**Response Codes:**

- `200 OK` - Success
- `400 Bad Request` - Invalid parameters
- `500 Internal Server Error` - Query failed

---

### List Workloads

Lists all Kubernetes pods across all namespaces.

**Endpoint:** `GET /api/v1/workloads`

**Example Request:**

```bash
curl "http://localhost:8080/api/v1/workloads"
```

<details>
<summary>Example Response (Click to expand)</summary>

```json
[
  {
    "namespace": "argocd",
    "podName": "argocd-application-controller-0",
    "restarts": 7,
    "status": "Running"
  },
  {
    "namespace": "chaos-test",
    "podName": "heap-oom-prom-67d785cb84-p59fx",
    "restarts": 661,
    "status": "Running"
  },
  {
    "namespace": "default",
    "podName": "mongodb-75f4fb68b7-2f2xh",
    "restarts": 7,
    "status": "Running"
  },
  {
    "namespace": "default",
    "podName": "ollama-67f857d985-zjwnn",
    "restarts": 5,
    "status": "Running"
  }
]
```

</details>

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `namespace` | string | Kubernetes namespace |
| `podName` | string | Pod name |
| `status` | string | Pod phase (Running, Pending, Failed, etc.) |
| `restarts` | integer | Total container restart count |

**Response Codes:**

- `200 OK` - Success
- `500 Internal Server Error` - Query failed

---

## Status Values

### Analysis Status

| Status | Description |
|--------|-------------|
| `INITIATED` | Analysis has been started |
| `IN_PROGRESS` | Analysis is currently running |
| `COMPLETED` | Analysis finished successfully |
| `FAILED` | Analysis encountered an error |

---

## Error Responses

All error responses follow this format:

```json
{
  "error": "Error message describing what went wrong"
}
```

**Common Error Codes:**

- `400 Bad Request` - Invalid request parameters
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server-side error

---

## Rate Limiting

Currently, there are no rate limits enforced. For production deployments, consider implementing rate limiting at the API gateway or ingress level.

---

## Pagination

Endpoints that support pagination use these parameters:

- `page` - Page number (0-based, default: 0)
- `pageSize` - Results per page (1-100, default: 50)

Response includes:
- `page` - Current page number
- `pageSize` - Results per page
- `count` - Number of results in current page
- `total` - Total number of results (when available)

---

## Best Practices

1. **Use verbose=false for lists** - When listing many analyses, use `verbose=false` to reduce response size
2. **Filter by namespace** - Use namespace filters to reduce query scope
3. **Poll for completion** - After triggering analysis, poll the session endpoint to check status
4. **Handle async operations** - Analysis endpoints return immediately; use session ID to track progress
5. **Monitor webhook processing** - Check webhook response for skipped/failed alerts

---

## Integration Examples

### Prometheus Alertmanager Configuration

```yaml
receivers:
- name: 'causa-rca'
  webhook_configs:
  - url: 'http://causa-service:8080/api/v1/webhooks/alerts'
    send_resolved: false
    http_config:
      follow_redirects: true

route:
  group_by: ['alertname', 'namespace', 'pod']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 12h
  receiver: 'causa-rca'
  routes:
  - match:
      severity: critical
    receiver: 'causa-rca'
  - match:
      severity: warning
    receiver: 'causa-rca'
```

### Kubernetes CronJob for Periodic Analysis

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: periodic-rca
spec:
  schedule: "0 */6 * * *"  # Every 6 hours
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: trigger-rca
            image: curlimages/curl:latest
            command:
            - /bin/sh
            - -c
            - |
              curl -X POST http://causa-service:8080/api/v1/analyze \
                -H "Content-Type: application/json" \
                -d '{"namespace":"production","podName":"my-app-pod"}'
          restartPolicy: OnFailure
```

---

[← Back to Getting Started](getting-started.html) | [Next: Configuration →](configuration.html)