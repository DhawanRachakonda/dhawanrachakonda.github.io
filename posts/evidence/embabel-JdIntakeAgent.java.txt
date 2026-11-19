package jobagent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.core.hitl.WaitFor;
import jobagent.JdDomain.Collecting;
import jobagent.JdDomain.Draft;
import jobagent.JdDomain.Duplicate;
import jobagent.JdDomain.Escalated;
import jobagent.JdDomain.ExtractedFields;
import jobagent.JdDomain.FieldAnswer;
import jobagent.JdDomain.JobOutcome;
import jobagent.JdDomain.NeedsRewrite;
import jobagent.JdDomain.Publishable;
import jobagent.JdDomain.QualityScore;
import jobagent.JdDomain.RawJd;
import jobagent.JdDomain.Scoring;
import jobagent.JdDomain.Stage;
import jobagent.JdDomain.Unchecked;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static jobagent.JdDomain.MAX_MISSING_INFO_ATTEMPTS;
import static jobagent.JdDomain.MAX_REVISIONS;
import static jobagent.JdDomain.QUALITY_THRESHOLD;
import static jobagent.JdDomain.REQUIRED_FIELDS;

/**
 * JD Intake as an Embabel agent.
 *
 * There is no graph definition here. The flow comes from the method signatures:
 * {@code parse(RawJd) -> Stage}, {@code askMissing(Collecting) -> Stage}, and so on.
 * Returning a different {@link Stage} type picks the next branch; returning the same type
 * loops. Rules (thresholds, caps) are plain Java in the actions, not LLM decisions.
 * Pausing for the recruiter is {@link WaitFor#formSubmission} (see {@code ask}/{@code absorb}).
 */
@Agent(description = "Turns a messy job description into a published job posting: extracts fields, "
        + "asks for missing ones, rejects duplicates, scores quality and rewrites if needed.")
public class JdIntakeAgent {

    private final JobStore store;

    public JdIntakeAgent(JobStore store) {
        this.store = store;
    }

    // ─── Stages ───────────────────────────────────────────────────────────────

    /** LLM extraction of the five required fields. */
    @Action(clearBlackboard = true)
    public Stage parse(RawJd raw, OperationContext context) {
        String prompt = "Extract these fields from the job description below, with an empty string for any "
                + "field that isn't clearly mentioned: title, company, location, exp, workMode.\n\n"
                + "Job description:\n" + raw.text();
        ExtractedFields f = context.ai().withDefaultLlm().createObject(prompt, ExtractedFields.class);

        Map<String, String> parsed = new LinkedHashMap<>();
        parsed.put("title", clean(f.title()));
        parsed.put("company", clean(f.company()));
        parsed.put("location", clean(f.location()));
        parsed.put("exp", clean(f.exp()));
        parsed.put("work_mode", clean(f.workMode()));

        List<String> missing = new ArrayList<>();
        for (String field : REQUIRED_FIELDS) {
            if (parsed.get(field).isEmpty()) {
                missing.add(field);
            }
        }
        Draft draft = Draft.of(raw.text()).withParsed(parsed)
                .logged("parse_jd: parsed=" + parsed + ", missing=" + missing);
        return missing.isEmpty() ? new Unchecked(draft) : new Collecting(draft, missing, 0);
    }

    /**
     * Human in the loop, step 1: pauses the process and asks for ONE field. The form-bound
     * {@link FieldAnswer} is this action's output; it appears on the blackboard when the
     * recruiter replies.
     */
    @Action(canRerun = true)
    public FieldAnswer ask(Collecting state) {
        String field = state.missing().get(0);
        return WaitFor.formSubmission("What is the " + field + "?", FieldAnswer.class);
    }

