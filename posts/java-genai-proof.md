# Is Java really too heavy for GenAI? The numbers, the setup and the sources

**TL;DR**
- The hard requirement: run on Render's free plan, where a web service gets only 512 MB of RAM and 0.1 CPU. That is why I tried a native image.
- Same Spring Boot 4 + Spring AI application code, run two ways: as a normal JVM app and as a GraalVM native image.
- Measured on Render's free plan (approximately, read from Render's charts): peak memory about 70% vs about 23% of the 512 MB, startup 49.2 s vs 1.1 s (details in section 1).
- Native images are built in GitHub Actions and published to GHCR, and Render just pulls and runs the image. Getting the native build working took real work (section 4).

I built a small GenAI chatbot (React + Spring Boot 4 + Spring AI 2) that answers questions over live assignment data and streams answers token by token (see [`ChatController.java`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/java/com/alumnx/chatbot/ChatController.java) for the streaming endpoint and [`AssignmentTools.java`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/java/com/alumnx/chatbot/AssignmentTools.java) for the data tools). I ran the same application code on the JVM and as a GraalVM native image, both on Render, and compared them.

## What this post explains, and why

- **What:** how one Spring Boot 4 + Spring AI chatbot behaves on Render's free plan as a normal JVM app versus a GraalVM native image, and how the native image is built and deployed.
- **Why:** the free plan has 512 MB of RAM and 0.1 CPU, and I wanted to know whether a Java GenAI app could run inside that, and what it takes.
- **After reading you will understand:** why the free plan's limits pushed me towards a native image (the context section); what the memory, CPU and startup difference looked like on that plan (section 1); how the native image is built in CI and run on Render (sections 2 and 3); what the native port needed (section 4); and why the JVM tends to use more memory (section 5).
- **Not covered:** throughput or latency under load, other hosting plans, and other JVM tuning options. See "Not proven".

## The constraint that drove this

I wanted to host the chatbot for free on Render. A free web service gets **512 MB of RAM and 0.1 CPU**, and it spins down after 15 minutes without traffic (about a minute to start again). The app, the runtime and the AI client libraries all had to fit inside that.

A **native image** is a program compiled ahead of time by GraalVM into a standalone executable, so it does not need a JVM to warm up at startup and usually has a smaller memory footprint. The cost is a heavier build and some extra configuration.

