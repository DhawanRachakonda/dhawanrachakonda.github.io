# One agent workflow, four implementations: LangGraph, LangGraph4j (with LangChain4j or Spring AI) and Embabel

**TL;DR**
- I took one small agent workflow that already existed in Python (LangGraph): a job-description intake pipeline that extracts fields, asks for what is missing, rejects duplicates, scores quality, rewrites, and publishes. I ported it to the JVM three ways: LangChain4j with LangGraph4j, Spring AI with LangGraph4j, and Embabel. The three Java ports are Spring Boot applications.
- All four versions have the same eight steps and the same three rules (score 75, two rewrites, six questions), and the diagram below describes all of them. The LangChain4j and Spring AI ports use the same LangGraph4j graph (the graph assembly is identical in a [diff](posts/evidence/job-agent-langchain4j-vs-springai-diff.txt)) and differ in the LLM client. The Embabel port has no graph at all: the flow comes from method signatures and typed states, the output type (not the prompt) says what to extract, and "what is missing" is declared with Jakarta Bean Validation.
- The Java ports pass their offline tests (8, 8 and 6; see [the test list](posts/evidence/job-agent-tests.txt)), and each app also ran end to end with live Claude and MongoDB Atlas, including a real kill and restart of the process while a run was paused (section 7). These are small runs, so this is not a production result (see "Not proven").
- The point I most want Java teams to look at: in the Embabel port, a standard Jakarta Bean Validation annotation (`@NotBlank`) on a plain Java record, checked by the Spring `Validator` bean, decides which fields the agent asks the recruiter for, so ordinary Java conventions did the job in this demo. My opinion, which this small demo is consistent with but does not prove, is that much existing Java code can be reused for GenAI instead of rewritten in Python. The JVM also offers several routes (per their own docs, Quarkus and Micronaut integrate LangChain4j too; I did not build with them).

