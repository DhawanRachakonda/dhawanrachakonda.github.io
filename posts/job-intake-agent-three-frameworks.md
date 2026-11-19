# One agent workflow, three frameworks: LangGraph in Python, LangChain4j and Embabel in Java

**TL;DR**
- I took one small agent workflow that already existed in Python (LangGraph): a job-description intake pipeline that extracts fields, asks for what is missing, rejects duplicates, scores quality, rewrites, and publishes. I ported it to Java twice: once with LangChain4j and LangGraph4j, once with Embabel.
- All three versions have the same eight steps and the same three rules (score 75, two rewrites, six questions), and the diagram below describes all of them. The LangGraph4j port copies the Python graph node for node. The Embabel port has no graph at all: the flow comes from method signatures and typed states.
- Both Java ports pass their offline test suites (run under JDK 26, compiled for Java 21; 7 LangChain4j tests and 6 Embabel tests; both cover publish, duplicate rejection, pausing for a missing field, the rewrite cap, the HTTP endpoint and resuming after a restart, and only the LangChain4j suite also tests escalation after too many questions; see [the test list](posts/evidence/job-agent-tests.txt)). I did **not** run either one against the real Claude API or a real MongoDB, so this is a build-and-test result, not a production result (see "Not proven").
- My takeaway: Java can express this kind of workflow as directly as Python can. Embabel is the interesting different design, because the planner is not an LLM and the route is worked out from types.

