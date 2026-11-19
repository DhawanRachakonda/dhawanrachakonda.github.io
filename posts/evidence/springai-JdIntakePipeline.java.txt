package jobagent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.SQLiteSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * JD Intake Pipeline - a LangGraph4j workflow with Spring AI (Claude) for the LLM steps,
 * hosted in a Spring Boot application (see JobAgentConfig and ChatController).
 *
 * Same design as the Python version: every node is a fixed step. Some are plain Java
 * (duplicate check, routing), some make one LLM call for one job (extract, score, rewrite).
 * LangGraph4j supplies typed state, conditional edges, bounded loops, and a human-in-the-loop
 * pause that survives a restart (SQLite checkpoints keyed by thread id).
 */
public class JdIntakePipeline {

    static final List<String> REQUIRED_FIELDS = List.of("title", "company", "location", "exp", "work_mode");
    static final int QUALITY_THRESHOLD = 75;
    static final int MAX_REVISIONS = 2;
    /** Fields are asked one at a time, so this is a total-question cap, not a round cap. */
    static final int MAX_MISSING_INFO_ATTEMPTS = 6;

    static final String NODE_REQUEST_MISSING_INFO = "request_missing_info";

    private final ChatClient llm;
    private final JobStore store;

    public JdIntakePipeline(ChatClient llm, JobStore store) {
        this.llm = llm;
        this.store = store;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> update(String note, Map<String, Object> changes) {
        Map<String, Object> out = new LinkedHashMap<>(changes);
        out.put("log", List.of(note)); // the "log" channel appends
        return out;
    }

    // ─── Nodes ────────────────────────────────────────────────────────────────

    /** The model's answer to the extraction prompt; Spring AI maps the reply onto this record. */
    record ExtractedFields(String title, String company, String location, String exp, String workMode) {}

    /** The model's answer to the scoring prompt. */
    record QualityScore(int score, String feedback) {}

    private static final BeanOutputConverter<ExtractedFields> EXTRACT = new BeanOutputConverter<>(ExtractedFields.class);
    private static final BeanOutputConverter<QualityScore> SCORE = new BeanOutputConverter<>(QualityScore.class);

    Map<String, Object> parseJd(JdState state) {
        String prompt = "Extract these fields from the job description below, using an empty string for any "
                + "field that isn't clearly mentioned: title, company, location, exp, workMode.\n\n"
                + "Job description:\n" + state.rawJd();
        // An API failure (bad key, network) propagates. Only a reply that can't be read as the
        // record is treated as "nothing extracted", so the recruiter is asked for every field.
        String reply = llm.prompt().user(prompt + "\n\n" + EXTRACT.getFormat()).call().content();
        ExtractedFields f;
        try {
            f = EXTRACT.convert(reply);
        } catch (Exception e) {
            f = new ExtractedFields("", "", "", "", "");
        }

        Map<String, String> parsed = new LinkedHashMap<>();
        parsed.put("title", clean(f == null ? null : f.title()));
        parsed.put("company", clean(f == null ? null : f.company()));
        parsed.put("location", clean(f == null ? null : f.location()));
        parsed.put("exp", clean(f == null ? null : f.exp()));
        parsed.put("work_mode", clean(f == null ? null : f.workMode()));

        List<String> missing = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            if (parsed.get(field).isEmpty()) {
                missing.add(field);
            }
        }
        return update("parse_jd: parsed=" + parsed + ", missing=" + missing,
                Map.of("parsed", parsed, "missing_fields", missing));
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }

    String routeAfterParse(JdState state) {
        return state.missingFields().isEmpty() ? "check_duplicate" : NODE_REQUEST_MISSING_INFO;
    }