I had a Python pipeline in the [Langgraph-Job-Agent](https://github.com/alumnx-ai-labs/Langgraph-Job-Agent) repository. I asked: can the same workflow be written on the JVM, and how different does each route feel? I had Claude Code write the Java ports from the Python code, and then I read, ran and tested them. The three ports are small Maven projects, public at [Langchain4j-Job-Agent](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent), [SpringAI-Job-Agent](https://github.com/DhawanRachakonda/SpringAI-Job-Agent) and [Embabel-Job-Agent](https://github.com/DhawanRachakonda/Embabel-Job-Agent). Code links point at fixed commits.

> **Why this matters for Java teams (my view, not something I measured).** In my experience, many enterprise applications are written in Java, with domain classes, validation rules, Spring beans and database code that already work and are trusted by the teams that run them. The question this post tries to answer on a small example is how much of that carries over to a GenAI workflow instead of being rewritten in Python. The clearest example in this post is **Jakarta Bean Validation in section 5**, where the `@NotBlank` annotation, the kind often used to guard a REST request or a database entity, decided what the agent treats as a complete record. I did not collect a market-share figure, so I make no numeric claim about how many enterprises use Java.

## What this post explains, and why

- **What:** one workflow, four implementations: LangGraph (Python), LangChain4j with LangGraph4j (Java), Spring AI with LangGraph4j (Java), and Embabel (Java). Where the flow, the rules, the pause for a human and the saved state live in each.
- **Why:** I wanted to know whether Python is really the only comfortable place to build agent workflows, how much existing Java code and conventions (validation, Spring beans, domain classes) can be reused for GenAI, and how Embabel's idea (plan from types) compares with an explicit graph.
- **After reading you will understand:** what the workflow does and which steps need an LLM (section 1); how the explicit-graph style works in Python and in Java, and how little changes between the LangChain4j and Spring AI versions (sections 2 to 4); how Embabel expresses the same flow without a graph, and how standard Jakarta validation can drive what the agent asks for (section 5); what other JVM frameworks can host LangChain4j (section 6); how the four compare (sections 7 and 8); and where the comparison stops being reliable ("Not proven").
- **Not covered:** speed, cost or answer quality of the LLM calls, scaling, load or production use, LangChain4j's own experimental agentic module, and building the same workflow in Quarkus or Micronaut. See "Not proven".

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

**How the comparison is built, and what it is not.** I wanted like-for-like pairs, so the pairs are explicit:

- **Python LangGraph and LangGraph4j** are the same engine family: [LangGraph4j](https://github.com/langgraph4j/langgraph4j) describes itself as inspired by LangGraph. These two are the like-for-like comparison of the workflow engine.
- **LangChain4j and Spring AI** are then two LLM clients under the *same* LangGraph4j graph, so swapping them is like for like. The "LangChain4j port" in this post is therefore really LangGraph4j plus LangChain4j, and the "Spring AI port" is LangGraph4j plus Spring AI. LangGraph4j documents both integrations.
- **Embabel** is a different design: its own planner, pause and persistence, and its own LLM access. It is compared as a whole.

What I did not compare: LangChain4j's own agentic module (`langchain4j-agentic`), which has sequential, loop, conditional and parallel workflows, a human-in-the-loop step and a scope store for recovery, but which its docs call "experimental" ([sources](posts/evidence/jvm-framework-sources.txt)). A pure-LangChain4j port on that module would be a fifth version. Spring AI's workflow-patterns page describes plain Java classes and does not mention a workflow engine, human-in-the-loop or persistence ([same sources](posts/evidence/jvm-framework-sources.txt)), which is why I paired it with LangGraph4j.

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

The wiring reads almost the same as the Python ([`buildGraph`, lines 200-237](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/847e196/src/main/java/jobagent/JdIntakePipeline.java#L200-L237); [copy of the file](posts/evidence/langchain4j-JdIntakePipeline.java.txt)):

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

**Hosting in Spring Boot.** The port is a Spring Boot 4.0.9 application. The model, the job store and the compiled graph are beans in [`JobAgentConfig`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/847e196/src/main/java/jobagent/JobAgentConfig.java#L19-L43), and `/chat` is a [`ChatController`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/847e196/src/main/java/jobagent/ChatController.java#L27-L58). The Claude client bean is built like this:

```java
return AnthropicChatModel.builder()
        .apiKey(apiKey)
        .modelName(model)
        .maxTokens(2048)
        .build();
```

The LangGraph4j graph is a normal bean, so nothing about it is tied to Spring: the same graph code could be hosted elsewhere.

**What LangGraph4j needs that Python does not.** The saver needs three settings that the Python `SqliteSaver` call does not: a state serializer and table creation (both in [`checkpointer`](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/847e196/src/main/java/jobagent/JdIntakePipeline.java#L271-L277)), and `releaseThread(false)` ([line 235](https://github.com/DhawanRachakonda/Langchain4j-Job-Agent/blob/847e196/src/main/java/jobagent/JdIntakePipeline.java#L235)), whose comment in the code above says why: to keep checkpoints after a run ends, as the Python saver does.

## 4. Java, option B: Spring AI and LangGraph4j on Spring Boot

If your application is already a Spring one, Spring AI is the other obvious LLM layer. Its docs describe `ChatClient` as "a fluent API for communicating with an AI Model" and say that "The `entity()` method" maps the model's reply onto a Java class ([ChatClient docs](https://docs.spring.io/spring-ai/reference/api/chatclient.html); quotes in [jvm-framework-sources.txt](posts/evidence/jvm-framework-sources.txt)). This port is the repository [SpringAI-Job-Agent](https://github.com/DhawanRachakonda/SpringAI-Job-Agent): the same graph as the LangChain4j one with a different LLM client. A [diff of the two](posts/evidence/job-agent-langchain4j-vs-springai-diff.txt) (at `847e196` and `0e09b4f`) shows it: the controller, the state, the job store and the application class are identical; in `JdIntakePipeline` everything from `buildGraph` to the end of the file (graph assembly, run loop, checkpointer) is identical, and so are nine of the twelve nodes and routers. What differs is the dependencies and configuration, the three nodes that call the model, and the test fakes. Spring AI 2.0.2 on Spring Boot 4.0.9 ([versions](posts/evidence/job-agent-versions.txt)).

What changed:

- **Client:** `JdIntakePipeline` takes a Spring AI `ChatClient` instead of a LangChain4j `ChatModel`. Spring Boot builds it from the `spring.ai.anthropic.*` properties in [`application.yml`](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/0e09b4f/src/main/resources/application.yml), and `JobAgentConfig` only calls `builder.build()` on the auto-configured `ChatClient.Builder` ([lines 22-26](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/0e09b4f/src/main/java/jobagent/JobAgentConfig.java#L22-L26)).
- **Structured output:** in the LangChain4j version I wrote the prompt as "reply with strict JSON" and parsed it with Jackson by hand. In this version Spring AI reads the reply into the record itself: `entity(ExtractedFields.class)` and `entity(QualityScore.class)`, one call each ([`parseJd`](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/0e09b4f/src/main/java/jobagent/JdIntakePipeline.java#L80-L98), [`scoreQuality`](https://github.com/DhawanRachakonda/SpringAI-Job-Agent/blob/0e09b4f/src/main/java/jobagent/JdIntakePipeline.java#L139-L148)). There is no parsing code of mine, and the extraction prompt does not name the fields:

```java
ExtractedFields f = llm.prompt().user(prompt).call().entity(ExtractedFields.class);
```

I did not inspect what `entity()` adds to the request; in the real run (section 7) it extracted all five fields from a prompt that names none of them.


LangGraph4j also ships Spring AI integration modules (`langgraph4j-spring-ai` and `langgraph4j-springai-agentexecutor`). The [agent executor README](https://github.com/langgraph4j/langgraph4j/blob/main/spring-ai/spring-ai-agent/README.md) describes "ReAct-style agents built on LangGraph4j and Spring AI `ChatModel`" for when you "want a ready-to-run agent loop with tool callbacks, streaming output, approvals, or sub-agents instead of wiring every node manually" (quote saved in [jvm-framework-sources.txt](posts/evidence/jvm-framework-sources.txt)). That is the module to look at for a tool-calling agent. This pipeline is a fixed workflow with plain-code routing and a human pause, so I wired the `StateGraph` myself and used only Spring AI's `ChatClient`. I did not try the agent-executor module on this workflow, so I cannot say how well the workflow would fit it.

## 5. Java, option C: Embabel

[Embabel](https://docs.embabel.com) is a JVM agent framework, also a Spring Boot application here. There is no graph definition in this port. The "wiring" is the signatures of the methods in one class ([`JdIntakeAgent`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java), [copy](posts/evidence/embabel-JdIntakeAgent.java.txt)), and the states are small immutable types ([`JdDomain`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdDomain.java#L132-L161), [copy](posts/evidence/embabel-JdDomain.java.txt)). The signatures below are on lines [60](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L60), [82](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L82), [93](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L93), [112](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L112), [121](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L121), [137](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L137), [151](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L151), [159](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L159) and [165](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L165):

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

Read it against the diagram. Each method takes the state it works on and returns the state it produces. Returning a different state type is a branch; returning the same type is a loop (`rewrite` returns `Scoring`, which `score` consumes again). The three methods that return a `JobOutcome` are the goals, the three ends of the run. The rules stay in ordinary Java inside the methods. For example, in [`score`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L121-L133) (lines 129-132):

```java
if (q.score() >= QUALITY_THRESHOLD) {
    return new Publishable(draft);
}
return draft.revisionCount() < MAX_REVISIONS ? new NeedsRewrite(draft) : new Escalated(draft);
```

**The output type says what to extract.** In Python and LangChain4j the extraction prompt has to list the fields and the code parses the JSON by hand. Spring AI's `entity()` and Embabel's `createObject` both take the type instead, and in both the prompt does not name the fields. In Embabel that is `createObject(prompt, ExtractedFields.class)`. The guide says that Embabel generates the JSON schema from the requested type and parses the model's reply into that type ([§4.25.6](https://docs.embabel.com/embabel-agent/guide/1.5.4/#reference.llms.native-structured-output)), and its own examples describe the task in the prompt without listing fields. I did not inspect the exact request sent to Claude, and the same section says Anthropic's provider-native structured output is disabled by default, so how the schema reaches Claude is something I did not check. The prompt only says what to do and how to treat absent values ([`parse`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L59-L74)):

```java
RequiredFields fields = context.ai().withDefaultLlm()
        .createObject(prompt, ExtractedFields.class)
        .toRequiredFields();
```

`ExtractedFields` has a small method that returns the record carrying the constraints ([`toRequiredFields`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdDomain.java#L36-L43)), so `parse` has no hand-built map.

**Reusing the Java you already have: Jakarta validation for the missing fields.** This is the part of the Embabel port I would point an enterprise Java team to first. Embabel works with standard Java APIs, and Jakarta Bean Validation is the clearest example. The guide says the framework supports Jakarta Bean Validation (JSR-380) for domain object constraints, injects those constraints into the schema and validates them when an object is constructed ([§4.38.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#relationship-with-other-validation-mechanisms); the passage is saved in [embabel-guide-excerpts.txt](posts/evidence/embabel-guide-excerpts.txt)). In my port, "which fields are missing" is declared on a record with `@NotBlank`, not coded as a loop ([`RequiredFields`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdDomain.java#L52-L79)):

```java
public record RequiredFields(
        @NotBlank String title,
        @NotBlank String company,
        @NotBlank String location,
        @NotBlank String exp,
        @NotBlank String workMode) {
```

The same record has two small methods, `asMap()` (the values by field name, for the draft) and `missing(validator)`, which validates the record with the Spring `Validator` bean and turns the violated properties into the list of fields to ask for, in question order ([`RequiredFields`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdDomain.java#L52-L79)). `parse` just calls `fields.missing(validator)` ([lines 65-73](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L65-L73)). The constant list of field names is kept only to fix the order of the questions. Two honest limits on the reuse claim: I wrote this small record myself for the demo, and I did not point the agent at an existing annotated class from a real enterprise codebase. What the port shows is that the annotation vocabulary and the `Validator` bean a Spring application already has were enough, with no extra validation library or hand-written rule engine. The guide's wording suggests the same constraints also reach the model's output schema, so an already-annotated domain class could be a candidate output type, but I did not try that. I kept these constraints off `ExtractedFields`, the type the model fills in: the guide says constraints are checked when an object is constructed, so I expected a field that the job description simply does not mention to be treated as a bad model reply instead of a gap to ask the recruiter about (I did not test that expectation).

**Where "deterministic" comes from, stated carefully.** (The exact guide passages and their section anchors are saved in [embabel-guide-excerpts.txt](posts/evidence/embabel-guide-excerpts.txt).) Embabel's guide says its planning is "a true planning step, using a non-LLM AI algorithm" ([§1.3.1](https://docs.embabel.com/embabel-agent/guide/1.5.4/#sophisticated-planning)), and that for workflows with branching, loops or state, its planners and `@State` workflows "provide deterministic, typesafe planning that is far more powerful and predictable than LLM-driven orchestration" ([§4.9.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#reference.tools__agentic-tools); [guide 1.5.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/)). For states, it says that when an action returns a `@State` class the framework hides the previous state objects, binds the new one, and re-plans from the new state ([§4.21.1](https://docs.embabel.com/embabel-agent/guide/1.5.4/#how-states-work-with-goap)). So in this port: the model is only called inside `parse`, `score` and `rewrite`; which action runs next is chosen by the planner from the types, and the type is chosen by my plain Java. I want to be fair about what this shows. The Python and LangGraph4j versions are just as deterministic here, because their routers are plain code too. The real difference is **where the flow is declared**: as explicit edges in Python and LangGraph4j, and inferred from types in Embabel. I only tested a pipeline where exactly one action applies at each step, so I have not tested how the planner behaves when several actions could apply.

**The pause is a value.** In Embabel the pause is a value: `ask` returns `WaitFor.formSubmission(...)`, the process enters a WAITING state, a form is generated from the answer type, and when the recruiter submits it the answer appears on the blackboard (Embabel's shared object store) for the next action ([§4.21.9](https://docs.embabel.com/embabel-agent/guide/1.5.4/#human-in-the-loop-with-waitfor) of the guide). So I split the step into [`ask`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L81-L85) (pause) and [`absorb`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/JdIntakeAgent.java#L92-L108) (apply the answer and decide what comes next). The state-changing actions use `clearBlackboard = true`, which the guide recommends for looping states ([§4.21.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/#looping-states-2)), so the consumed answer and the previous state cannot be picked up again (see the comment on `absorb`).

**Saving state across a restart.** Embabel can snapshot a waiting process to a database. I used the JDBC starter with a file-backed H2 database and the setting `embabel.agent.platform.persistence.checkpoint-policy=WAITING` ([`application.yml`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/resources/application.yml#L32-L35), [copy](posts/evidence/embabel-application.yml.txt); the starter and H2 dependencies are in the [`pom.xml`](posts/evidence/embabel-pom.xml.txt)) (the guide's default is `LIFECYCLE`, [§4.17.4](https://docs.embabel.com/embabel-agent/guide/1.5.4/#configuration-2)). The guide lists limits ([§4.17.7](https://docs.embabel.com/embabel-agent/guide/1.5.4/#current-limitations)): the snapshot factory supports only the in-memory blackboard and assumes exactly one pending awaitable per waiting process, which matches this pipeline. I also hit one limit the guide does not list: on 1.5.4 the paused-form object could not be restored from its snapshot. With the workaround class removed (checked on commit `7a2a21b`), my restart test fails with `Cannot deserialize blackboard value of type [com.embabel.agent.core.hitl.FormBindingRequest]`, caused by a `KotlinInvalidNullException` for the `form` property ([log](posts/evidence/embabel-restore-without-serializer.txt)). I worked around it with a 57-line class, [`FormRequestSerializer`](https://github.com/DhawanRachakonda/Embabel-Job-Agent/blob/f3b79e5/src/main/java/jobagent/FormRequestSerializer.java#L23-L57), that stores the question and the answer type and rebuilds the form. That is a workaround for the version I used ([copy](posts/evidence/embabel-FormRequestSerializer.java.txt); the class comment explains the mismatch); a newer version may not need it, which I did not check. I also did not check which maturity label (Stable, Incubating, Experimental; guide [§3.3](https://docs.embabel.com/embabel-agent/guide/1.5.4/#overview__modules)) applies to the persistence modules, so treat this part as something to re-verify.

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
| Who tells the model the output shape | the prompt lists the keys; code parses the JSON | the prompt lists the keys; code parses the JSON | the record passed to `entity()` | the output type (`createObject` with `ExtractedFields`) |
| Missing fields found by | a loop over field names | a loop over field names | a loop over field names | Jakarta `@NotBlank` constraints |
| Pause for a human | `interrupt()` inside a node | `interruptBefore` plus resume | `interruptBefore` plus resume | `WaitFor.formSubmission` as an action's result |
| State survives a restart | `SqliteSaver`, per `thread_id` | `SQLiteSaver`, per thread ID | `SQLiteSaver`, per thread ID | JDBC snapshot store (H2 file here), per process ID, plus one workaround |
| Main source size (lines) | 355 (one file) | 291 + 54 (pipeline and state) | 274 + 54 | 174 + 161 (agent and domain) |
| All main source files (lines) | 463 | 566 | 547 | 660 |
| Offline tests | none in the repository | 8 passed | 8 passed | 6 passed |

Every figure in this table, the code links and the test counts come from the same commits: LangChain4j `847e196`, Spring AI `0e09b4f` and Embabel `f3b79e5` (the Python figures are from `8497a27`). Line counts are `wc -l` and include comments and blank lines ([sizes](posts/evidence/job-agent-sizes.txt)); they are a rough indication of size, not of quality. The test counts are from [job-agent-tests.txt](posts/evidence/job-agent-tests.txt): the model is faked in all of them and the job store is in memory. The automated resume-after-restart tests reopen the same checkpoint store (a SQLite file or an H2 file) in a new object or application context, which is not the same as killing the process; the real runs below do that.

### Real runs, with live Claude and MongoDB Atlas

After the offline tests, I ran each Java app for real, from the same main code as the commits above: all three on the same Claude model (`claude-sonnet-5-5`, the default in each app's `application.yml`; I did not set `ANTHROPIC_MODEL`) and one shared MongoDB Atlas cluster, sending requests to `/chat` ([record](posts/evidence/job-agent-real-runs.txt); keys and connection strings are in git-ignored `.env` files and appear nowhere in the evidence). Each app stores its jobs in its own collection, so apps sharing one cluster do not see each other's jobs in the duplicate check.

| Check | Embabel | LangGraph4j + LangChain4j | LangGraph4j + Spring AI |
|---|---|---|---|
| One complete job description | published; scores 72, 72, then 94 after two rewrites | published; scores 68, then 88 after one rewrite | published; scores 72, then 91 after one rewrite |
| The same description again | rejected as a duplicate | rejected as a duplicate | rejected as a duplicate |
| A description missing location, experience and work mode | asked the three questions one at a time, then published | same | same |
| Kill the process while paused on a question, restart, answer on the old thread | resumed and completed | resumed and completed | resumed and completed |

The input was the same job description for all three apps, apart from a company-name suffix ([the exact texts](posts/evidence/job-agent-real-runs.txt)). Each cell is one run of a non-deterministic model, so read the scores as an illustration, not a ranking.

The process kill was a real one: I stopped the app's operating-system process while a run was waiting for an answer, started it again, and answered on the same `thread_id`. This is one run of each check per app, so it shows that the flows work, not how reliably.

## 8. What I learned

- **An agent workflow is mostly ordinary control flow.** Once the rules were plain code, porting meant translating routers and state, and the LLM calls were the smallest part. That is why the language did not matter much (section 1, and the `score` excerpt in section 5).
- **Swapping the LLM layer is a small change, if the workflow does not depend on it.** The Spring AI port differs from the LangChain4j one in the client, the structured-output parsing, the configuration and the fake model in the tests, and not in the graph (the [diff](posts/evidence/job-agent-langchain4j-vs-springai-diff.txt), section 4). That is the benefit of keeping the graph and the rules in plain code.
- **The explicit graph is easier to see; the typed version is easier to extend.** The diagram maps one to one onto the Python and LangGraph4j wiring. In Embabel there is no diagram in the code, and I drew it from the signatures. The guide argues the other side: adding actions and goals extends the system without editing state-machine definitions ([§1.3.2](https://docs.embabel.com/embabel-agent/guide/1.5.4/#superior-extensibility-and-reuse)). I did not test extending any of them, so this is an expectation, not a result.
- **Pause and resume is where frameworks differ most.** It took one checkpointer argument in Python, three extra settings in LangGraph4j, and a split action plus a serializer workaround in Embabel (sections 2, 3 and 5).
- **Typed output beats listing fields in a prompt.** Asking for a type removed the field list from the Embabel prompt and let the missing-field check, the draft map and the prompt all hang off one pair of records (section 5). Spring AI's `entity()` does the same for the parsing.
- **In this demo, existing Java conventions carried over.** Declaring what is missing with Jakarta validation, and wiring the rest with Spring beans, needed no new concepts beyond what a Spring developer already uses (section 5). Whether that holds for a real enterprise codebase is a hypothesis I think is plausible, not something the demo shows: I used my own small domain classes only (see "Not proven"). Its cost in my port was the Spring Boot 4 requirement and the persistence rough edge above.

## Not proven

- **Small real runs only.** Each Java app ran once per check against live Claude and MongoDB Atlas (section 7), which shows the flows work but not how often they fail. The escalation paths (a third failed rewrite, or six questions) did not occur in the real runs. I also did not run the Python original end to end for this post.
- **Structured replies at scale.** In the real runs Spring AI's `entity()` and Embabel's extraction read Claude's replies correctly in the runs I did. I did not test messy or adversarial job descriptions, or how often the reply cannot be read, which in the Spring AI port now fails the request.
- **Reusing a real enterprise codebase.** I did not apply the agent to an existing, already-annotated domain model, so how well real legacy classes and rules carry over is untested.
- **Jakarta validation on the model-filled type.** I expected constraints on `ExtractedFields` to reject legitimately absent fields and kept them off it; I did not test that.
- **The rewrite step.** Nothing in the pipeline checks the rewritten text for invented content. In the real runs, the critiques of the rewritten drafts praised details such as a salary range and on-call load that the original description did not give ([record](posts/evidence/job-agent-real-runs.txt)); I did not save the rewritten texts.
- **Quarkus and Micronaut.** Section 6 only points at their documentation; I did not build with either.
- **How easy it is.** "Easy" here is one person's experience, helped by a coding agent, on one small workflow. I did not time the ports or compare them with a team's experience. The line counts say nothing about maintainability.
- **Planner behaviour with several applicable actions, scaling and cost.** Not tested.
- What would change my conclusion: a real run that fails on real model output, or a newer Embabel version that makes the serializer workaround unnecessary or the platform requirement lighter.
