package com.autoblog;

import jakarta.validation.constraints.*;
import java.util.List;

public class DraftModels {
    public record Generated(String title,String html,String category,List<String> tags,List<String> checks,List<CommonsImages.Selection> imageSelections) {}
    public record Create(@NotBlank String analysisId,@Min(0) int issueIndex,@NotNull @Size(max=2000) String direction) {}
    public record Content(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=40000) String html,
        @NotNull @Size(max=80) String category,@NotNull @Size(max=10) List<@NotBlank @Size(max=60) String> tags,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> checks,
        @Size(max=3) List<@NotNull @jakarta.validation.Valid Image> images) {
        public Content(String title,String html,String category,List<String> tags,List<String> checks){this(title,html,category,tags,checks,List.of());}
    }
    public record Image(@NotBlank @Size(max=2048) String url,
        @NotBlank @Size(max=2048) String sourceUrl,@NotBlank @Size(max=300) String credit,
        @NotBlank @Size(max=300) String license,@NotBlank @Size(max=2048) String licenseUrl,
        @NotBlank @Size(max=500) String caption,@Min(0) @Max(100) int afterParagraph,
        @AssertTrue boolean rightsConfirmed) {}
    public record Publish(@NotBlank @Pattern(regexp="https://[a-zA-Z0-9-]+\\.tistory\\.com/?") String blogUrl) {}
}
