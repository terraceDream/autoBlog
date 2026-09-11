package com.autoblog;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {}
    public record TopicInput(
        @NotBlank @Size(max=120) String name, @NotNull @Size(max=4000) String description,
        @NotNull @Size(max=10000) String instructions,
        @NotNull @Size(max=30) List<@NotBlank @Size(max=100) String> keywords,
        @NotNull @Size(max=30) List<@NotBlank @Size(max=100) String> exclusions,
        @NotNull @Size(max=30) List<@NotBlank @Size(max=50) String> tags,
        @NotBlank @Pattern(regexp="[a-z]{2}(-[A-Za-z]{2,4})?") String language,
        @NotBlank @Pattern(regexp="[A-Z]{2}") String region,
        boolean active, boolean scheduleEnabled, @NotBlank @Size(max=100) String cron,
        @NotBlank @Size(max=80) String timezone) {}
    public record SourceInput(
        @NotBlank @Size(max=160) String name,
        @NotBlank @Pattern(regexp="RSS|HN_SEARCH|YOUTUBE|NAVER_NEWS|NAVER_BLOG") String type,
        @NotBlank @Pattern(regexp="NEWS|BLOG|VIDEO|OTHER") String media,
        @NotNull @Size(max=2048) String url, @NotNull @Size(max=500) String query,
        @NotNull @Size(max=100) String channelId, boolean enabled) {}
    public record StatusInput(@NotNull @Size(min=1,max=100) List<@NotBlank String> ids,
        @NotBlank String topicId, @Pattern(regexp="UNREAD|READ|SAVED|HIDDEN") @NotNull String status) {}
    public record Source(String id, String topicId, String name, String type, String media,
        String url, String query, String channelId, boolean enabled, String lastSuccess) {}
    public record Topic(String id, String name, String description, String instructions,
        List<String> keywords, List<String> exclusions, List<String> tags, String language,
        String region, boolean active, boolean scheduleEnabled, String cron, String timezone,
        String nextRun, String createdAt, String updatedAt, long articleCount, long sourceCount) {}
    public record Candidate(String title, String url, String author, String externalId,
        String excerpt, Instant publishedAt, java.util.Map<String,Object> signals) {
        public Candidate(String title,String url,String author,String externalId,String excerpt,Instant publishedAt){this(title,url,author,externalId,excerpt,publishedAt,java.util.Map.of());}
    }
    public record Module(String type, String name, String description, boolean ready, String setup) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
}
