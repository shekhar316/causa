package com.causa.rca.service;

import com.causa.rca.ai.*;
import com.causa.rca.model.RcaReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import com.causa.rca.model.RcaAnalysisSession;
import com.causa.rca.model.AnalysisStatus;
import com.causa.rca.model.artifact.CollectedArtifacts;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class RcaOrchestrator {

    private static final Logger LOG = Logger.getLogger(RcaOrchestrator.class);

    @Inject DataCollectorService dataCollector;
    @Inject AnomalyDetector anomalyDetector;
    @Inject RcaAssertionExtractor rcaAssertionExtractor;
    @Inject EvidenceMatcherAgent evidenceMatcher;
    @Inject AssertionValidatorAgent assertionValidator;
    @Inject RootCauseAnalyst rootCauseAnalyst;
    @Inject AnalysisTrackingService trackingService;
    @Inject GcPauseDetector gcPauseDetector;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.detector.chat-model.model-id", defaultValue = "llama2:7b-chat-q8_0")
    String detectorModel;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.rca.chat-model.model-id", defaultValue = "llama2:7b-chat-q8_0")
    String rcaModel;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url",
            defaultValue = "http://ollama.default.svc.cluster.local:11434")
    String ollamaBaseUrl;

    @Inject ManagedExecutor executor;
    ManagedContext requestContext = Arc.container().requestContext();

    public RcaAnalysisSession startAnalysis(String namespace, String podName) {

        LOG.info("Starting async RCA analysis for: " + namespace + "/" + podName);

        RcaAnalysisSession session = trackingService.createSession(namespace, podName);
        String sessionId = session.sessionId;

        executor.runAsync(() -> {
            requestContext.activate();
            try {
                runAnalysisInternal(sessionId, namespace, podName);
            } catch (Exception e) {
                LOG.error("Error in async analysis for session " + sessionId, e);
            } finally {
                requestContext.terminate();
            }
        });

        return session;
    }

    void runAnalysisInternal(String sessionId, String namespace, String podName) {

        try {
            String highLevelIssue = null;
            String subLevelIssue = null;

            trackingService.recordStageStart(sessionId, "data_collection");
            trackingService.updateStatus(sessionId, AnalysisStatus.COLLECTING_DATA,
                    "Collecting metrics, logs, and events from Kubernetes");

            CollectedArtifacts artifacts = dataCollector.collectArtifacts(namespace, podName);
            String llmContext = artifacts.toLlmContext();

            trackingService.storeArtifacts(sessionId, artifacts);
            trackingService.recordStageEnd(sessionId, "data_collection");

            ObjectMapper mapper = new ObjectMapper();

            trackingService.recordStageStart(sessionId, "anomaly_detection");
            trackingService.updateStatus(sessionId, AnalysisStatus.DETECTING_ANOMALY,
                    "Analyzing data to detect anomalies using AI");

            String anamolyContext = artifacts.toAnamolyLLMContext();
            String rawAnomaly = anomalyDetector.detectAnomaly(anamolyContext);
            String anomalyType = parseAnomalyType(rawAnomaly);

            LOG.info("Anomaly type: " + anomalyType);
            LOG.info("RAW Response: " + rawAnomaly);

            // Store anomaly type in session for UI display
            trackingService.updateAnomalyType(sessionId, anomalyType);
            // Extract explanation from raw anomaly response
            highLevelIssue = extractAnomalyExplanation(rawAnomaly);

            trackingService.recordStageEnd(sessionId, "anomaly_detection");

            if ("HEALTHY".equalsIgnoreCase(anomalyType)) {

                RcaReport healthyReport = new RcaReport(
                        "System Healthy",
                        "No anomaly detected",
                        null, // highLevelIssue
                        null, // subLevelIssue
                        "Metrics within normal range",
                        null, // supportedLogs
                        null, // assertions
                        null, // validationChecks
                        null); // finalDecision

                trackingService.markHealthy(sessionId, healthyReport);
                return;
            }

            // Call LLM again if the anomaly is HIGH_MEMORY
            if ("HIGH_MEMORY".equalsIgnoreCase(anomalyType)) {
                LOG.info("HIGH_MEMORY detected, performing second-stage GC pause detection...");
                trackingService.recordStageStart(sessionId, "memory_analysis");
                String summarizedLogsContext = artifacts.toSummarizedLogsContext();
                String gcDetectionRaw = gcPauseDetector.detectGcPause(summarizedLogsContext);
                String gcAnomalyType = parseGcAnomalyType(gcDetectionRaw);

                LOG.info("Second-stage GC detection result: " + gcAnomalyType);
                LOG.info("RAW GC Response: " + gcDetectionRaw);

                // Extract explanation from GC detection response
                subLevelIssue = gcDetectionRaw;

                LOG.info("Second-stage GC detection result: " + gcAnomalyType);

                if ("GC_PAUSE".equalsIgnoreCase(gcAnomalyType)) {
                    anomalyType = "GC_PAUSE";
                    trackingService.updateStatus(sessionId, AnalysisStatus.MEMORY_ANALYSIS,
                            "Memory pressure detected, analyzing memory usage and collecting GC logs");
                } else {
                    // Keep as HIGH_MEMORY or downgrade to OTHERS
                    anomalyType = "OTHERS";
                }
                trackingService.recordStageEnd(sessionId, "memory_analysis");
            }

            trackingService.recordStageStart(sessionId, "rca_analysis");

            String rcaOutput =
                    rootCauseAnalyst.analyzeRootCause(anomalyType, llmContext);
            LOG.info("rca output from  rootCauseAnalyst " +  rcaOutput);

            trackingService.recordStageEnd(sessionId, "rca_analysis");

            trackingService.recordStageStart(sessionId, "validation");

            // Extract all fields from RCA Analyst output
            String evidence = extractKeyEvidence(rcaOutput);
            List<String> supportedLogs = extractSupportedLogs(rcaOutput);
            String severity = extractSeverity(rcaOutput);
            List<String> supportingEvidenceBullets = extractSupportingEvidenceBullets(rcaOutput);
            List<String> observableSymptoms = extractObservableSymptoms(rcaOutput);
            List<String> affectedServices = extractAffectedServices(rcaOutput);
            
            LOG.info("Extracted evidence from RCA Analyst: " + evidence);
            LOG.info("Extracted " + supportedLogs.size() + " supported logs from RCA Analyst");
            LOG.info("Extracted severity: " + severity);
            LOG.info("Extracted " + supportingEvidenceBullets.size() + " supporting evidence bullets");
            LOG.info("Extracted " + observableSymptoms.size() + " observable symptoms");
            LOG.info("Extracted " + affectedServices.size() + " affected services");

            String rcaSection = extractRootCause(rcaOutput);
            LOG.info("rca section sent as input to assertion extractor " +  rcaSection);
            String extractionRaw = rcaAssertionExtractor.extract(rcaSection);
            LOG.info("assertions output " +extractionRaw);

            JsonNode extractionNode =
                    safeParseValidatorOutput(extractionRaw, mapper);

            String issue =
                    extractionNode.path("issueIdentified")
                            .asText("Unknown Issue");

            ArrayNode assertionsNode =
                    (ArrayNode) extractionNode.get("assertions");

            String logsContext = llmContext;

            List<Map<String, Object>> finalAssertions = new ArrayList<>();
            int weightedScore = 0;

            for (JsonNode assertionNode : assertionsNode) {

                String assertion = assertionNode.asText();

                LOG.info("evidence i/p for assertion " + assertion + " - logs - " + logsContext);


                String evidenceRaw =
                        evidenceMatcher.match(assertion, logsContext);

                LOG.info(" evidenceRaw o/p " + evidenceRaw + " for assertion " + assertion + "logs " + logsContext);

                JsonNode evidenceNode =
                        safeParseValidatorOutput(evidenceRaw, mapper);

                List<String> matchedLogs = new ArrayList<>();

                if (evidenceNode.has("matchedLogs")) {

                    for (JsonNode log : evidenceNode.get("matchedLogs")) {

                        String l;

                        if (log.isTextual()) {
                            l = log.asText();
                        } else if (log.has("text")) {
                            l = log.get("text").asText();
                        } else {
                            continue;
                        }

                        if (l.length() < 400) {
                            matchedLogs.add(l);
                        }

                        if (matchedLogs.size() >= 3) break;
                    }
                }
                LOG.info("sending to validator assertion" + assertion + " matched Logs: " + matchedLogs);
                String validationRaw =
                        assertionValidator.validate(
                                assertion,
                                String.join("\n", matchedLogs));


                LOG.info("validation o/p from validator : " + validationRaw);
                JsonNode validationNode =
                        safeParseValidatorOutput(validationRaw, mapper);

                List<String> modelChecks = new ArrayList<>();

                if (validationNode.has("modelAnalysisQuestions")) {

                    for (JsonNode q :
                            validationNode.get("modelAnalysisQuestions")) {

                        modelChecks.add(q.asText());

                        if (modelChecks.size() >= 3) break;
                    }
                }

                String judgement =
                        validationNode.path("judgementCall")
                                .asText("Unsupported");

                String matchType =
                        validationNode.path("matchType")
                                .asText("none");

                double confidence =
                        validationNode.path("confidence")
                                .asDouble(0.3);

                String reasoning =
                        validationNode.path("reasoning")
                                .asText("");

                Map<String, Object> judgmentCall =
                        new LinkedHashMap<>();

                judgmentCall.put("decision", judgement);
                judgmentCall.put("matchType", matchType);
                judgmentCall.put("confidence", confidence);
                judgmentCall.put("reasoning", reasoning);

                Map<String, Object> assertionBlock =
                        new LinkedHashMap<>();

                assertionBlock.put("assertion", assertion);
                assertionBlock.put("matchedLogs", matchedLogs);
                assertionBlock.put("modelAnalysisQuestions", modelChecks);
                assertionBlock.put("judgmentCall", judgmentCall);

                finalAssertions.add(assertionBlock);

                if ("Supported".equals(judgement)) {
                    weightedScore += 2;
                } else if ("Partially Supported".equals(judgement)) {
                    weightedScore += 1;
                }
            }

            // Note: evidence and supportedLogs are already extracted from RCA Analyst output above
            // No need to re-extract from matchedLogs

            double ratio =
                    (double) weightedScore /
                            (finalAssertions.size() * 2);

            String finalStatus =
                    ratio >= 0.65 ? "Supported"
                            : ratio >= 0.30 ? "Partially Supported"
                            : "Unsupported";

            Map<String, Object> finalDecision =
                    Map.of(
                            "status", finalStatus,
                            "summary",
                            "Assertions validated independently and aggregated deterministically."
                    );

            Map<String, Object> finalReport =
                    new LinkedHashMap<>();

            finalReport.put("title", extractRootCauseTitle(rcaOutput));
            if (null != highLevelIssue)
                finalReport.put("highLevelIssue", highLevelIssue);
            if (null != subLevelIssue)
                finalReport.put("subLevelIssue", subLevelIssue);
            finalReport.put("issue", issue);
            finalReport.put("evidence", evidence);
            finalReport.put("supportedLogs", supportedLogs);
            finalReport.put("assertions", finalAssertions);
            finalReport.put("finalDecision", finalDecision);
            
            // Add new structured fields
            finalReport.put("severity", severity);
            finalReport.put("confidenceLevel", finalStatus); // Use validation result as confidence
            finalReport.put("supportingEvidenceBullets", supportingEvidenceBullets);
            finalReport.put("observableSymptoms", observableSymptoms);
            finalReport.put("affectedServices", affectedServices);

            String finalJson =
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(finalReport);

            RcaReport report =
                    mapper.readValue(finalJson, RcaReport.class);

            trackingService.recordStageEnd(sessionId, "validation");
            trackingService.completeSession(sessionId, report);

        } catch (Exception e) {

            LOG.error(
                    "Error during RCA analysis for session "
                            + sessionId,
                    e
            );

            trackingService.failSession(sessionId, e.getMessage());
        }
    }

    private String extractLogs(String context) {

        StringBuilder logs = new StringBuilder();

        for (String line : context.split("\n")) {

            String lower = line.toLowerCase();

            if (lower.contains("oom")
                    || lower.contains("killed")
                    || lower.contains("crashloop")
                    || lower.contains("backoff")
                    || lower.contains("error")
                    || lower.contains("failed")) {

                logs.append(line).append("\n");
            }
        }

        return logs.toString();
    }

    private JsonNode safeParseValidatorOutput(
            String raw,
            ObjectMapper mapper
    ) throws Exception {

        if (raw == null || raw.isBlank()) {

            Map<String,Object> fallback = new HashMap<>();
            fallback.put("matchedLogs", List.of());
            fallback.put("matchType","none");
            fallback.put("modelAnalysisQuestions", List.of());
            fallback.put("judgementCall","Unsupported");
            fallback.put("confidence",0.2);
            fallback.put("reasoning","Model returned empty response");

            return mapper.valueToTree(fallback);
        }

        raw = raw.replace("```json","")
                .replace("```","")
                .trim();

        int start = raw.indexOf("{");
        int end = raw.lastIndexOf("}");

        // ✅ If JSON exists
        if(start != -1 && end != -1 && end > start){

            String json = raw.substring(start, end + 1);

            json = json
                    .replaceAll(",\\s*]","]")
                    .replaceAll(",\\s*}","}")
                    .replaceAll("\\\\n"," ")
                    .trim();

            try {
                return mapper.readTree(json);
            }
            catch(Exception e){
                LOG.warn("Invalid JSON from model, falling back:\n"+raw);
            }
        }

        // ✅ Fallback when model returned text
        LOG.warn("Model returned non-JSON output:\n" + raw);

        Map<String,Object> fallback = new HashMap<>();
        fallback.put("matchedLogs", List.of());
        fallback.put("matchType","none");
        fallback.put("modelAnalysisQuestions", List.of());
        fallback.put("judgementCall","Unsupported");
        fallback.put("confidence",0.2);
        fallback.put("reasoning", raw);

        return mapper.valueToTree(fallback);
    }

    private String parseAnomalyType(String raw) {

        if (raw == null) return "OTHERS";

        // Define priority order (highest to lowest)
        String[] priorityOrder = {
            "HIGH_MEMORY",
            "GC_PAUSE",
            "OOM_KILLED",
            "CPU_THROTTLING",
            "CRASH_LOOP",
            "IMAGE_PULL_BACKOFF",
            "HEALTHY"
        };

        // Collect all matching anomaly types
        Set<String> foundAnomalies = new HashSet<>();
        for (String word : raw.toUpperCase().split("[\\s:]+")) {
            if (word.equals("HIGH_MEMORY")
                    || word.equals("GC_PAUSE")
                    || word.equals("OOM_KILLED")
                    || word.equals("CPU_THROTTLING")
                    || word.equals("CRASH_LOOP")
                    || word.equals("IMAGE_PULL_BACKOFF")
                    || word.equals("HEALTHY")) {
                foundAnomalies.add(word);
            }
        }

        // Return the highest priority anomaly found
        for (String anomaly : priorityOrder) {
            if (foundAnomalies.contains(anomaly)) {
                return anomaly;
            }
        }

        return "OTHERS";
    }

    private String extractRootCauseTitle(String rcaOutput) {

        Pattern pattern = Pattern.compile(
                "(?i)ROOT[_ ]CAUSE[_ ]TITLE\\s*:\\s*(.*?)(?=ROOT[_ ]CAUSE\\s*:|$)",
                Pattern.DOTALL);

        Matcher matcher = pattern.matcher(rcaOutput);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return "Root Cause Analysis Report";
    }

    private String extractRootCause(String rcaOutput) {

        Pattern pattern = Pattern.compile(
                "(?i)ROOT[_ ]CAUSE\\s*:\\s*(.*?)(?=KEY[_ ]EVIDENCE\\s*:|SUPPORTED[_ ]LOGS\\s*:|$)",
                Pattern.DOTALL);

        Matcher matcher = pattern.matcher(rcaOutput);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return rcaOutput.trim();
    }

    /**
     * Extracts the SUPPORTED LOGS section from the RCA Analyst output.
     * Returns a list of log lines that were identified by the RCA Analyst.
     */
    private List<String> extractSupportedLogs(String rcaOutput) {
        List<String> logs = new ArrayList<>();
        
        if (rcaOutput == null || rcaOutput.trim().isEmpty()) {
            return logs;
        }

        // Extract SUPPORTED LOGS section
        Pattern pattern = Pattern.compile(
                "(?i)SUPPORTED[_ ]LOGS\\s*:\\s*(.*?)(?=\\n\\n|$)",
                Pattern.DOTALL);

        Matcher matcher = pattern.matcher(rcaOutput);

        if (matcher.find()) {
            String logsSection = matcher.group(1).trim();
            
            // Skip if it says "no direct supported logs present"
            if (logsSection.toLowerCase().contains("no direct supported logs present")) {
                LOG.info("RCA Analyst indicated no direct supported logs present");
                return logs;
            }

            // Split by newlines and filter out empty lines
            String[] lines = logsSection.split("\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                // Skip empty lines, section headers, and lines that are just dashes/bullets
                if (!trimmed.isEmpty()
                    && !trimmed.matches("^[-*•]+$")
                    && trimmed.length() > 10) {
                    // Remove leading bullets/dashes
                    trimmed = trimmed.replaceFirst("^[-*•]\\s*", "");
                    logs.add(trimmed);
                }
            }
        }

        LOG.info("Extracted " + logs.size() + " supported logs from RCA Analyst output");
        return logs;
    }

    /**
     * Extracts the KEY EVIDENCE section from the RCA Analyst output.
     * Returns the evidence text that was identified by the RCA Analyst.
     */
    private String extractKeyEvidence(String rcaOutput) {
        if (rcaOutput == null || rcaOutput.trim().isEmpty()) {
            return "No evidence available";
        }

        // Extract KEY EVIDENCE section
        Pattern pattern = Pattern.compile(
                "(?i)KEY[_ ]EVIDENCE\\s*:\\s*(.*?)(?=SUPPORTED[_ ]LOGS\\s*:|$)",
                Pattern.DOTALL);

        Matcher matcher = pattern.matcher(rcaOutput);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return "No evidence extracted";
    }

    /**
     * Parses the GC pause detection response.
     * Extracts GC_PAUSE or NO_GC_ISSUE from the LLM output.
     */
    private String parseGcAnomalyType(String raw) {
        if (raw == null) return "NO_GC_ISSUE";
        
        for (String word : raw.toUpperCase().split("[\\s:]+")) {
            if (word.equals("GC_PAUSE")) {
                return "GC_PAUSE";
            }
            if (word.equals("NO_GC_ISSUE")) {
                return "NO_GC_ISSUE";
            }
        }
        
        return "NO_GC_ISSUE";
    }

    /**
     * Extracts the SEVERITY field from RCA output.
     */
    private String extractSeverity(String rcaOutput) {
        if (rcaOutput == null || rcaOutput.trim().isEmpty()) {
            return "Medium";
        }
        
        Pattern pattern = Pattern.compile(
                "(?i)SEVERITY\\s*:\\s*(.*?)(?=\\n|$)",
                Pattern.DOTALL);
        
        Matcher matcher = pattern.matcher(rcaOutput);
        
        if (matcher.find()) {
            String severity = matcher.group(1).trim();
            // Validate it's one of the expected values
            if (severity.matches("(?i)(High|Medium|Low)")) {
                return severity.substring(0, 1).toUpperCase() + severity.substring(1).toLowerCase();
            }
        }
        
        return "Medium";
    }
    
    /**
     * Extracts the SUPPORTING_EVIDENCE bullets from RCA output.
     */
    private List<String> extractSupportingEvidenceBullets(String rcaOutput) {
        List<String> bullets = new ArrayList<>();
        
        if (rcaOutput == null || rcaOutput.trim().isEmpty()) {
            return bullets;
        }
        
        Pattern pattern = Pattern.compile(
                "(?i)SUPPORTING_EVIDENCE\\s*:\\s*(.*?)(?=OBSERVABLE_SYMPTOMS\\s*:|KEY_EVIDENCE\\s*:|$)",
                Pattern.DOTALL);
        
        Matcher matcher = pattern.matcher(rcaOutput);
        
        if (matcher.find()) {
            String section = matcher.group(1).trim();
            String[] lines = section.split("\\n");
            
            for (String line : lines) {
                String trimmed = line.trim();
                // Extract bullet points (lines starting with - or •)
                if (trimmed.matches("^[-•]\\s+.+")) {
                    trimmed = trimmed.replaceFirst("^[-•]\\s+", "");
                    if (trimmed.length() > 10) {
                        bullets.add(trimmed);
                    }
                }
            }
        }
        
        return bullets;
    }
    
    /**
     * Extracts the OBSERVABLE_SYMPTOMS from RCA output.
     */
    private List<String> extractObservableSymptoms(String rcaOutput) {
        List<String> symptoms = new ArrayList<>();
        
        if (rcaOutput == null || rcaOutput.trim().isEmpty()) {
            return symptoms;
        }
        
        Pattern pattern = Pattern.compile(
                "(?i)OBSERVABLE_SYMPTOMS\\s*:\\s*(.*?)(?=AFFECTED_SERVICES\\s*:|KEY_EVIDENCE\\s*:|$)",
                Pattern.DOTALL);
        
        Matcher matcher = pattern.matcher(rcaOutput);
        
        if (matcher.find()) {
            String section = matcher.group(1).trim();
            String[] lines = section.split("\\n");
            
            for (String line : lines) {
                String trimmed = line.trim();
                // Extract bullet points or lines with metrics
                if (trimmed.matches("^[-•]\\s+.+") || trimmed.contains(":")) {
                    trimmed = trimmed.replaceFirst("^[-•]\\s+", "");
                    if (trimmed.length() > 5) {
                        symptoms.add(trimmed);
                    }
                }
            }
        }
        
        return symptoms;
    }
    
    /**
     * Extracts the AFFECTED_SERVICES from RCA output.
     */
    private List<String> extractAffectedServices(String rcaOutput) {
        List<String> services = new ArrayList<>();
        
        if (rcaOutput == null || rcaOutput.trim().isEmpty()) {
            return services;
        }
        
        Pattern pattern = Pattern.compile(
                "(?i)AFFECTED_SERVICES\\s*:\\s*(.*?)(?=\\n\\n|KEY_EVIDENCE\\s*:|$)",
                Pattern.DOTALL);
        
        Matcher matcher = pattern.matcher(rcaOutput);
        
        if (matcher.find()) {
            String section = matcher.group(1).trim();
            // Split by comma and clean up
            String[] parts = section.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && trimmed.length() > 2) {
                    services.add(trimmed);
                }
            }
        }
        
        return services;
    }

    /**
     * Extracts the explanation/description from the raw anomaly response.
     * Looks for patterns like "EXPLANATION:" or descriptive text after the anomaly type.
     */
    private String extractAnomalyExplanation(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return "No explanation available";
        }

        // Try to extract EXPLANATION: section
        Pattern explanationPattern = Pattern.compile(
            "(?i)EXPLANATION\\s*:\\s*(.*?)(?=\\n\\n|$)",
            Pattern.DOTALL
        );
        Matcher explanationMatcher = explanationPattern.matcher(rawResponse);
        if (explanationMatcher.find()) {
            return explanationMatcher.group(1).trim();
        }

        // Try to extract text after anomaly type (e.g., "HIGH_MEMORY: The system...")
        Pattern afterTypePattern = Pattern.compile(
            "(?i)(?:HIGH_MEMORY|GC_PAUSE|OOM_KILLED|CPU_THROTTLING|CRASH_LOOP|IMAGE_PULL_BACKOFF|NO_GC_ISSUE)\\s*[:\\-]\\s*(.*?)(?=\\n\\n|$)",
            Pattern.DOTALL
        );
        Matcher afterTypeMatcher = afterTypePattern.matcher(rawResponse);
        if (afterTypeMatcher.find()) {
            return afterTypeMatcher.group(1).trim();
        }

        // If no pattern matches, return the full response cleaned up
        String cleaned = rawResponse
            .replaceAll("(?i)(HIGH_MEMORY|GC_PAUSE|OOM_KILLED|CPU_THROTTLING|CRASH_LOOP|IMAGE_PULL_BACKOFF|NO_GC_ISSUE|HEALTHY|OTHERS)\\s*[:\\-]?\\s*", "")
            .trim();
        
        return cleaned.isEmpty() ? rawResponse.trim() : cleaned;
    }
}