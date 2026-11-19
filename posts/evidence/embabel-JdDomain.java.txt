package jobagent;

import com.embabel.agent.api.annotation.State;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Domain types for the JD intake workflow.
 *
 * In Embabel the types ARE the workflow: each action declares which type it consumes and
 * which it produces, and the planner chains them. The {@link Stage} records below are the
 * pipeline's states (the counterpart of LangGraph's nodes); an action that returns a
 * different stage type is a branch, one that returns the same type is a loop.
 */
public final class JdDomain {

    private JdDomain() {}

    public static final List<String> REQUIRED_FIELDS = List.of("title", "company", "location", "exp", "work_mode");
    public static final int QUALITY_THRESHOLD = 75;
    public static final int MAX_REVISIONS = 2;
    /** Fields are asked one at a time, so this is a total-question cap, not a round cap. */
    public static final int MAX_MISSING_INFO_ATTEMPTS = 6;

    /** Pipeline input: the recruiter's raw job description. */
    public record RawJd(String text) {}

    /** Structured LLM output for extraction. Empty string means "not mentioned". */
    public record ExtractedFields(String title, String company, String location, String exp, String workMode) {

        /** The same values in the record that carries the validation constraints. */
        public RequiredFields toRequiredFields() {
            return new RequiredFields(title, company, location, exp, workMode);
        }
    }

    /**
     * The five fields a job needs before it can be checked and published, as Jakarta Bean Validation
     * constraints. The agent validates this record to find what is missing. The constraints are
     * deliberately NOT on {@link ExtractedFields}, the type the model fills: the guide says Embabel
     * validates constraints when it constructs an object, and a field that the job description
     * simply does not mention is a gap to ask the recruiter about, not a malformed model reply.
     */
    public record RequiredFields(
            @NotBlank String title,
            @NotBlank String company,
            @NotBlank String location,
            @NotBlank String exp,
            @NotBlank String workMode) {

        /** The values by field name, in the order the recruiter is asked. */
        public Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("title", Objects.toString(title, ""));
            m.put("company", Objects.toString(company, ""));
            m.put("location", Objects.toString(location, ""));
            m.put("exp", Objects.toString(exp, ""));
            m.put("work_mode", Objects.toString(workMode, ""));
            return m;
        }

        /** The fields that fail their constraints (the missing ones), in the order the recruiter is asked. */
        public List<String> missing(Validator validator) {
            Set<String> violated = validator.validate(this).stream()
                    .map(v -> v.getPropertyPath().toString())
                    .collect(Collectors.toSet());
            return REQUIRED_FIELDS.stream()
                    .filter(f -> violated.contains("work_mode".equals(f) ? "workMode" : f))
                    .toList();
        }
    }

    /** Structured LLM output for scoring. */
    public record QualityScore(int score, String feedback) {}

    /** What the human types in answer to "What is the X?". */
    public record FieldAnswer(String value) {}

    /** Pipeline output (the goal): how the run ended. */
    public record JobOutcome(String status, String jobId, List<String> log) {}

    /**
     * Everything accumulated so far. Immutable: every step returns an updated copy.
     * Jackson-friendly (records of strings, ints, lists, maps) so it can be snapshotted.
     */
    public record Draft(
            String rawJd,
            Map<String, String> parsed,
            String rewrittenJd,
            int revisionCount,
            int qualityScore,
            String qualityFeedback,
            List<String> log) {

        public static Draft of(String rawJd) {
            return new Draft(rawJd, new LinkedHashMap<>(), "", 0, 0, "", List.of());
        }

        /** The draft being worked on: the rewrite if there is one, else the original. */
        public String currentText() {
            return rewrittenJd.isEmpty() ? rawJd : rewrittenJd;
        }

        public Draft withParsed(Map<String, String> p) {
            return new Draft(rawJd, new LinkedHashMap<>(p), rewrittenJd, revisionCount, qualityScore, qualityFeedback, log);
        }

        public Draft withScore(int score, String feedback) {
            return new Draft(rawJd, parsed, rewrittenJd, revisionCount, score, feedback, log);
        }

        public Draft withRewrite(String text) {
            return new Draft(rawJd, parsed, text, revisionCount + 1, qualityScore, qualityFeedback, log);
        }

        public Draft logged(String note) {
            List<String> next = new ArrayList<>(log);
            next.add(note);
            return new Draft(rawJd, parsed, rewrittenJd, revisionCount, qualityScore, qualityFeedback, next);
        }
    }

    /** Marker for the pipeline's states. */
    public interface Stage {}

    /** Required fields are still missing; ask the human for the first one. */
    @State
    public record Collecting(Draft draft, List<String> missing, int attempts) implements Stage {}

    /** All fields present; next the duplicate check. */
    @State
    public record Unchecked(Draft draft) implements Stage {}

    /** An existing job with the same title and company was found. */
    @State
    public record Duplicate(Draft draft, String existingId) implements Stage {}

    /** Unique; next the quality score. */
    @State
    public record Scoring(Draft draft) implements Stage {}

    /** Scored below the threshold with rewrites left. */
    @State
    public record NeedsRewrite(Draft draft) implements Stage {}

    /** Scored at or above the threshold. */
    @State
    public record Publishable(Draft draft) implements Stage {}

    /** Gave up: too many questions, or too many failed rewrites. A person must take over. */
    @State
    public record Escalated(Draft draft) implements Stage {}
}