I had a Python pipeline in the [Langgraph-Job-Agent](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent) repository. I asked: can the same workflow be written on the JVM, and how different does it feel? I had Claude Code write the two Java ports from the Python code, and then I read, ran and tested them. Both are small Maven projects with a React chat UI on top, public at [Langchain4j-Job-Agent](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent) and [Embabel-Job-Agent](https://github.com/DhawanRachakonda/Embabel-Job-Agent). Every code link below points at a fixed commit, so the line numbers stay correct, and I also keep copies of the key files in this site's evidence folder.

## What this post explains, and why

- **What:** one workflow, three implementations: LangGraph (Python), LangChain4j with LangGraph4j (Java), and Embabel (Java). Where the flow, the rules, the pause for a human and the saved state live in each.
- **Why:** I wanted to know whether Python is really the only comfortable place to build agent workflows, and how Embabel's idea (plan from types) compares with an explicit graph.
- **After reading you will understand:** what the workflow does and which steps need an LLM (section 1); how the explicit-graph style works in Python and in Java (sections 2 and 3); how Embabel expresses the same flow without a graph, and what that costs (section 4); how the three compare (sections 5 and 6); and where the comparison stops being reliable ("Not proven").
- **Not covered:** speed, cost or answer quality of the LLM calls, scaling, and any real-world run. See "Not proven".

## 1. The workflow, in one picture

[![The JD intake workflow: parse_jd, request_missing_info, check_duplicate, score_quality, rewrite_jd, publish_job, reject, escalate and END](posts/images/job-intake-workflow.svg)](posts/images/job-intake-workflow.svg)

*Click the diagram to open it full size. Blue steps call the LLM, orange pauses for a person, green touches the database, red are the terminal outcomes.*

A recruiter pastes a messy job description. The workflow turns it into a published job, or stops with a clear reason. The diagram is the same for all three versions. These are the steps and the plain-code condition on each arrow (the conditions come from the router functions in the Python file: [`route_after_parse`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L124-L126), [`route_after_request_missing_info`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L149-L155), [`route_after_duplicate_check`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L168-L169) and [`route_after_score`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L197-L203)):

| Step | What it does | Uses the LLM? | Where it goes next |
|---|---|---|---|
| `parse_jd` | Extracts title, company, location, experience and work mode | Yes | `request_missing_info` if a field is missing, else `check_duplicate` |
| `request_missing_info` | Pauses and asks the recruiter for **one** missing field | No (a person answers) | itself while fields remain, `escalate` after 6 questions, else `check_duplicate` |
| `check_duplicate` | Looks for the same title and company in MongoDB | No | `reject` if found, else `score_quality` |
| `score_quality` | Scores clarity and bias from 0 to 100 with a short critique | Yes | `publish_job` if the score is at least 75, `rewrite_jd` if below 75 and fewer than 2 rewrites so far, else `escalate` |
| `rewrite_jd` | Rewrites the text using the critique | Yes | back to `score_quality` |
| `publish_job` / `reject` / `escalate` | The three ways a run ends: published, rejected as a duplicate, or handed to a person | No | `END` |

Two things in this picture matter for the rest of the post.

**The LLM only fills in text.** Three nodes call the model (blue in the diagram). Every arrow is decided by plain code: "is the field list empty", "is the score at least 75". The model never decides where the run goes next. That is a deliberate design choice in the Python original (its router functions say "Plain Python, not an LLM decision"), and I kept it in both ports.

**The graph has a loop and a pause.** `score_quality` and `rewrite_jd` loop until the score passes or the rewrite cap is reached. `request_missing_info` loops on itself and waits for a human each time. Those two things, a bounded loop and a pause that must survive a restart, are what make this more than a straight chain of prompts, and they are what I compared across the frameworks.

One small observation from the Python file: its graph declares an edge from `parse_jd` to `escalate` ([lines 271-275](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L271-L275)), but `route_after_parse` can only return `request_missing_info` or `check_duplicate` ([lines 124-126](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L124-L126)), so that edge can never be taken (the numbered lines are saved in [python-parse-edge-lines.txt](posts/evidence/python-parse-edge-lines.txt)). The diagram leaves it out, and so does my LangGraph4j port ([`buildGraph`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/a1005b3/src/main/java/jobagent/JdIntakePipeline.java#L205-L242)).

## 2. Python: LangGraph, the original

LangGraph describes a workflow as a graph of nodes and edges over a shared state. In this project the state is a `TypedDict`, each node is a function that returns the fields it changed, and the routers are plain functions that return the next node's name. The wiring is explicit, one line per arrow ([source](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L256-L293)):

```python
graph.add_conditional_edges("check_duplicate", route_after_duplicate_check, {
    "reject": "reject",
    "score_quality": "score_quality",
})
graph.add_conditional_edges("score_quality", route_after_score, {
    "publish_job": "publish_job",
    "rewrite_jd": "rewrite_jd",
    "escalate": "escalate",
})
graph.add_edge("rewrite_jd", "score_quality")
```

The router returns a string, and the dictionary says which node that string means. If the router returns a name that isn't in the dictionary, the run fails, so the allowed routes are all declared in one place.

**How saving and resuming works.** I compile the graph with a checkpointer (`SqliteSaver`) and pass a `thread_id` with every call. LangGraph's docs say it "creates a checkpoint at each super-step boundary" and that a checkpoint is a snapshot of the graph state ([checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers.md)); a thread is the ID that groups the checkpoints of one run. My code never calls "save". For the pause, a node calls `interrupt()`. Per the docs, that suspends the graph and saves its state, resuming needs the same thread ID, the value passed to `Command(resume=...)` becomes the return value of `interrupt`, and "the node restarts from the beginning of the node where the interrupt was called when resumed" ([interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts.md)). In this project that is `request_missing_info` ([lines 129-147](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L129-L147)).

This also answers "how does it know which user is calling": only by `thread_id`. The chat page keeps it and sends it back with each message. There is no login in any of the three versions.

The Python original is set up with Python 3.13, `langgraph` 1.2.22 and `langchain-anthropic` 1.7.11 ([versions](posts/evidence/job-agent-versions.txt)). The repository has no automated tests, so I could not compare test results across all three.

## 3. Java, option A: LangChain4j and LangGraph4j

This port keeps the Python design and translates it. [LangGraph4j](https://github.com/langgraph4j/langgraph4j) describes itself as "A library for building stateful, multi-agents applications with LLMs", inspired by LangGraph, and built to work with LangChain4j (exact README lines in [embabel-guide-excerpts.txt](posts/evidence/embabel-guide-excerpts.txt)). [LangChain4j](https://docs.langchain4j.dev/) is the Java library for talking to LLMs through one API. In this project LangChain4j makes the Claude calls (the `langchain4j-anthropic` module, version 1.24.0) and LangGraph4j (1.9.9) is the workflow engine ([versions](posts/evidence/job-agent-versions.txt)).

| Python | Java |
|---|---|
| `JDState` (`TypedDict`) | `JdState`, a class extending LangGraph4j's `AgentState`, with a `log` field that appends |
| `add_node`, `add_conditional_edges` | the same names, one line per arrow |
| `interrupt()` | `interruptBefore("request_missing_info")`; the recruiter's reply is written into state, then the graph resumes |
| `SqliteSaver` plus `thread_id` | `SQLiteSaver` plus a thread ID in the run config |
| `ChatAnthropic` | LangChain4j's Anthropic chat model |
| `jobs_db.py` | a `JobStore` interface with a MongoDB class and an in-memory class for tests |

The wiring reads almost the same as the Python ([`buildGraph`, lines 205-242](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/a1005b3/src/main/java/jobagent/JdIntakePipeline.java#L205-L242); [copy of the file](posts/evidence/langchain4j-JdIntakePipeline.java.txt)):

```java
graph.addConditionalEdges("check_duplicate", edge_async(this::routeAfterDuplicateCheck), Map.of(
        "reject", "reject",
        "score_quality", "score_quality"));
graph.addEdge("rewrite_jd", "score_quality");

return graph.compile(CompileConfig.builder()
        .checkpointSaver(checkpointer)
        .interruptBefore(NODE_REQUEST_MISSING_INFO)
        .releaseThread(false) // keep checkpoints after the run ends, like the Python SqliteSaver
        .build());
```

The one real design difference is the pause. Python pauses from inside a node. In LangGraph4j I pause before the node: the graph stops, the HTTP layer returns the question, and the next request writes the answer into the saved state and resumes. The effect for the user is the same.

**What I had to add compared with Python.** The saver needed three settings that the Python `SqliteSaver` call did not make me think about: a state serializer and table creation (both in [`checkpointer`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/a1005b3/src/main/java/jobagent/JdIntakePipeline.java#L287-L294)), and `releaseThread(false)` ([line 240](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/a1005b3/src/main/java/jobagent/JdIntakePipeline.java#L240)), whose comment in the code above says why: to keep checkpoints after a run ends, as the Python saver does. I added them as I hit errors during the port, but I did not save those error logs, so the code is the record here.

## 4. Java, option B: Embabel

[Embabel](https://docs.embabel.com) is a JVM agent framework. There is no graph definition in this port. The "wiring" is the signatures of the methods in one class ([`JdIntakeAgent`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java), [copy](posts/evidence/embabel-JdIntakeAgent.java.txt)), and the states are small immutable types ([`JdDomain`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdDomain.java#L85-L114), [copy](posts/evidence/embabel-JdDomain.java.txt)). The signatures below are on lines [56](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L56), [86](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L86), [97](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L97), [116](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L116), [125](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L125), [141](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L141), [155](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L155), [163](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L163) and [169](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L169):

```java
public Stage parse(RawJd raw, OperationContext context)          // Unchecked | Collecting
public FieldAnswer ask(Collecting state)                         // pauses for the recruiter
public Stage absorb(Collecting state, FieldAnswer answer)        // Collecting (loop) | Unchecked | Escalated
public Stage checkDuplicate(Unchecked state)                     // Duplicate | Scoring
public Stage score(Scoring state, OperationContext context)      // Publishable | NeedsRewrite | Escalated
public Scoring rewrite(NeedsRewrite state, OperationContext context)   // back to Scoring
public JobOutcome publish(Publishable state)                     // goal
public JobOutcome reject(Duplicate state)                        // goal
public JobOutcome escalate(Escalated state)                      // goal
```

Read it against the diagram. Each method takes the state it works on and returns the state it produces. Returning a different state type is a branch; returning the same type is a loop (`rewrite` returns `Scoring`, which `score` consumes again). The three methods that return a `JobOutcome` are the goals, the three ends of the run. The rules stay in ordinary Java inside the methods. For example, in [`score`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L125-L137) (lines 133-136):

```java
if (q.score() >= QUALITY_THRESHOLD) {
    return new Publishable(draft);
}
return draft.revisionCount() < MAX_REVISIONS ? new NeedsRewrite(draft) : new Escalated(draft);
```

**Where "deterministic" comes from, stated carefully.** (The exact guide passages and their section anchors are saved in [embabel-guide-excerpts.txt](posts/evidence/embabel-guide-excerpts.txt).) Embabel's guide says its planning is "a true planning step, using a non-LLM AI algorithm" ([§1.3.1](https://docs.embabel.com/embabel-agent/guide/1.5.4/#sophisticated-planning)), and that for workflows with branching, loops or state, its planners and `@State` workflows "provide deterministic, typesafe planning that is far more powerful and predictable than LLM-driven orchestration" ([§4.9.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#reference.tools__agentic-tools); [guide 1.5.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/)). For states, it says that when an action returns a `@State` class the framework hides the previous state objects, binds the new one, and "Re-plans from the new state" ([§4.21.1](https://docs.embabel.com/embabel-agent/guide/1.5.4/#how-states-work-with-goap)). So in this port: the model is only called inside `parse`, `score` and `rewrite`; which action runs next is chosen by the planner from the types, and the type is chosen by my plain Java. I want to be fair about what this shows. The Python and LangGraph4j versions are just as deterministic here, because their routers are plain code too. The real difference is **where the flow is declared**: as explicit edges in Python and LangGraph4j, and inferred from types in Embabel. I only tested a pipeline where exactly one action applies at each step, so I have not tested how the planner behaves when several actions could apply.

**The pause needed a different shape.** I first expected to pause in the middle of a method. In Embabel the pause is a value: `ask` returns `WaitFor.formSubmission(...)`, the process enters a WAITING state, a form is generated from the answer type, and when the recruiter submits it the answer appears on the blackboard (Embabel's shared object store) for the next action ([§4.21.9](https://docs.embabel.com/embabel-agent/guide/1.5.4/#human-in-the-loop-with-waitfor) of the guide). So I split the step into [`ask`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L85-L89) (pause) and [`absorb`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/JdIntakeAgent.java#L96-L112) (apply the answer and decide what comes next). The state-changing actions use `clearBlackboard = true`, which the guide recommends for looping states ([§4.21.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/#looping-states-2)), so the consumed answer and the previous state cannot be picked up again (see the comment on `absorb`).

**Saving state across a restart.** Embabel can snapshot a waiting process to a database. I used the JDBC starter with a file-backed H2 database and the setting `embabel.agent.platform.persistence.checkpoint-policy=WAITING` ([`application.yml`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/resources/application.yml#L32-L35), [copy](posts/evidence/embabel-application.yml.txt); the starter and H2 dependencies are in the [`pom.xml`](posts/evidence/embabel-pom.xml.txt)) (the guide's default is `LIFECYCLE`, [§4.17.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/#configuration-2)). The guide lists limits ([§4.17.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#current-limitations)): the snapshot factory "supports InMemoryBlackboard and assumes ... exactly one pending awaitable per WAITING process", which matches this pipeline. I also hit one limit the guide does not list: on 1.5.4 the paused-form object could not be restored from its snapshot. With the workaround class removed, my restart test fails with `Cannot deserialize blackboard value of type [com.embabel.agent.core.hitl.FormBindingRequest]`, caused by a `KotlinInvalidNullException` for the `form` property ([log](posts/evidence/embabel-restore-without-serializer.txt)). I worked around it with a 57-line class, [`FormRequestSerializer`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/7a2a21b/src/main/java/jobagent/FormRequestSerializer.java#L23-L57), that stores the question and the answer type and rebuilds the form. That is a workaround for the version I used ([copy](posts/evidence/embabel-FormRequestSerializer.java.txt); the class comment explains the mismatch); a newer version may not need it, which I did not check. I also did not check which maturity label (Stable, Incubating, Experimental; guide [§3.3](https://docs.embabel.com/embabel-agent/guide/1.5.4/#overview__modules)) applies to the persistence modules, so treat this part as something to re-verify.

**Platform requirement.** The port is set to Spring Boot 4.0.9 ([`pom.xml`](posts/evidence/embabel-pom.xml.txt)). As a check, I changed only the parent version to 3.5.7 in a scratch copy: the Embabel 1.5.4 application context then failed to load with `ClassNotFoundException: org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration` ([log](posts/evidence/embabel-spring-boot-3-5-failure.txt)). I tried one 3.5.x version and did not dig into why, so I can only say that this combination did not work for me. It is a bigger platform requirement than the LangGraph4j port, which is a plain Java program.

## 5. Side by side

| | Python, LangGraph | Java, LangChain4j + LangGraph4j | Java, Embabel 1.5.4 |
|---|---|---|---|
| Where the flow is declared | explicit nodes and edges | explicit nodes and edges | method signatures and state types |
| Where the rules (75, 2, 6) live | router functions | router methods | inside the action methods |
| Pause for a human | `interrupt()` inside a node | `interruptBefore` plus resume | `WaitFor.formSubmission` as an action's result |
| State survives a restart | `SqliteSaver`, per `thread_id` | `SQLiteSaver`, per thread ID | JDBC snapshot store (H2 file here), per process ID, plus one workaround |
| Main source size (lines) | 355 (one file) | 345 + 54 (pipeline and state) | 182 + 114 (agent and domain) |
| All main source files (lines) | 463 (pipeline, Flask app, Mongo helper) | 624 | 615 |
| Offline tests | none in the repository | 7 passed | 6 passed |

The line counts are `wc -l` and include comments, blank lines and, for the Python and LangGraph4j files, the command-line runner ([sizes](posts/evidence/job-agent-sizes.txt)). They are a rough indication of size, not of quality, and the whole-project row adds the web server, store and, for Embabel, service, controller and serializer classes. The test results are from the run in [job-agent-tests.txt](posts/evidence/job-agent-tests.txt): the LangChain4j tests use a scripted fake model, and the Embabel tests use Embabel's Mockito-based test support with the model mocked. Both use an in-memory job store ([LangChain4j tests](posts/evidence/langchain4j-JdIntakePipelineTest.java.txt), [Embabel restart test](posts/evidence/embabel-PersistenceRestartTest.java.txt)), and both include a test that a paused run can be resumed after a restart. The Embabel version of that test starts a fresh application context over the same database file, which is not the same as killing and restarting the process.

## 6. What I learned

- **An agent workflow is mostly ordinary control flow.** Once the rules were plain code, porting meant translating routers and state, and the LLM calls were the smallest part. That is why the language did not matter much (section 1, and the `score` excerpt in section 4).
- **The explicit graph is easier to see; the typed version is easier to extend.** The diagram maps one to one onto the Python and LangGraph4j wiring. In Embabel there is no diagram in the code, and I drew it from the signatures. The guide argues the other side: adding actions and goals extends the system without editing state-machine definitions ([§1.3.2](https://docs.embabel.com/embabel-agent/guide/1.5.4/#superior-extensibility-and-reuse)). I did not test extending any of them, so this is an expectation, not a result.
- **Pause and resume is where frameworks differ most.** It took one checkpointer argument in Python, three extra settings in LangGraph4j, and a split action plus a serializer workaround in Embabel (sections 2 to 4).
- **Embabel is the one to try if you want the planner, not an edge list, to own the route.** Its cost in my port was the Spring Boot 4 requirement and the persistence rough edge above.

## Not proven

- **No real run.** I never called the real Claude API or a real MongoDB from either Java port. The tests use a fake model and an in-memory store. I also did not run the Python original end to end for this post.
- **Embabel with real model output.** I do not know how the Embabel version copes with real extraction replies, only with mocked ones.
- **True process-kill resume.** Both "resume after a restart" tests are close to, but not, a real kill of the operating-system process. The LangGraph4j test reopens the same SQLite file; the Embabel test builds a second application context over the same database file.
- **How easy it is.** "Easy" here is one person's experience, helped by a coding agent, on one small workflow. I did not time the ports or compare them with a team's experience. The line counts say nothing about maintainability.
- **Planner behaviour with several applicable actions, scaling and cost.** Not tested.
- What would change my conclusion: a real run that fails on real model output, or a newer Embabel version that makes the serializer workaround unnecessary or the platform requirement lighter.
