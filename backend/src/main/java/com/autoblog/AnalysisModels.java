package com.autoblog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class AnalysisModels {
    private AnalysisModels() {}
    public record Request(
        @NotBlank String topicId,
        @NotNull @Size(min=1,max=20) List<@NotBlank String> articleIds,
        @NotNull @Pattern(regexp="QUICK|DETAILED") String mode,
        @NotNull @Size(max=1000) String direction) {}
    public record Source(String id,String title,String url,String sourceName,String publishedAt,
                         String coverage,String excerpt,boolean truncated) {}
    public record Input(String topicId,String topicName,String description,String instructions,
                        List<String> tags,String mode,String direction,List<Source> sources) {}
    public record Preview(Input input,int inputChars,int maxInputChars,int maxArticles,
                          int excerptLimit,int truncatedCount,boolean topicTruncated,String fingerprint,String cachedJobId) {}
    public record Start(@NotNull @Valid Request request,@NotBlank String fingerprint) {}
    public record Result(@NotNull @Size(max=20) List<@Valid Issue> issues,
                         @NotNull @Size(max=20) List<@Valid Excluded> excluded) {}
    public record Issue(
        @NotBlank @Size(max=160) String title,
        @NotBlank @Size(max=1500) String summary,
        @NotBlank @Size(max=80) String category,
        @NotBlank @Size(max=500) String categoryReason,
        @NotBlank @Size(max=1000) String angle,
        @NotBlank @Size(max=300) String audience,
        @NotNull @Size(min=1,max=3) List<@NotBlank @Size(max=160) String> suggestedTitles,
        @NotNull @Size(min=1,max=8) List<@NotBlank @Size(max=500) String> outline,
        @NotNull @Size(min=1,max=6) List<@NotBlank @Size(max=700) String> keyPoints,
        @NotNull @Size(max=8) List<@NotBlank @Size(max=60) String> tags,
        @NotNull @Size(min=1,max=20) List<@NotBlank String> sourceIds,
        @NotNull @Size(max=6) List<@NotBlank @Size(max=500) String> uncertainties) {}
    public record Excluded(@NotBlank String sourceId,@NotBlank @Size(max=500) String reason) {}
}
