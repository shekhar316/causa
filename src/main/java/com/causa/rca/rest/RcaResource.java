package com.causa.rca.rest;

import com.causa.rca.model.AlertWebhookRequest;
import com.causa.rca.model.RcaAnalysisSession;
import com.causa.rca.service.RcaOrchestrator;
import com.causa.rca.service.WebhookManagerService;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * REST API resource for Root Cause Analysis operations.
 * <p>
 * This resource provides HTTP endpoints for triggering RCA analysis on Kubernetes pods.
 * It serves as the entry point for external clients to request diagnostic analysis
 * of pod issues and receive structured RCA reports.
 * </p>
 * <p>
 * All endpoints produce and consume JSON format for easy integration with various clients.
 * </p>
 *
 * @see RcaOrchestrator
 */
@Tag(name = "RCA")
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RcaResource {

    private static final Logger LOG = Logger.getLogger(RcaResource.class);

    @Inject
    RcaOrchestrator orchestrator;

    @Inject
    WebhookManagerService webhookManager;

    /**
     * Starts an asynchronous Root Cause Analysis for a specific pod.
     * <p>
     * Initiates the RCA pipeline asynchronously and returns immediately with session information.
     * The analysis runs in the background through the following stages:
     * <ol>
     *   <li>Data collection (metrics, logs, events, JFR data)</li>
     *   <li>Anomaly detection using AI</li>
     *   <li>Root cause analysis with detailed reasoning</li>
     *   <li>Validation and formatting of results</li>
     * </ol>
     * </p>
     *
     * @param request JSON body containing namespace and podName
     * @return an {@link RcaAnalysisSession} with status IN_PROGRESS and session details
     * @throws BadRequestException if namespace or podName is missing or empty
     */
    @POST
    @Path("/analyze")
    @Operation(
        summary = "Start RCA analysis for a pod",
        description = "Initiates an asynchronous Root Cause Analysis for a specific Kubernetes pod. " +
                     "The analysis collects metrics, logs, events, and JFR data, then uses AI to detect anomalies " +
                     "and perform root cause analysis. Returns immediately with a session ID that can be used to " +
                     "track the analysis progress via the /api/v1/diagnostics/{sessionId} endpoint."
    )
    @RequestBody(
        description = "Request body containing the target pod information. Required: 'podName'. Optional: 'namespace' (defaults to 'default').",
        required = true,
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            examples = {
                @ExampleObject(
                    name = "Basic request",
                    summary = "Analyze a pod in the default namespace",
                    value = "{\n  \"podName\": \"my-app-pod-12345\"\n}"
                ),
                @ExampleObject(
                    name = "With namespace",
                    summary = "Analyze a pod in a specific namespace",
                    value = "{\n  \"namespace\": \"production\",\n  \"podName\": \"my-app-pod-12345\"\n}"
                )
            }
        )
    )
    @APIResponses(
        value = {
            @APIResponse(
                responseCode = "200",
                description = "Analysis started successfully",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = RcaAnalysisSession.class),
                    examples = @ExampleObject(
                        name = "Success response",
                        value = "{\n" +
                               "  \"sessionId\": \"550e8400-e29b-41d4-a716-446655440000\",\n" +
                               "  \"timestamp\": \"2024-01-15T10:30:00\",\n" +
                               "  \"namespace\": \"production\",\n" +
                               "  \"podName\": \"my-app-pod-12345\",\n" +
                               "  \"status\": \"INITIATED\",\n" +
                               "  \"currentStep\": \"Starting analysis...\",\n" +
                               "  \"progressPercent\": 0.0\n" +
                               "}"
                    )
                )
            ),
            @APIResponse(
                responseCode = "400",
                description = "Bad request - missing or invalid parameters",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    examples = @ExampleObject(
                        name = "Missing podName",
                        value = "{\n  \"error\": \"podName is required\"\n}"
                    )
                )
            ),
            @APIResponse(
                responseCode = "500",
                description = "Internal server error - analysis could not be started",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    examples = @ExampleObject(
                        name = "Server error",
                        value = "{\n  \"error\": \"Failed to start analysis: Connection timeout\"\n}"
                    )
                )
            )
        }
    )
    public RcaAnalysisSession analyze(Map<String, String> request) {
        String namespace = request.get("namespace");
        String podName = request.get("podName");
        
        // Default namespace to "default" if not provided
        if (namespace == null || namespace.isEmpty()) {
            namespace = "default";
        }
        
        if (podName == null || podName.isEmpty()) {
            throw new BadRequestException("podName is required");
        }
        
        return orchestrator.startAnalysis(namespace, podName);
    }

    /**
     * Webhook endpoint for receiving Prometheus Alertmanager alerts.
     * <p>
     * This endpoint is designed to be called by Prometheus Alertmanager when alerts fire.
     * It processes incoming alerts and triggers RCA analysis for the affected pods.
     * </p>
     * <p>
     * The webhook expects alerts to contain labels with:
     * <ul>
     *   <li><b>namespace</b>: The Kubernetes namespace of the affected pod</li>
     *   <li><b>pod</b> or <b>pod_name</b>: The name of the affected pod</li>
     * </ul>
     * </p>
     * <p>
     * Example Alertmanager webhook configuration:
     * <pre>
     * receivers:
     * - name: 'rca-webhook'
     *   webhook_configs:
     *   - url: 'http://causa-service:8080/rca/webhook'
     *     send_resolved: false
     * </pre>
     * </p>
     * <p>
     * The endpoint processes all firing alerts in the webhook payload and returns
     * a summary of the analysis results for each pod.
     * </p>
     *
     * @param alertRequest the webhook payload from Prometheus Alertmanager
     * @return a Response containing a summary of processed alerts and their RCA results
     */
    @POST
    @Path("/webhooks/alerts")
    @Operation(summary = "Process Alertmanager webhook and trigger RCA analyses")
    public Response handleWebhook(AlertWebhookRequest alertRequest) {
        LOG.info("Received webhook request");
        Map<String, Object> result = webhookManager.processWebhook(alertRequest);
        return Response.ok(result).build();
    }
}
