package jobagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests: a scripted fake model and an in-memory store, real LangGraph4j + SQLite checkpoints. */
class JdIntakePipelineTest {

    /** Fake Claude: a Spring AI ChatModel that answers based on which prompt it receives. */
    static ChatClient fakeLlm(String parseJson, Function<String, String> scorer) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt request) {
                String prompt = request.getContents();
                String reply;
                if (prompt.startsWith("Extract")) {
                    reply = parseJson;
                } else if (prompt.startsWith("Score")) {
                    reply = scorer.apply(prompt);
                } else {
                    reply = "A clearer, rewritten job description.";
                }
                return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(reply))));
            }
        };
        return ChatClient.create(model);
    }

    static final String FULL = "{\"title\":\"Backend Engineer\",\"company\":\"Acme\",\"location\":\"Bangalore\","
            + "\"exp\":\"5+ years\",\"workMode\":\"hybrid\"}";
    static final String PARTIAL = "{\"title\":\"Backend Engineer\",\"company\":\"Acme\",\"location\":\"\","
            + "\"exp\":\"\",\"workMode\":\"\"}";
    static final Function<String, String> GOOD = p -> "```json\n{\"score\": 90, \"feedback\": \"clear\"}\n```";
    static final Function<String, String> BAD = p -> "{\"score\": 40, \"feedback\": \"vague\"}";

    private CompiledGraph<JdState> graph(Path dir, ChatClient llm, JobStore store) throws Exception {
        return new JdIntakePipeline(llm, store).buildGraph(JdIntakePipeline.checkpointer(dir.resolve("cp.sqlite").toString()));
    }

    @Test
    void completeJdIsPublished(@TempDir Path dir) throws Exception {
        var g = graph(dir, fakeLlm(FULL, GOOD), new InMemoryJobStore());
        var result = JdIntakePipeline.start(g, "any jd", "t1");
        assertFalse(result.paused());
        assertEquals("published", result.finalState().status());
        assertEquals("mem-1", result.finalState().jobId());
    }

    @Test
    void anApiFailureIsNotMistakenForAnEmptyExtraction(@TempDir Path dir) throws Exception {
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(Prompt request) {
                throw new IllegalStateException("401 invalid x-api-key");
            }
        };
        var g = graph(dir, ChatClient.create(broken), new InMemoryJobStore());
        var ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> JdIntakePipeline.start(g, "any jd", "t-api-failure"));
        assertTrue(String.valueOf(ex).contains("401 invalid x-api-key") || String.valueOf(ex.getCause()).contains("401"));
    }

    @Test
    void duplicateIsRejected(@TempDir Path dir) throws Exception {
        var store = new InMemoryJobStore();
        var g = graph(dir, fakeLlm(FULL, GOOD), store);
        JdIntakePipeline.start(g, "any jd", "t1");
        var second = JdIntakePipeline.start(g, "any jd", "t2");
        assertEquals("rejected", second.finalState().status());
    }

    @Test
    void missingFieldsAreAskedOneAtATime(@TempDir Path dir) throws Exception {
        var g = graph(dir, fakeLlm(PARTIAL, GOOD), new InMemoryJobStore());
        var r = JdIntakePipeline.start(g, "jd", "t1");
        assertEquals("What is the location?", r.question());
        r = JdIntakePipeline.resume(g, "t1", "Bangalore");
        assertEquals("What is the exp?", r.question());
        r = JdIntakePipeline.resume(g, "t1", "5 years");
        assertEquals("What is the work_mode?", r.question());
        r = JdIntakePipeline.resume(g, "t1", "hybrid");
        assertFalse(r.paused());
        assertEquals("published", r.finalState().status());
        assertEquals("Bangalore", r.finalState().parsed().get("location"));
        assertTrue(r.finalState().log().stream().anyMatch(l -> l.contains("got 'exp'")));
    }

    @Test
    void pausedRunSurvivesAProcessRestart(@TempDir Path dir) throws Exception {
        var store = new InMemoryJobStore();
        var first = graph(dir, fakeLlm(PARTIAL, GOOD), store);
        assertEquals("What is the location?", JdIntakePipeline.start(first, "jd", "t1").question());

        // "Restart": brand-new graph and checkpointer objects over the same SQLite file.
        var second = graph(dir, fakeLlm(PARTIAL, GOOD), store);
        var r = JdIntakePipeline.resume(second, "t1", "Bangalore");
        assertEquals("What is the exp?", r.question());
    }

    @Test
    void poorQualityRewritesTwiceThenEscalates(@TempDir Path dir) throws Exception {
        var g = graph(dir, fakeLlm(FULL, BAD), new InMemoryJobStore());
        var r = JdIntakePipeline.start(g, "jd", "t1");
        assertEquals("needs_input", r.finalState().status());
        assertEquals(2, r.finalState().revisionCount());
    }

    @Test
    void tooManyQuestionsEscalates(@TempDir Path dir) throws Exception {
        // No required field can ever be parsed, but only 5 exist, so the 6-question cap
        // is exercised directly through the routing rule.
        var pipeline = new JdIntakePipeline(fakeLlm(PARTIAL, GOOD), new InMemoryJobStore());
        var state = new JdState(java.util.Map.of(
                "missing_fields", java.util.List.of("exp"),
                "missing_info_attempts", JdIntakePipeline.MAX_MISSING_INFO_ATTEMPTS));
        assertEquals("escalate", pipeline.routeAfterRequestMissingInfo(state));
    }
}