    /**
     * Human in the loop. The graph is compiled with interruptBefore(request_missing_info), so
     * execution pauses BEFORE this node and the caller writes the recruiter's reply into
     * state ("answer") before resuming. The reply is taken verbatim as the field's value.
     */
    Map<String, Object> requestMissingInfo(JdState state) {
        List<String> missing = state.missingFields();
        String field = missing.get(0);
        Map<String, String> parsed = state.parsed();
        parsed.put(field, state.answer().strip());
        return update("request_missing_info: got '" + field + "' from the recruiter", Map.of(
                "parsed", parsed,
                "missing_fields", new ArrayList<>(missing.subList(1, missing.size())),
                "missing_info_attempts", state.missingInfoAttempts() + 1));
    }

    String routeAfterRequestMissingInfo(JdState state) {
        if (!state.missingFields().isEmpty()) {
            return state.missingInfoAttempts() >= MAX_MISSING_INFO_ATTEMPTS
                    ? "escalate" : NODE_REQUEST_MISSING_INFO;
        }
        return "check_duplicate";
    }

    Map<String, Object> checkDuplicate(JdState state) {
        Map<String, String> p = state.parsed();
        String duplicateOf = store.findDuplicate(p.get("title"), p.get("company")).orElse("");
        return update("check_duplicate: duplicate_of=" + (duplicateOf.isEmpty() ? "None" : duplicateOf),
                Map.of("duplicate_of", duplicateOf));
    }

    String routeAfterDuplicateCheck(JdState state) {
        return state.duplicateOf().isPresent() ? "reject" : "score_quality";
    }

    Map<String, Object> scoreQuality(JdState state) {
        String prompt = "Score this job description from 0-100 on clarity, specificity, and "
                + "bias-free language, with a 1-2 sentence critique as feedback.\n\n"
                + "Job description:\n" + state.currentDraft();
        String reply = llm.prompt().user(prompt + "\n\n" + SCORE.getFormat()).call().content();
        int score;
        String feedback;
        try {
            QualityScore q = SCORE.convert(reply);
            score = q.score();
            feedback = q.feedback() == null ? "" : q.feedback();
        } catch (Exception e) {
            score = 0;
            feedback = "Could not parse a quality score from the model's reply.";
        }
        return update("score_quality: " + score + "/100 - " + feedback,
                Map.of("quality_score", score, "quality_feedback", feedback));
    }

    /** The threshold and revision cap are fixed rules, not LLM discretion. */
    String routeAfterScore(JdState state) {
        if (state.qualityScore() >= QUALITY_THRESHOLD) {
            return "publish_job";
        }
        return state.revisionCount() < MAX_REVISIONS ? "rewrite_jd" : "escalate";
    }

    Map<String, Object> rewriteJd(JdState state) {
        String prompt = "Rewrite this job description to address the following feedback: \""
                + state.qualityFeedback() + "\". Reply with only the rewritten job "
                + "description, no commentary, no markdown fences.\n\n"
                + "Job description:\n" + state.currentDraft();
        String rewritten = llm.prompt().user(prompt).call().content().strip();
        int revision = state.revisionCount() + 1;
        return update("rewrite_jd: revision #" + revision,
                Map.of("rewritten_jd", rewritten, "revision_count", revision));
    }

    Map<String, Object> publishJob(JdState state) {
        String jobId = store.publish(state.parsed(), state.currentDraft(), state.qualityScore());
        return update("publish_job: job_id=" + jobId, Map.of("job_id", jobId, "status", "published"));
    }

    Map<String, Object> reject(JdState state) {
        return update("reject: duplicate of an existing job posting", Map.of("status", "rejected"));
    }

    Map<String, Object> escalate(JdState state) {
        return update("escalate: needs manual review", Map.of("status", "needs_input"));
    }

    // ─── Graph assembly ───────────────────────────────────────────────────────

