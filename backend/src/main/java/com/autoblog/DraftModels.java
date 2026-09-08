package com.autoblog;

import jakarta.validation.constraints.*;
import java.util.List;

public class DraftModels {
    public record Create(@NotBlank String analysisId,@Min(0) int issueIndex,@NotNull @Size(max=2000) String direction) {}
    public record Content(@NotBlank @Size(max=160) String title,@NotBlank @Size(max=40000) String html,
        @NotBlank @Size(max=80) String category,@NotNull @Size(max=10) List<@NotBlank @Size(max=60) String> tags,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=1000) String> checks) {}
    public record Publish(@NotBlank @Pattern(regexp="https://[a-zA-Z0-9-]+\\.tistory\\.com/?") String blogUrl) {}
}
