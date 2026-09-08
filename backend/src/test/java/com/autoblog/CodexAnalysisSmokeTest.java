package com.autoblog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: consumes subscription usage for a tiny synthetic example, never user database contents. */
@EnabledIfEnvironmentVariable(named="ISSUE_DESK_CODEX_SMOKE",matches="true")
class CodexAnalysisSmokeTest {
    @Test void chatgptSubscriptionReturnsStructuredKoreanBrief() throws Exception {
        var json=new ObjectMapper();var runner=new CodexAnalysisRunner(json,"",180);
        assertThat(runner.availability().ready()).isTrue();
        String data="""
            {"topicName":"개발 도구","description":"개발자용 도구 업데이트","instructions":"기술 중심","tags":[],"mode":"QUICK","direction":"가상의 테스트 자료입니다. 매우 짧게 작성하세요.","sources":[{"id":"test-1","title":"ExampleTool v2 adds JSON export","url":"https://example.com/test","sourceName":"Synthetic fixture","coverage":"EXCERPT","excerpt":"This fictional test tool adds JSON export in version 2. No performance data is provided.","truncated":false}]}
            """;
        try(var schema=new ClassPathResource("analysis-schema.json").getInputStream()) {
            var output=runner.analyze(AnalysisService.INSTRUCTIONS+"\nDATA:\n"+data,json.readTree(schema),()->false);
            var result=json.readTree(output.json());assertThat(result.path("issues")).hasSize(1);assertThat(result.path("issues").get(0).path("sourceIds").get(0).asText()).isEqualTo("test-1");
            assertThat(result.path("issues").get(0).path("summary").asText()).containsPattern("[가-힣]");
            System.out.println("CODEX_SMOKE_OK inputTokens="+output.inputTokens()+" outputTokens="+output.outputTokens());
        }
    }
}