    public CompiledGraph<JdState> buildGraph(BaseCheckpointSaver checkpointer) throws GraphStateException {
        var graph = new StateGraph<>(JdState.SCHEMA, JdState::new);

        graph.addNode("parse_jd", node_async(this::parseJd));
        graph.addNode(NODE_REQUEST_MISSING_INFO, node_async(this::requestMissingInfo));
        graph.addNode("check_duplicate", node_async(this::checkDuplicate));
        graph.addNode("score_quality", node_async(this::scoreQuality));
        graph.addNode("rewrite_jd", node_async(this::rewriteJd));
        graph.addNode("publish_job", node_async(this::publishJob));
        graph.addNode("reject", node_async(this::reject));
        graph.addNode("escalate", node_async(this::escalate));

        graph.addEdge(START, "parse_jd");
        graph.addConditionalEdges("parse_jd", edge_async(this::routeAfterParse), Map.of(
                NODE_REQUEST_MISSING_INFO, NODE_REQUEST_MISSING_INFO,
                "check_duplicate", "check_duplicate"));
        graph.addConditionalEdges(NODE_REQUEST_MISSING_INFO, edge_async(this::routeAfterRequestMissingInfo), Map.of(
                NODE_REQUEST_MISSING_INFO, NODE_REQUEST_MISSING_INFO,
                "escalate", "escalate",
                "check_duplicate", "check_duplicate"));
        graph.addConditionalEdges("check_duplicate", edge_async(this::routeAfterDuplicateCheck), Map.of(
                "reject", "reject",
                "score_quality", "score_quality"));
        graph.addConditionalEdges("score_quality", edge_async(this::routeAfterScore), Map.of(
                "publish_job", "publish_job",
                "rewrite_jd", "rewrite_jd",
                "escalate", "escalate"));
        graph.addEdge("rewrite_jd", "score_quality");
        graph.addEdge("publish_job", END);
        graph.addEdge("reject", END);
        graph.addEdge("escalate", END);

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(checkpointer)
                .interruptBefore(NODE_REQUEST_MISSING_INFO)
                .releaseThread(false) // keep checkpoints after the run ends, like the Python SqliteSaver
                .build());
    }

    // ─── Run loop (shared by the web controller and tests) ───────────────

    /** Where a run ended up: paused on a question, or finished with a final state. */
    public record RunResult(String question, JdState finalState) {
        public boolean paused() { return question != null; }
    }

    /** Starts a new run for {@code rawJd}. */
    public static RunResult start(CompiledGraph<JdState> graph, String rawJd, String threadId) throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        graph.invoke(GraphInput.args(Map.of("raw_jd", rawJd)), config);
        return inspect(graph, config);
    }

    /** Resumes a paused run with the recruiter's answer. */
    public static RunResult resume(CompiledGraph<JdState> graph, String threadId, String answer) throws Exception {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        RunnableConfig withAnswer = graph.updateState(config, Map.of("answer", answer));
        graph.invoke(GraphInput.resume(), withAnswer);
        return inspect(graph, config);
    }

    private static RunResult inspect(CompiledGraph<JdState> graph, RunnableConfig config) {
        StateSnapshot<JdState> snapshot = graph.getState(config);
        JdState state = snapshot.state();
        if (NODE_REQUEST_MISSING_INFO.equals(snapshot.nextNodeId())) {
            return new RunResult("What is the " + state.missingFields().get(0) + "?", state);
        }
        return new RunResult(null, state);
    }

    /** SQLite checkpoints keyed by thread id: this is what lets a paused run survive a restart. */
    public static SQLiteSaver checkpointer(String databasePath) throws Exception {
        return SQLiteSaver.builder()
                .databasePath(databasePath)
                .createTables(true)
                .stateSerializer(new ObjectStreamStateSerializer<>(JdState::new))
                .build();
    }

    // ─── Reply text for the chat UI ───────────────────────────────────────────

    public static String formatResult(JdState state) {
        StringBuilder sb = new StringBuilder("Status: " + state.status());
        for (String line : state.log()) {
            sb.append("\n- ").append(line);
        }
        if ("published".equals(state.status())) {
            sb.append("\nPublished job_id: ").append(state.jobId());
        }
        return sb.toString();
    }
}