Sources: [Render compute plans](https://render.com/docs/compute-plans) (free web service: 512 MB, 0.1 CPU) and [Render free instances](https://render.com/docs/free) (spin-down after 15 minutes, about a minute to restart). Both of my Render services are on `plan: free` in [`render.yaml` for the JVM service](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/74edcfd/render.yaml) and [`render.yaml` for the native service](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/4b8a252/render.yaml).

## 1. Measured: JVM vs native, on that free plan

| | JVM (Java 27) | Native (GraalVM 25) |
|---|---|---|
| Memory, peak | about 70% of 512 MB (roughly 360 MB) | about 23% of 512 MB (roughly 120 MB) |
| Memory, steady | about 15–40% | mostly under 20% |
| CPU, peak | about 100% at startup | about 3% |
| Startup (`Started ChatbotApplication in`) | 49.2 s | 1.1 s |

That is roughly 3x less peak memory and about 45x faster startup. Memory and CPU are percentages of the instance's limits, **read by eye** from the Render metrics charts, so treat them, and the MB figures I derived from them, as approximate. Startup comes straight from the application logs (figures 3 and 6).

**How the two runs differ.** The charts show "average across all instances" from Render's metrics, and they cover different time windows and numbers of restarts: the JVM chart has several restarts and spikes, the native one fewer. The JVM service was set up for Java 27 (I have not tied the charted run to a specific deploy, so this is the configuration at the time: `java.version` is 27 in the [first `pom.xml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/3bc9639/backend/pom.xml) and the [first Dockerfile](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/3bc9639/backend/Dockerfile)); the native image is built with GraalVM 25 (the [Dockerfile after the move to Java 25](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/6270515/backend/Dockerfile)). So the gap reflects ahead-of-time compilation, not a Java 27 feature. My hypothesis, which I did not measure: with only 0.1 CPU available, the JVM's startup work (class loading and JIT compilation) is slowed down, so part of the 49 s may come from the free plan's CPU limit and not only from the JVM.

### The charts and logs

| | JVM (Java 27) | Native (GraalVM 25) |
|---|---|---|
| CPU | ![JVM CPU](posts/images/figure1.jpeg) | ![Native CPU](posts/images/figure4.jpeg) |
| Memory | ![JVM memory](posts/images/figure2.jpeg) | ![Native memory](posts/images/figure5.jpeg) |
| Startup logs | ![JVM startup logs](posts/images/figure3.jpeg) | ![Native startup logs](posts/images/figure6.jpeg) |

## 2. One codebase, two ways to run it

- **Local, RC and dev:** run on the normal JVM (debugger, fast feedback).
- **QA and prod:** build a native image.

The application code is the same. The native build uses the `native` Maven profile that Spring Boot provides (`mvn -Pnative native:compile`, see the [Spring Boot native image guide](https://docs.spring.io/spring-boot/how-to/native-image/developing-your-first-application.html)), with the `native-maven-plugin` options set in [`backend/pom.xml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/pom.xml). On top of the application code there is one small file of reflection hints, [`NativeHints.java`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/java/com/alumnx/chatbot/NativeHints.java), and a couple of configuration choices described in section 4. I expect the hints to be ignored on a normal JVM run, but I have not tested that.

For the JVM service I had already tuned the container for 512 MB in [this commit](https://github.com/DhawanRachakonda/assignments-chat-bot/commit/74edcfd): `-XX:+UseSerialGC -XX:MaxRAMPercentage=60 -XX:TieredStopAtLevel=1 -Xss512k`. I have not confirmed that the charted JVM run used exactly that build.

## 3. How the native image is built and deployed

1. A manual GitHub Actions workflow ([`native-image.yml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/.github/workflows/native-image.yml)) builds the container image from [`backend/Dockerfile`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/Dockerfile). Inside that Dockerfile, a GraalVM 25 build stage runs `./mvnw -q -B -Pnative -DskipTests native:compile`. The compile is memory-hungry: the project passes `-J-Xmx12g` to the native-image builder in `backend/pom.xml`, and the workflow adds swap space.
2. GitHub's standard Linux runner has [8 GB of RAM for private repositories and 16 GB for public ones](https://docs.github.com/en/actions/reference/runners/github-hosted-runners). The workflow's [run 2](https://github.com/DhawanRachakonda/assignments-chat-bot/actions/runs/43611678138) and [run 3](https://github.com/DhawanRachakonda/assignments-chat-bot/actions/runs/43613081159) failed in the native compile step, and [run 4](https://github.com/DhawanRachakonda/assignments-chat-bot/actions/runs/43615609664) and [run 5](https://github.com/DhawanRachakonda/assignments-chat-bot/actions/runs/43618964192) succeeded. My own account, not something I have a saved log for: the first failures happened while the repo was private, I believe from running out of memory, and the builds passed after I made the repo public and the runner got more memory. The compile needs far more memory than Render's 512 MB, which is why the build runs on CI.
3. The workflow pushes the finished binary, packaged as a container image, to GitHub Container Registry (GHCR).
4. Render pulls that image and runs it. Nothing is built on Render. The service is described in [`render.yaml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/render.yaml) (`runtime: image`). See [Render: deploy an image](https://render.com/docs/deploy-an-image).

## 4. What the native port needed

Native image works by finding everything the program uses at build time, so anything found only at runtime (reflection, for example) has to be declared. The commit that moved the app to a native image, [`6270515`](https://github.com/DhawanRachakonda/assignments-chat-bot/commit/6270515), records the three changes it needed:

- **Reflection hints.** [`NativeHints.java`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/java/com/alumnx/chatbot/NativeHints.java) registers the app's own records, the `@Tool` methods and the OpenAI SDK's chat classes. I registered only the SDK packages the app uses and not the whole SDK, which is my recollection of keeping the native build manageable; I have no saved log of what happened when I registered everything.
- **A different HTTP client.** The commit message says the JDK's built-in HTTP client is used because Reactor Netty needs extra native flags. It is set with `spring.http.clients.imperative.factory: jdk` in [`application.yaml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/resources/application.yaml).
- **An explicit scheduler for chat memory.** The commit message says the chat-memory advisor had a null default scheduler in native, so [`ChatConfig.java`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/java/com/alumnx/chatbot/ChatConfig.java) passes `Schedulers.boundedElastic()`.

The same commit message also records a local measurement before I deployed: the native image started in about 0.3 s, used about 56 MiB idle and about 108 MiB after a test run, under a 250 MB limit. That is a local run on my machine, so it is not comparable to the Render numbers above.

## 5. Why the JVM tends to use more memory

This part is my explanation, supported by the Oracle guide. The [Java SE GC tuning guide](https://docs.oracle.com/en/java/javase/26/gctuning/factors-affecting-garbage-collection-performance.html) says that, because collections happen when generations fill up, "throughput is correlated with memory size", and that you can minimise the memory footprint by minimising the heap. So a JVM given more memory does less garbage-collection work, and one squeezed into a small heap does more. In my charts the native image sat at the small-footprint end, but I have not measured why.

The same trade-off is the subject of the JavaOne 2026 session ["Principles of Memory Management in Java"](https://dev.java/community/javaone-2026/sessions/lrn1449) ([talk](https://www.youtube.com/watch?v=xr73mR7ii9M), [Inside Java write-up](https://inside.java/2026/09/04/memory-management-principles-java)). I could not read the talk, so I am not quoting it and I do not name specific collectors from it.

## 6. Java for GenAI

- **Spring AI 2.0 went GA on 12 June 2026** ([Spring blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)). Its reference documents the pieces this chatbot uses: [`ChatClient`, streaming with `.stream()`, tool calling and chat memory](https://docs.spring.io/spring-ai/reference/api/chatclient.html).
- **62% of surveyed organisations use Java to code AI functionality, up from 50% a year earlier** (Azul 2026 State of Java survey, Feb 2026). It is a vendor-sponsored survey of Java users, so read it as a share of Java shops. ([Channel Insider coverage](https://www.channelinsider.com/infrastructure/azul-2026-java-survey-results/), [Azul press release](https://www.businesswire.com/news/home/20260210972138/en/Azul-2026-State-of-Java-Survey-Report-62-of-Enterprises-Now-Leverage-Java-to-Power-AI-Functionality-41-Rely-on-High-Performance-Java-Platforms-to-Reduce-Cloud-Compute-Costs))
- For me, the takeaway is modest: I was able to write this prototype entirely in Java. I have not run it under production load.

## 7. Deterministic workflows with Embabel

[Embabel](https://github.com/embabel), created by Rod Johnson, is a JVM agent framework built on Spring AI. You declare actions and goals, and a classical planner (GOAP, goal-oriented action planning) rather than an LLM chooses the sequence of steps, so the flow does not depend on a model's choice. It has reached 1.0 GA.

**Version gap:** according to Dan Vega's write-up, Embabel 1.0 runs on Spring AI 1.1.7 and Spring Boot 3.5.x, and does not support Spring Boot 4 or Spring AI 2.0 yet (that is tracked for Embabel 2.0). My chatbot is on Spring Boot 4 and Spring AI 2, so I have not tried Embabel with it.

Sources (the excerpts I checked are saved in [`posts/evidence/openjdk-sources-verified.txt`](posts/evidence/openjdk-sources-verified.txt)): [InfoQ](https://www.infoq.com/news/2025/06/introducing-embabel-ai-agent), [The New Stack](https://thenewstack.io/meet-embabel-a-framework-for-building-ai-agents-with-java/), [Dan Vega on 1.0 GA](https://www.danvega.dev/blog/embabel-1-0-ga).

## 8. Not proven

- **Exact memory and CPU.** The percentages are read by eye from Render's charts, and the MB figures are derived from them, so they are approximate.
- **One app, no repeats.** I did not repeat the runs or run a load test, so there is no data on throughput or latency under load.
- **Different time windows.** The JVM and native charts cover different windows and numbers of restarts, so they are not a controlled experiment.
- **JVM tuning.** I tried one JVM configuration (the commit in section 2) and did not compare others, so this does not show that a JVM cannot be made to fit comfortably in 512 MB. A tuned JVM that fits with acceptable startup would weaken my conclusion.
- **Why the CI builds failed.** I have no saved log of the failures, so the out-of-memory explanation in section 3 is my belief, not a proven cause.
- **Startup cause.** The 49 s JVM startup includes the free plan's 0.1 CPU throttling; I did not separate that from the JVM's own cost.
- **Embabel, the Vector API and Babylon/HAT.** I have not tried these; they are cited from their sources only.

## 9. Worth watching (both experimental)

- **Vector API (SIMD):** still incubating, now in its 12th incubator in JDK 27, waiting on Project Valhalla. JEP 537 states the goal as vector computations that compile at run time to optimal vector instructions on supported CPUs, performing better than the equivalent scalar code. ([JEP 529](https://openjdk.org/jeps/529), [JEP 537](https://openjdk.org/jeps/537)) I have not benchmarked it, and I am not claiming it beats native code. (The JEP wording I relied on is saved in [`posts/evidence/openjdk-sources-verified.txt`](posts/evidence/openjdk-sources-verified.txt).)
- **Project Babylon and HAT:** GPU programming from Java through the CUDA and OpenCL programming models, as an exploration of the Babylon project. HAT currently requires the Babylon JDK build and a base JDK 26 or later, so it is not in mainline OpenJDK. ([Project Babylon](https://openjdk.org/projects/babylon), [HAT article](https://openjdk.org/projects/babylon/articles/hat-matmul/hat-matmul), [HAT README](https://builds.shipilev.net/patch-openjdk-babylon-code-reflection/hat/README.md.html), a related JavaOne 2026 talk, ["How does Java fit into the world of AI, and how can it become an even better fit?"](https://www.youtube.com/watch?v=rL3sWS_8b-g), which I could not read, so I am not claiming it covers HAT specifically; the page excerpts I checked are saved in [`posts/evidence/openjdk-sources-verified.txt`](posts/evidence/openjdk-sources-verified.txt))