    /**
     * Human in the loop, step 2: applies the answer verbatim. Returning {@code Collecting}
     * again is the loop; the attempts cap is a plain rule here. The blackboard is cleared so
     * the consumed answer and the previous state can never be picked up again.
     */
    @Action(clearBlackboard = true)
    public Stage absorb(Collecting state, FieldAnswer answer) {
        String field = state.missing().get(0);
        Map<String, String> parsed = new LinkedHashMap<>(state.draft().parsed());
        parsed.put(field, clean(answer.value()));
        List<String> rest = new ArrayList<>(state.missing().subList(1, state.missing().size()));
        int attempts = state.attempts() + 1;
        Draft draft = state.draft().withParsed(parsed)
                .logged("request_missing_info: got '" + field + "' from the recruiter");

        if (rest.isEmpty()) {
            return new Unchecked(draft);
        }
        return attempts >= MAX_MISSING_INFO_ATTEMPTS
                ? new Escalated(draft)
                : new Collecting(draft, rest, attempts);
    }

    /** Plain database lookup, no LLM. A duplicate is a fact, not a judgment call. */
    @Action(clearBlackboard = true)
    public Stage checkDuplicate(Unchecked state) {
        Map<String, String> p = state.draft().parsed();
        var existing = store.findDuplicate(p.get("title"), p.get("company"));
        Draft draft = state.draft().logged("check_duplicate: duplicate_of=" + existing.orElse("None"));
        return existing.<Stage>map(id -> new Duplicate(draft, id)).orElseGet(() -> new Scoring(draft));
    }

    /** LLM scores the current draft; the threshold and revision cap are fixed rules. */
    @Action(canRerun = true, clearBlackboard = true)
    public Stage score(Scoring state, OperationContext context) {
        String prompt = "Score this job description from 0-100 on clarity, specificity, and bias-free "
                + "language, with a 1-2 sentence critique as feedback.\n\n"
                + "Job description:\n" + state.draft().currentText();
        QualityScore q = context.ai().withDefaultLlm().createObject(prompt, QualityScore.class);

        Draft draft = state.draft().withScore(q.score(), q.feedback())
                .logged("score_quality: " + q.score() + "/100 - " + q.feedback());
        if (q.score() >= QUALITY_THRESHOLD) {
            return new Publishable(draft);
        }
        return draft.revisionCount() < MAX_REVISIONS ? new NeedsRewrite(draft) : new Escalated(draft);
    }

    /** LLM rewrites the draft using the feedback, then it is scored again. */
    @Action(canRerun = true, clearBlackboard = true)
    public Scoring rewrite(NeedsRewrite state, OperationContext context) {
        String prompt = "Rewrite this job description to address the following feedback: \""
                + state.draft().qualityFeedback() + "\". Reply with only the rewritten job description.\n\n"
                + "Job description:\n" + state.draft().currentText();
        String rewritten = context.ai().withDefaultLlm().generateText(prompt).strip();
        Draft draft = state.draft().withRewrite(rewritten)
                .logged("rewrite_jd: revision #" + (state.draft().revisionCount() + 1));
        return new Scoring(draft);
    }

    // ─── Goals (three ways a run can end) ─────────────────────────────────────

    @AchievesGoal(description = "The job description was scored well and published")
    @Action
    public JobOutcome publish(Publishable state) {
        Draft d = state.draft();
        String jobId = store.publish(d.parsed(), d.currentText(), d.qualityScore());
        return outcome("published", jobId, d.logged("publish_job: job_id=" + jobId));
    }

    @AchievesGoal(description = "The job description duplicated an existing posting and was rejected")
    @Action
    public JobOutcome reject(Duplicate state) {
        return outcome("rejected", "", state.draft().logged("reject: duplicate of an existing job posting"));
    }

    @AchievesGoal(description = "The job description needs a person: too many questions or failed rewrites")
    @Action
    public JobOutcome escalate(Escalated state) {
        return outcome("needs_input", "", state.draft().logged("escalate: needs manual review"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static JobOutcome outcome(String status, String jobId, Draft draft) {
        return new JobOutcome(status, jobId, draft.log());
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
