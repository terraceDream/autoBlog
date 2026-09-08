package com.autoblog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.BooleanSupplier;

public interface AnalysisRunner {
    record Availability(boolean ready,String message) {}
    record Output(String json,Long inputTokens,Long outputTokens,Long cachedTokens) {}
    Availability availability();
    Output analyze(String prompt,JsonNode schema,BooleanSupplier cancelled) throws Exception;
    class Failure extends Exception {
        public final String status;
        public Failure(String status,String message) {super(message);this.status=status;}
    }
}
