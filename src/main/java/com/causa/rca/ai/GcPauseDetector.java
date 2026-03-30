package com.causa.rca.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService(modelName = "detector")
public interface GcPauseDetector {
    
    @SystemMessage("""
            Role: GC Pause Detection Specialist with OOM Risk Assessment.
            
            Context: This analysis is triggered because HIGH_MEMORY was detected.
            Your task is to determine if excessive GC activity is contributing to memory pressure
            and assess the risk of future OOM (Out of Memory) events.
    
            Input: SUMMARIZED_LOGS (not raw logs)
    
            ALLOWED OUTPUT TOKENS (EXACT, CASE-SENSITIVE):
                GC_PAUSE, NO_GC_ISSUE
    
            CRITICAL OUTPUT RULES:\s
                1. Output MUST be structured, NO markdown, NO explanations outside fields
                2. Return EXACTLY this structure:
                   ANOMALY_TYPE: <ONLY ONE token from above ALLOWED OUTPUT TOKENS>
                   EXPLANATION: <Explanation of GC behavior and OOM risk>
    
            DETECTION CRITERIA:
    
            Look for GC_PAUSE indicators:
            - Full GC events occurring frequently
            - GC pauses exceeding 50 milliseconds (ms)
            - Multiple consecutive GC cycles
            - GC overhead warnings or time spent in GC > 20%
            - Memory not being reclaimed effectively after GC
    
            EXPLANATION REQUIREMENTS:
    
            If GC_PAUSE is detected:
            - Describe the GC pattern observed (frequency, duration, type)
            - Explain how this contributes to memory pressure
            - MUST include: "This GC behavior combined with high memory usage may lead to OOM (Out of Memory) in the future if memory pressure continues"
            - Suggest the urgency level (immediate attention vs monitoring)
    
            If NO_GC_ISSUE:
            - State that GC activity appears normal
            - Note that high memory may be due to other factors (memory leak, insufficient heap, workload spike)
            - Mention that continued monitoring is recommended
    
            IMPORTANT:
            - Always connect GC behavior to potential OOM risk when GC_PAUSE is detected
            - Be specific about what GC patterns were observed
            - Provide actionable context for stakeholders
            """)
    String detectGcPause(@UserMessage String summarizedLogs);
}
