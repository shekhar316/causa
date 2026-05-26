package com.causa.rca.rest;

import com.causa.rca.clients.KubernetesMcpClient;
import com.causa.rca.model.RcaAnalysisSession;
import com.causa.rca.model.AnalysisStatus;
import com.causa.rca.service.AnalysisTrackingService;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API resource for querying RCA analysis data.
 * <p>
 * This resource provides JSON endpoints for the dashboard to retrieve
 * analysis sessions, statistics, and individual analysis details.
 * </p>
 * <p>
 * All endpoints return JSON and support filtering, pagination, and sorting.
 * </p>
 *
 * @see AnalysisTrackingService
 * @see RcaAnalysisSession
 */
@Tag(name = "Diagnosis")
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnalysisApiResource {
    
    private static final Logger LOG = Logger.getLogger(AnalysisApiResource.class);
    
    @Inject
    AnalysisTrackingService trackingService;

    @Inject
    KubernetesMcpClient kubernetesMcpClient;

    @Inject
    KubernetesClient kubernetesClient;
    
    /**
     * Lists all analyses with optional filtering and pagination.
     *
     * @param statusParam optional status filter
     * @param namespace optional namespace filter
     * @param podName optional pod name filter
     * @param page page number (0-based)
     * @param pageSize number of results per page
     * @return list of analyses matching the filters
     */
    @GET
    @Path("/diagnostics")
    @Operation(summary = "List RCA analysis sessions")
    public Response listAnalyses(
            @QueryParam("status") String statusParam,
            @QueryParam("namespace") String namespace,
            @QueryParam("pod") String podName,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("50") int pageSize,
            @QueryParam("verbose") @DefaultValue("false") boolean verbose) {
        
        LOG.infof("Listing analyses: status=%s, namespace=%s, pod=%s, page=%d, pageSize=%d, verbose=%s",
                 statusParam, namespace, podName, page, pageSize, verbose);
        
        try {
            AnalysisStatus status = null;
            if (statusParam != null && !statusParam.isEmpty()) {
                try {
                    status = AnalysisStatus.valueOf(statusParam.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "Invalid status: " + statusParam))
                            .build();
                }
            }
            
            if (page < 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Page must be >= 0"))
                        .build();
            }
            if (pageSize < 1 || pageSize > 100) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Page size must be between 1 and 100"))
                        .build();
            }
            
            List<RcaAnalysisSession> analyses = trackingService.getAnalysesWithFilters(
                status, namespace, podName, page, pageSize
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("page", page);
            response.put("pageSize", pageSize);
            response.put("count", analyses.size());
            response.put("verbose", verbose);

            if (verbose) {
                response.put("analyses", analyses);
            } else {
                List<Map<String, Object>> analysisItems = new ArrayList<>();
                for (RcaAnalysisSession analysis : analyses) {
                    analysisItems.add(toIssueSummary(analysis));
                }
                response.put("analyses", analysisItems);
            }
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOG.error("Error listing analyses", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve analyses: " + e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Gets a specific analysis by its session ID.
     *
     * @param sessionId the unique session identifier
     * @return the analysis session or 404 if not found
     */
    @GET
    @Path("/diagnostics/{sessionId}")
    @Operation(summary = "Get a specific RCA analysis session")
    public Response getAnalysis(
            @PathParam("sessionId") String sessionId,
            @QueryParam("verbose") @DefaultValue("true") boolean verbose) {
        LOG.infof("Getting analysis: %s, verbose=%s", sessionId, verbose);
        
        try {
            Optional<RcaAnalysisSession> session = trackingService.getSession(sessionId);
            
            if (session.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Analysis not found: " + sessionId))
                        .build();
            }

            if (verbose) {
                return Response.ok(session.get()).build();
            }

            return Response.ok(toIssueSummary(session.get())).build();
            
        } catch (Exception e) {
            LOG.error("Error getting analysis " + sessionId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve analysis: " + e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Gets dashboard statistics.
     *
     * @return statistics object
     */
    @GET
    @Path("/diagnostics/stats")
    @Operation(summary = "Get RCA analysis statistics")
    public Response getStatistics() {
        LOG.info("Getting dashboard statistics");
        
        try {
            AnalysisTrackingService.AnalysisStats stats = trackingService.getStatistics();
            
            Map<String, Object> response = new HashMap<>();
            response.put("total", stats.total);
            response.put("inProgress", stats.inProgress);
            response.put("completed", stats.completed);
            response.put("failed", stats.failed);
            response.put("healthy", stats.healthy);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            LOG.error("Error getting statistics", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve statistics: " + e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Gets all workloads (all pods across all namespaces).
     *
     * @return list of workloads
     */
    @GET
    @Path("/workloads")
    @Operation(summary = "List Kubernetes workloads")
    public Response getWorkloads() {
        LOG.info("Fetching all workloads");
        
        try {
            // Find all pods across all namespaces.
            // KubernetesMcpClient tries MCP first; if MCP is unreachable it automatically
            // falls back to direct Fabric8 access.
            List<Pod> pods;
            try {
                pods = kubernetesMcpClient.listAllPods();
            } catch (Exception e) {
                LOG.warnf("KubernetesMcpClient.listAllPods failed (%s), falling back to direct Fabric8", e.getMessage());
                pods = kubernetesClient.pods().inAnyNamespace().list().getItems();
            }
            
            List<Map<String, Object>> workloads = new ArrayList<>();
            
            for (Pod pod : pods) {
                Map<String, Object> workload = new HashMap<>();
                workload.put("namespace", pod.getMetadata().getNamespace());
                workload.put("podName", pod.getMetadata().getName());
                workload.put("status", pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown");
                
                // Get restart count
                int restarts = 0;
                if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                    restarts = pod.getStatus().getContainerStatuses().stream()
                            .mapToInt(cs -> cs.getRestartCount())
                            .sum();
                }
                workload.put("restarts", restarts);
                
                workloads.add(workload);
            }
            
            LOG.infof("Found %d workloads", workloads.size());
            return Response.ok(workloads).build();

        } catch (Exception e) {
            LOG.error("Error fetching workloads", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to fetch workloads: " + e.getMessage()))
                    .build();
        }
    }
    
    /**
     * Lists diagnostic issues. By default returns a summary projection.
     * When verbose=true, returns the full original RcaAnalysisSession objects as-is.
     */
    @GET
    @Path("/diagnostics/issues")
    @Operation(summary = "List diagnostic issues with summary-by-default and verbose full-session option")
    public Response getDiagnosticIssues(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("50") int pageSize,
            @QueryParam("verbose") @DefaultValue("false") boolean verbose) {

        LOG.infof("Getting diagnostic issues: page=%d, pageSize=%d, verbose=%s", page, pageSize, verbose);

        try {
            if (page < 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Page must be >= 0"))
                        .build();
            }
            if (pageSize < 1 || pageSize > 100) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Page size must be between 1 and 100"))
                        .build();
            }

            List<RcaAnalysisSession> analyses = trackingService.getUnhealthyAnalyses(page, pageSize);
            long totalCount = trackingService.countUnhealthyAnalyses();

            Map<String, Object> response = new HashMap<>();
            response.put("page", page);
            response.put("pageSize", pageSize);
            response.put("count", analyses.size());
            response.put("total", totalCount);
            response.put("verbose", verbose);

            if (verbose) {
                response.put("issues", analyses);
            } else {
                List<Map<String, Object>> issues = new ArrayList<>();
                for (RcaAnalysisSession analysis : analyses) {
                    issues.add(toIssueSummary(analysis));
                }
                response.put("issues", issues);
            }

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("Error getting diagnostic issues", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve diagnostic issues: " + e.getMessage()))
                    .build();
        }
    }

    private Map<String, Object> toIssueSummary(RcaAnalysisSession analysis) {
        Map<String, Object> issue = new HashMap<>();
        issue.put("sessionId", analysis.sessionId);
        issue.put("timestamp", analysis.timestamp);
        issue.put("namespace", analysis.namespace);
        issue.put("podName", analysis.podName);
        issue.put("status", analysis.status);
        issue.put("title", analysis.report != null ? analysis.report.title : null);
        issue.put("issue", analysis.report != null ? analysis.report.issue : null);
        return issue;
    }
}
