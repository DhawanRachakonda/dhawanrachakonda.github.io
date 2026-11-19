# One agent workflow, four implementations: LangGraph, LangChain4j, Spring AI and Embabel

**TL;DR**
- I took one small agent workflow that already existed in Python (LangGraph): a job-description intake pipeline that extracts fields, asks for what is missing, rejects duplicates, scores quality, rewrites, and publishes. I ported it to the JVM three ways: LangChain4j with LangGraph4j, Spring AI with LangGraph4j, and Embabel. The three Java ports are all Spring Boot applications, and their React chat UI sources are identical ([diff](posts/evidence/job-agent-frontend-compare.txt); the folders are [`frontend/`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/tree/c5dd553/frontend), [`frontend/`](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/tree/d2b0afe/frontend) and [`frontend/`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/tree/c96c51f/frontend)).
- All four versions have the same eight steps and the same three rules (score 75, two rewrites, six questions), and the diagram below describes all of them. The LangChain4j and Spring AI ports share the same graph code and differ only in the LLM layer. The Embabel port has no graph at all: the flow comes from method signatures and typed states, and "what is missing" is declared with Jakarta Bean Validation.
- The Java ports pass their offline test suites (8 LangChain4j tests, 8 Spring AI tests, 6 Embabel tests; see [the test list](posts/evidence/job-agent-tests.txt)). I did **not** run any of them against the real Claude API or a real MongoDB, so this is a build-and-test result, not a production result (see "Not proven").
- The point I most want Java teams to look at: in the Embabel port, a standard Jakarta Bean Validation annotation (`@NotBlank`) on a plain Java record, checked by the Spring `Validator` bean, decides which fields the agent asks the recruiter for, so ordinary Java conventions did the job in this demo. My opinion, which this small demo is consistent with but does not prove, is that much existing Java code can be reused for GenAI instead of rewritten in Python. The JVM also offers several routes (per their own docs, Quarkus and Micronaut integrate LangChain4j too; I did not build with them).

