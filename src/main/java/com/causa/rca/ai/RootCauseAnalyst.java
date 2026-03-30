package com.causa.rca.ai;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * AI service interface for performing root cause analysis on detected anomalies.
 *
 * <p>Receives a <b>compact, LLM-safe summary context</b> produced by
 * {@link com.causa.rca.model.artifact.CollectedArtifacts#toLlmContext()}.
 * This context contains only pre-processed summaries — never raw log lines,
 * raw event dumps, or full JFR reports.</p>
 *
 * <p>This is the second step in the RCA pipeline, following anomaly detection.</p>
 *
 * @see AnomalyDetector
 * @see ValidationAgent
 * @see com.causa.rca.model.artifact.CollectedArtifacts
 */
@RegisterAiService(modelName = "rca")
public interface RootCauseAnalyst {

    /**
     * Analyses the root cause of a detected anomaly and proposes a solution.
     *
     * <p>The {@code llmContext} parameter contains pre-processed, token-budgeted
     * summaries of pod status, metrics, events, and representative log lines.
     * If JFR data is available it is included as a compact summary in the
     * {@code =JFR=} section of the context.</p>
     *
     * @param anomalyType the anomaly type token from {@link AnomalyDetector}
     *                    (e.g. {@code "OOM_KILLED"}, {@code "CPU_THROTTLING"})
     * @param llmContext  the compact summary context from
     *                    {@link com.causa.rca.model.artifact.CollectedArtifacts#toLlmContext()}
     * @return a detailed analysis string with root cause reasoning and proposed fix
     */
    @UserMessage("""
            Role: Root Cause Analysis Engine.
            CRITICAL OUTPUT RULES: 1. Output MUST be structured, NO markdown, NO explanations outside fields, NO extra text
                                   2. Return EXACTLY this structure with ALL sections:
                                   
                                   ROOT_CAUSE_TITLE: <A very short, one-liner summary of the root cause>
                                   
                                   SEVERITY: <One of: High, Medium, Low - based on impact>
                                   
                                   ROOT_CAUSE: <A very detailed explanation describing: What went wrong, Why it happened, How it relates to the detected anomaly. FOCUS ON WHAT WHY HOW?>
                                   
                                   SUPPORTING_EVIDENCE: <Exactly 2-4 bullet points with concrete technical details>
                                   - <First bullet: e.g., "Heap utilization consistently >90% during peak hours">
                                   - <Second bullet: e.g., "GC pause can be possible">
                                   - <Third bullet: e.g., "Default -Xmx setting detected (512MB) vs workload requirements">
                                   
                                   OBSERVABLE_SYMPTOMS: <Exactly 3-5 impact metrics from the signals>
                                   - User Impact: <e.g., "High - 30% requests >2s latency">
                                   - <Metric 1: e.g., "If there is OOMKilled events, 8 OOMKills/hr">
                                   - <Metric 2: e.g., "Memory usage was recorded at 90%">
                                   - <Metric 4: e.g., "Heap >90%">
                                   
                                   AFFECTED_SERVICES: <Comma-separated list of affected service/container names from signals>
                                   
                                   KEY_EVIDENCE: <A very detailed Bullet-style lines listing concrete signals (metrics, events, warnings). Each item must be directly observable in the provided signals. Avoid speculation or inferred data>
                                   
                                   SUPPORTED_LOGS: <Include ONLY verbatim log lines that appear in the provided signals. Select log lines that directly support the root cause. If no supporting log lines are present, output: no direct supported logs present>
            
            Input: ANOMALY: {anomalyType}
            Signals (summarized logs, metrics, events): {llmContext}
            
            Task: Produce a clear and accurate root cause analysis that can be understood by every stakeholders in easy words while remaining technically precise.
            
            Goals: Determine the most likely root cause based strictly on the provided signals.
            
            RULES:
                1. Use ONLY information present in signals
                2. Do NOT invent metrics or logs
                3. BASE ALL CONCLUSIONS ONLY on the PROVIDED signals
                4. SUPPORTING_EVIDENCE must be 3-4 concise technical bullets
                5. OBSERVABLE_SYMPTOMS must include User Impact first, then 3-4 metrics
                6. Extract actual service/container names from signals for AFFECTED_SERVICES
            """)
    String analyzeRootCause(@V("anomalyType") String anomalyType, @V("llmContext") String llmContext);
}