I had a Python pipeline in the [Langgraph-Job-Agent](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent) repository. I asked: can the same workflow be written on the JVM, and how different does each route feel? I had Claude Code write the Java ports from the Python code, and then I read, ran and tested them. The three ports are small Maven projects, public at [Langchain4j-Job-Agent](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent), [SpringAI-Job-Agent](https://github.com/DhawanRachakonda/SpringAI-Job-Agent) and [Embabel-Job-Agent](https://github.com/DhawanRachakonda/Embabel-Job-Agent). Every code link below points at a fixed commit, so the line numbers stay correct, and I also keep copies of the key files in this site's evidence folder.

> **Why this matters for Java teams (my view, not something I measured).** Many enterprise applications are written in Java, with domain classes, validation rules, Spring beans and database code that already work and are already trusted. The question this post tries to answer on a small example is how much of that carries over to a GenAI workflow instead of being rewritten in Python. The clearest example in this post is **Jakarta Bean Validation in section 5**, where the `@NotBlank` annotation, the kind often used to guard a REST request or a database entity, decided what the agent treats as a complete record. I did not collect a market-share figure, so I make no numeric claim about how many enterprises use Java.

## What this post explains, and why

- **What:** one workflow, four implementations: LangGraph (Python), LangChain4j with LangGraph4j (Java), Spring AI with LangGraph4j (Java), and Embabel (Java). Where the flow, the rules, the pause for a human and the saved state live in each, and what hosting them in Spring Boot adds.
- **Why:** I wanted to know whether Python is really the only comfortable place to build agent workflows, how much existing Java code and conventions (validation, Spring beans, domain classes) can be reused for GenAI, and how Embabel's idea (plan from types) compares with an explicit graph.
- **After reading you will understand:** what the workflow does and which steps need an LLM (section 1); how the explicit-graph style works in Python and in Java, and how little changes between the LangChain4j and Spring AI versions (sections 2 to 4); how Embabel expresses the same flow without a graph, and how standard Jakarta validation can drive what the agent asks for (section 5); what other JVM frameworks can host LangChain4j (section 6); how the four compare (sections 7 and 8); and where the comparison stops being reliable ("Not proven").
- **Not covered:** speed, cost or answer quality of the LLM calls, scaling, any real-world run, and building the same workflow in Quarkus or Micronaut. See "Not proven".

## 1. The workflow, in one picture

[![The JD intake workflow: parse_jd, request_missing_info, check_duplicate, score_quality, rewrite_jd, publish_job, reject, escalate and END](posts/images/job-intake-workflow.svg)](posts/images/job-intake-workflow.svg)

*Click the diagram to open it full size. Blue steps call the LLM, orange pauses for a person, green touches the database, red are the terminal outcomes.*

A recruiter pastes a messy job description. The workflow turns it into a published job, or stops with a clear reason. The diagram is the same for all four versions. These are the steps and the plain-code condition on each arrow (the conditions come from the router functions in the Python file: [`route_after_parse`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L124-L126), [`route_after_request_missing_info`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L149-L155), [`route_after_duplicate_check`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L168-L169) and [`route_after_score`](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L197-L203)):

| Step | What it does | Uses the LLM? | Where it goes next |
|---|---|---|---|
| `parse_jd` | Extracts title, company, location, experience and work mode | Yes | `request_missing_info` if a field is missing, else `check_duplicate` |
| `request_missing_info` | Pauses and asks the recruiter for **one** missing field | No (a person answers) | itself while fields remain, `escalate` after 6 questions, else `check_duplicate` |
| `check_duplicate` | Looks for the same title and company in MongoDB | No | `reject` if found, else `score_quality` |
| `score_quality` | Scores clarity and bias from 0 to 100 with a short critique | Yes | `publish_job` if the score is at least 75, `rewrite_jd` if below 75 and fewer than 2 rewrites so far, else `escalate` |
| `rewrite_jd` | Rewrites the text using the critique | Yes | back to `score_quality` |
| `publish_job` / `reject` / `escalate` | The three ways a run ends: published, rejected as a duplicate, or handed to a person | No | `END` |

Two things in this picture matter for the rest of the post.

**The LLM only fills in text.** Three nodes call the model (blue in the diagram). Every arrow is decided by plain code: "is the field list empty", "is the score at least 75". The model never decides where the run goes next. That is a deliberate design choice in the Python original (its router functions say "Plain Python, not an LLM decision"), and I kept it in every port.

**The graph has a loop and a pause.** `score_quality` and `rewrite_jd` loop until the score passes or the rewrite cap is reached. `request_missing_info` loops on itself and waits for a human each time. Those two things, a bounded loop and a pause that must survive a restart, are what make this more than a straight chain of prompts, and they are what I compared across the frameworks.

One small observation from the Python file: its graph declares an edge from `parse_jd` to `escalate` ([lines 271-275](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L271-L275)), but `route_after_parse` can only return `request_missing_info` or `check_duplicate` ([lines 124-126](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent/blob/8497a27/jd_intake_pipeline.py#L124-L126)), so that edge can never be taken (the numbered lines are saved in [python-parse-edge-lines.txt](posts/evidence/python-parse-edge-lines.txt)). The diagram leaves it out, and so do my LangGraph4j ports ([`buildGraph`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/main/java/jobagent/JdIntakePipeline.java#L200-L237)).

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

This also answers "how does it know which user is calling": only by `thread_id`. The chat page keeps it and sends it back with each message. There is no login in any of the versions.

The Python original is set up with Python 3.13, `langgraph` 1.2.22 and `langchain-anthropic` 1.7.11 ([versions](posts/evidence/job-agent-versions.txt)). The repository has no automated tests, so I could not compare test results across all four.

## 3. Java, option A: LangChain4j and LangGraph4j on Spring Boot

This port keeps the Python design and translates it. [LangGraph4j](https://github.com/langgraph4j/langgraph4j) describes itself as "A library for building stateful, multi-agents applications with LLMs", inspired by LangGraph, and built to work with LangChain4j and Spring AI (exact README lines in [jvm-framework-sources.txt](posts/evidence/jvm-framework-sources.txt)). [LangChain4j](https://docs.langchain4j.dev/) is the Java library for talking to LLMs through one API. In this project LangChain4j makes the Claude calls (the `langchain4j-anthropic` module, version 1.24.0) and LangGraph4j (1.9.9) is the workflow engine ([versions](posts/evidence/job-agent-versions.txt)).

| Python | Java |
|---|---|
| `JDState` (`TypedDict`) | `JdState`, a class extending LangGraph4j's `AgentState`, with a `log` field that appends |
| `add_node`, `add_conditional_edges` | the same names, one line per arrow |
| `interrupt()` | `interruptBefore("request_missing_info")`; the recruiter's reply is written into state, then the graph resumes |
| `SqliteSaver` plus `thread_id` | `SQLiteSaver` plus a thread ID in the run config |
| `ChatAnthropic` | LangChain4j's `AnthropicChatModel`, a `ChatModel` bean |
| `jobs_db.py` | a `JobStore` interface with a MongoDB class and an in-memory class for tests |
| Flask `app.py` | a Spring MVC `@RestController` |

The wiring reads almost the same as the Python ([`buildGraph`, lines 200-237](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/main/java/jobagent/JdIntakePipeline.java#L200-L237); [copy of the file](posts/evidence/langchain4j-JdIntakePipeline.java.txt)):

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

**Hosting in Spring Boot.** My first version of this port ran its own small HTTP server on the JDK's built-in `HttpServer`, a 120-line [`WebServer.java`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/a1005b3/src/main/java/jobagent/WebServer.java) at commit `a1005b3` ([line count](posts/evidence/job-agent-sizes.txt)). That was reinventing something the Java ecosystem already does well, and it made this port an uneven comparison with the Embabel one, which is a Spring Boot app. The current version is a Spring Boot 4.0.9 application. The model, the job store and the compiled graph are beans in [`JobAgentConfig`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/main/java/jobagent/JobAgentConfig.java#L19-L41), and `/chat` is a [`ChatController`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/main/java/jobagent/ChatController.java#L27-L58). The Claude client bean is built like this:

```java
return AnthropicChatModel.builder()
        .apiKey(apiKey)
        .modelName(model)
        .maxTokens(2048)
        .build();
```

The test for the HTTP contract now starts the real Spring context with the model faked ([`ChatControllerTest`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/test/java/jobagent/ChatControllerTest.java)). The LangGraph4j graph is a normal bean, so nothing about it is tied to Spring: the same graph code could be hosted elsewhere.

**What I had to add compared with Python.** The saver needed three settings that the Python `SqliteSaver` call did not make me think about: a state serializer and table creation (both in [`checkpointer`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/main/java/jobagent/JdIntakePipeline.java#L271-L277)), and `releaseThread(false)` ([line 235](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/c5dd553/src/main/java/jobagent/JdIntakePipeline.java#L235)), whose comment in the code above says why: to keep checkpoints after a run ends, as the Python saver does. I added them as I hit errors during the port, but I did not save those error logs, so the code is the record here.

## 4. Java, option B: Spring AI and LangGraph4j on Spring Boot

If your application is already a Spring one, Spring AI is the other obvious LLM layer. Its docs describe `ChatClient` as "a fluent API for communicating with an AI Model" and say that "The `entity()` method" maps the model's reply onto a Java class ([ChatClient docs](https://docs.spring.io/spring-ai/reference/api/chatclient.html); quotes in [jvm-framework-sources.txt](posts/evidence/jvm-framework-sources.txt)). I made this a separate repository, [SpringAI-Job-Agent](https://github.com/DhawanRachakonda/SpringAI-Job-Agent), by copying the LangChain4j port and replacing only the LLM layer. The graph, the state, the checkpoints and the controller are the same code. Spring AI 2.0.2 on Spring Boot 4.0.9 ([versions](posts/evidence/job-agent-versions.txt)).

What changed:

- **Client:** `JdIntakePipeline` takes a Spring AI `ChatClient` instead of a LangChain4j `ChatModel`. Spring Boot builds it from the `spring.ai.anthropic.*` properties in [`application.yml`](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/d2b0afe/src/main/resources/application.yml), and `JobAgentConfig` only calls `builder.build()` on the auto-configured `ChatClient.Builder` ([lines 22-26](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/d2b0afe/src/main/java/jobagent/JobAgentConfig.java#L22-L26)).
- **Structured output:** in the LangChain4j version I wrote the prompt as "reply with strict JSON" and parsed it with Jackson by hand. In this version the replies are read into two records, `ExtractedFields` and `QualityScore`, with Spring AI's `BeanOutputConverter`, which also supplies the format instructions appended to the prompt ([`parseJd`, lines 72-101](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/d2b0afe/src/main/java/jobagent/JdIntakePipeline.java#L72-L101)):

```java
String reply = llm.prompt().user(prompt + "\n\n" + EXTRACT.getFormat()).call().content();
...
f = EXTRACT.convert(reply);
```

- **Tests:** the fake model is a Spring AI `ChatModel` wrapped in a `ChatClient` ([test source](posts/evidence/springai-JdIntakePipelineTest.java.txt)). All other tests are unchanged apart from the JSON field name `workMode`.

I did not use LangGraph4j's own `langgraph4j-spring-ai` module. Its README says the Spring AI integration was proven with an `AgentExecutor` (a ReAct-style agent) built on LangGraph4j (line 417, [quoted](posts/evidence/jvm-framework-sources.txt)). This pipeline is a fixed workflow, not a tool-calling loop, so I used the plain graph and only Spring AI's chat client. That is my reasoning, and I did not try the module.

**What went wrong, and a regression test.** My first Spring AI version wrapped the model call in a `try/catch` that treated any exception as "nothing extracted". I only noticed when I started the app with a dummy API key: instead of an authentication error, `/chat` replied "What is the title?", which asked the recruiter to fill in every field because the 401 had been swallowed ([captured outputs](posts/evidence/job-agent-smoke-checks.txt)). The fix is the code above: call the model first, so an API failure propagates, and only treat a reply that cannot be read as empty. The test `anApiFailureIsNotMistakenForAnEmptyExtraction` pins that behaviour; I added the same test to the LangChain4j port, where the behaviour was already correct ([test list](posts/evidence/job-agent-tests.txt)).

## 5. Java, option C: Embabel

[Embabel](https://docs.embabel.com) is a JVM agent framework, also a Spring Boot application here. There is no graph definition in this port. The "wiring" is the signatures of the methods in one class ([`JdIntakeAgent`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java), [copy](posts/evidence/embabel-JdIntakeAgent.java.txt)), and the states are small immutable types ([`JdDomain`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdDomain.java#L100-L129), [copy](posts/evidence/embabel-JdDomain.java.txt)). The signatures below are on lines [62](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L62), [97](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L97), [108](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L108), [127](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L127), [136](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L136), [152](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L152), [166](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L166), [174](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L174) and [180](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L180):

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

Read it against the diagram. Each method takes the state it works on and returns the state it produces. Returning a different state type is a branch; returning the same type is a loop (`rewrite` returns `Scoring`, which `score` consumes again). The three methods that return a `JobOutcome` are the goals, the three ends of the run. The rules stay in ordinary Java inside the methods. For example, in [`score`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L136-L148) (lines 144-147):

```java
if (q.score() >= QUALITY_THRESHOLD) {
    return new Publishable(draft);
}
return draft.revisionCount() < MAX_REVISIONS ? new NeedsRewrite(draft) : new Escalated(draft);
```

**Reusing the Java you already have: Jakarta validation for the missing fields.** This is the part of the Embabel port I would point an enterprise Java team to first. Embabel works with standard Java APIs, and Jakarta Bean Validation is the clearest example. The guide says that the framework "supports Jakarta Bean Validation (JSR-380) for domain object constraints", which "are injected into the schema and validated during object construction" ([§4.38.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#relationship-with-other-validation-mechanisms)). In my port, "which fields are missing" is declared on a record with `@NotBlank`, not coded as a loop ([`RequiredFields`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdDomain.java#L42-L47)):

```java
public record RequiredFields(
        @NotBlank String title,
        @NotBlank String company,
        @NotBlank String location,
        @NotBlank String exp,
        @NotBlank String workMode) {}
```

`parse` validates that record with the Spring `Validator` bean and turns the violated properties into the list of fields to ask for ([`JdIntakeAgent`, lines 75-86](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L75-L86)). The constant list of field names is kept only to fix the order of the questions. Two honest limits on the reuse claim: I wrote this small record myself for the demo, and I did not point the agent at an existing annotated class from a real enterprise codebase. What the port shows is that the annotation vocabulary and the `Validator` bean a Spring application already has were enough, with no extra validation library or hand-written rule engine. The guide's wording suggests the same constraints also reach the model's output schema, so an already-annotated domain class could be a candidate output type, but I did not try that. I deliberately kept these constraints off `ExtractedFields`, the type the model fills in: since the guide says constraints are checked when an object is constructed, I expected a field that the job description simply does not mention to be treated as a bad model reply instead of a gap to ask the recruiter about. I did not test that expectation. The existing test where `parse` finds missing fields and the pipeline pauses to ask ([`missingFieldsPauseForTheRecruiterThenContinue`](posts/evidence/job-agent-tests.txt)) passes with the validator in place.

**Where "deterministic" comes from, stated carefully.** (The exact guide passages and their section anchors are saved in [embabel-guide-excerpts.txt](posts/evidence/embabel-guide-excerpts.txt).) Embabel's guide says its planning is "a true planning step, using a non-LLM AI algorithm" ([§1.3.1](https://docs.embabel.com/embabel-agent/guide/1.5.4/#sophisticated-planning)), and that for workflows with branching, loops or state, its planners and `@State` workflows "provide deterministic, typesafe planning that is far more powerful and predictable than LLM-driven orchestration" ([§4.9.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#reference.tools__agentic-tools); [guide 1.5.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/)). For states, it says that when an action returns a `@State` class the framework hides the previous state objects, binds the new one, and "Re-plans from the new state" ([§4.21.1](https://docs.embabel.com/embabel-agent/guide/1.5.4/#how-states-work-with-goap)). So in this port: the model is only called inside `parse`, `score` and `rewrite`; which action runs next is chosen by the planner from the types, and the type is chosen by my plain Java. I want to be fair about what this shows. The Python and LangGraph4j versions are just as deterministic here, because their routers are plain code too. The real difference is **where the flow is declared**: as explicit edges in Python and LangGraph4j, and inferred from types in Embabel. I only tested a pipeline where exactly one action applies at each step, so I have not tested how the planner behaves when several actions could apply.

**The pause needed a different shape.** I first expected to pause in the middle of a method. In Embabel the pause is a value: `ask` returns `WaitFor.formSubmission(...)`, the process enters a WAITING state, a form is generated from the answer type, and when the recruiter submits it the answer appears on the blackboard (Embabel's shared object store) for the next action ([§4.21.9](https://docs.embabel.com/embabel-agent/guide/1.5.4/#human-in-the-loop-with-waitfor) of the guide). So I split the step into [`ask`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L96-L100) (pause) and [`absorb`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/JdIntakeAgent.java#L107-L123) (apply the answer and decide what comes next). The state-changing actions use `clearBlackboard = true`, which the guide recommends for looping states ([§4.21.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/#looping-states-2)), so the consumed answer and the previous state cannot be picked up again (see the comment on `absorb`).

**Saving state across a restart.** Embabel can snapshot a waiting process to a database. I used the JDBC starter with a file-backed H2 database and the setting `embabel.agent.platform.persistence.checkpoint-policy=WAITING` ([`application.yml`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/resources/application.yml#L32-L35), [copy](posts/evidence/embabel-application.yml.txt); the starter and H2 dependencies are in the [`pom.xml`](posts/evidence/embabel-pom.xml.txt)) (the guide's default is `LIFECYCLE`, [§4.17.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/#configuration-2)). The guide lists limits ([§4.17.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#current-limitations)): the snapshot factory "supports InMemoryBlackboard and assumes ... exactly one pending awaitable per WAITING process", which matches this pipeline. I also hit one limit the guide does not list: on 1.5.4 the paused-form object could not be restored from its snapshot. With the workaround class removed (checked at the earlier commit `7a2a21b`, before the validation change), my restart test fails with `Cannot deserialize blackboard value of type [com.embabel.agent.core.hitl.FormBindingRequest]`, caused by a `KotlinInvalidNullException` for the `form` property ([log](posts/evidence/embabel-restore-without-serializer.txt)). I worked around it with a 57-line class, [`FormRequestSerializer`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/c96c51f/src/main/java/jobagent/FormRequestSerializer.java#L23-L57), that stores the question and the answer type and rebuilds the form. That is a workaround for the version I used ([copy](posts/evidence/embabel-FormRequestSerializer.java.txt); the class comment explains the mismatch); a newer version may not need it, which I did not check. I also did not check which maturity label (Stable, Incubating, Experimental; guide [§3.3](https://docs.embabel.com/embabel-agent/guide/1.5.4/#overview__modules)) applies to the persistence modules, so treat this part as something to re-verify.

**Platform requirement.** The port is set to Spring Boot 4.0.9 ([`pom.xml`](posts/evidence/embabel-pom.xml.txt)). As a check, I changed only the parent version to 3.5.7 in a scratch copy (again at `7a2a21b`): the Embabel 1.5.4 application context then failed to load with `ClassNotFoundException: org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration` ([log](posts/evidence/embabel-spring-boot-3-5-failure.txt)). I tried one 3.5.x version and did not dig into why, so I can only say that this combination did not work for me. The other two ports are on Spring Boot 4.0.9 as well, so I cannot say how they behave on 3.5.

## 6. Other JVM routes: Quarkus and Micronaut

Spring Boot is not the only host for LangChain4j. Quarkus has a LangChain4j extension, which its docs describe as one that "integrates Large Language Models (LLMs) into your Quarkus applications", with Anthropic (Claude) in its list of chat models ([Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)). Micronaut has a module that "provides integration between Micronaut and Langchain4j", also listing Anthropic as a chat model provider ([Micronaut LangChain4j](https://micronaut-projects.github.io/micronaut-langchain4j/latest/guide/)). Quotes are saved in [jvm-framework-sources.txt](posts/evidence/jvm-framework-sources.txt). I did **not** build this workflow in either of them, so this section is a pointer from their documentation, not a result. It does suggest that the choice of web framework and the choice of LLM library are separate decisions on the JVM, which is the part of the Python-versus-Java question I found most useful.

## 7. Side by side

| | Python, LangGraph | Java, LangChain4j + LangGraph4j | Java, Spring AI + LangGraph4j | Java, Embabel 1.5.4 |
|---|---|---|---|---|
| Web layer | Flask | Spring Boot (MVC) | Spring Boot (MVC) | Spring Boot (MVC) |
| LLM calls | `ChatAnthropic` | LangChain4j `ChatModel` | Spring AI `ChatClient` | Embabel `context.ai()` |
| Where the flow is declared | explicit nodes and edges | explicit nodes and edges | explicit nodes and edges | method signatures and state types |
| Where the rules (75, 2, 6) live | router functions | router methods | router methods | inside the action methods |
| Missing fields found by | a loop over field names | a loop over field names | a loop over field names | Jakarta `@NotBlank` constraints |
| Pause for a human | `interrupt()` inside a node | `interruptBefore` plus resume | `interruptBefore` plus resume | `WaitFor.formSubmission` as an action's result |
| State survives a restart | `SqliteSaver`, per `thread_id` | `SQLiteSaver`, per thread ID | `SQLiteSaver`, per thread ID | JDBC snapshot store (H2 file here), per process ID, plus one workaround |
| Main source size (lines) | 355 (one file) | 291 + 54 (pipeline and state) | 290 + 54 | 193 + 129 (agent and domain) |
| All main source files (lines) | 463 | 562 | 559 | 641 |
| Offline tests | none in the repository | 8 passed | 8 passed | 6 passed |

The line counts are `wc -l` and include comments and blank lines ([sizes](posts/evidence/job-agent-sizes.txt)). They are a rough indication of size, not of quality, and the whole-project row adds the web layer, the job store and, for Embabel, the service and serializer classes. The Python whole-project figure counts the pipeline, `app.py` and `jobs_db.py`. The test results are from the run in [job-agent-tests.txt](posts/evidence/job-agent-tests.txt): the LangChain4j and Spring AI tests use a scripted fake model, and the Embabel tests use Embabel's Mockito-based test support with the model mocked. All use an in-memory job store ([LangChain4j tests](posts/evidence/langchain4j-JdIntakePipelineTest.java.txt), [Spring AI tests](posts/evidence/springai-JdIntakePipelineTest.java.txt), [Embabel restart test](posts/evidence/embabel-PersistenceRestartTest.java.txt)), and each Java suite includes a test that a paused run can be resumed after a restart. The Embabel version of that test starts a fresh application context over the same database file, which is not the same as killing and restarting the process. Only the LangChain4j and Spring AI suites test escalation after too many questions, and they do it by giving the router a state that already has six attempts (see the comment in the [test](posts/evidence/langchain4j-JdIntakePipelineTest.java.txt)).

## 8. What I learned

- **An agent workflow is mostly ordinary control flow.** Once the rules were plain code, porting meant translating routers and state, and the LLM calls were the smallest part. That is why the language did not matter much (section 1, and the `score` excerpt in section 5).
- **Swapping the LLM layer is a small change, if the workflow does not depend on it.** The Spring AI port differs from the LangChain4j one only in the client, the structured-output parsing and the property names (section 4). That is the benefit of keeping the graph and the rules in plain code.
- **Do not reinvent the host.** My hand-written HTTP server worked, but it made the port less representative of how a Java developer would build this (section 3). Spring Boot gave the controller, configuration and a real application context for the tests.
- **A swallowed exception can look like a valid answer.** The first Spring AI version turned an authentication error into a question for the recruiter. A start-up check with a dummy key found it, and a test now guards it (section 4).
- **The explicit graph is easier to see; the typed version is easier to extend.** The diagram maps one to one onto the Python and LangGraph4j wiring. In Embabel there is no diagram in the code, and I drew it from the signatures. The guide argues the other side: adding actions and goals extends the system without editing state-machine definitions ([§1.3.2](https://docs.embabel.com/embabel-agent/guide/1.5.4/#superior-extensibility-and-reuse)). I did not test extending any of them, so this is an expectation, not a result.
- **Pause and resume is where frameworks differ most.** It took one checkpointer argument in Python, three extra settings in LangGraph4j, and a split action plus a serializer workaround in Embabel (sections 2, 3 and 5).
- **In this demo, existing Java conventions carried over.** Declaring what is missing with Jakarta validation, and wiring the rest with Spring beans, needed no new concepts beyond what a Spring developer already uses (section 5). Whether that holds for a real enterprise codebase is a hypothesis I think is plausible, not something the demo shows: I used my own small domain classes only (see "Not proven"). Its cost in my port was the Spring Boot 4 requirement and the persistence rough edge above.

## Not proven

- **No real run.** I never called the real Claude API or a real MongoDB from any Java port. The tests use a fake model and an in-memory store. The only live check was starting the LangChain4j and Spring AI apps with a dummy key and seeing Anthropic reject it ([captured outputs](posts/evidence/job-agent-smoke-checks.txt)), which shows the request reaches the API, not that the pipeline works with real replies. I did not run the Embabel app that way, and I did not run the Python original end to end for this post.
- **Real model replies for the structured parts.** The Spring AI port depends on the model returning JSON that `BeanOutputConverter` can read, and the Embabel port on Embabel's own extraction. I tested both only with scripted or mocked replies.
- **Reusing a real enterprise codebase.** I did not apply the agent to an existing, already-annotated domain model, so how well real legacy classes and rules carry over is untested.
- **Jakarta validation on the model-filled type.** I expected constraints on `ExtractedFields` to reject legitimately absent fields and kept them off it; I did not test that.
- **The six-question cap in a real run.** With five required fields asked one at a time, a normal run in this design cannot reach six questions, so the cap is a safety limit that I test only by handing the router a prepared state. I did not look for a real conversation that triggers it.
- **True process-kill resume.** The "resume after a restart" tests are close to, but not, a real kill of the operating-system process. The LangGraph4j tests reopen the same SQLite file; the Embabel test builds a second application context over the same database file.
- **Quarkus and Micronaut.** Section 6 only points at their documentation; I did not build with either.
- **How easy it is.** "Easy" here is one person's experience, helped by a coding agent, on one small workflow. I did not time the ports or compare them with a team's experience. The line counts say nothing about maintainability.
- **Planner behaviour with several applicable actions, scaling and cost.** Not tested.
- What would change my conclusion: a real run that fails on real model output, or a newer Embabel version that makes the serializer workaround unnecessary or the platform requirement lighter.
