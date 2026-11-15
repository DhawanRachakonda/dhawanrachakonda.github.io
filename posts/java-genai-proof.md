# Is Java really too heavy for GenAI? The numbers, the setup and the sources

**TL;DR**
- Same Spring Boot 4 + Spring AI application code, run two ways: JVM for local/RC dev, GraalVM native image for QA/prod.
- Measured result on the same Render service: peak memory about 70% vs about 23% of the instance, startup 49.2 s vs 1.1 s (details in section 1).
- Native images are built in GitHub Actions and published to GHCR. Render just pulls and runs the image.
- You don't have to leave Java to build production GenAI. Spring AI covers the model layer, and Embabel covers deterministic workflows.

I built a GenAI chatbot (React + Spring Boot 4 + Spring AI 2) that answers questions over live assignment data and streams answers token by token. I ran the same application code on the JVM and as a GraalVM native image, and measured both.

## 1. Measured: JVM vs native

| | JVM (Java 27) | Native (GraalVM 25) |
|---|---|---|
| Memory, peak | about 70% of the instance | about 23% of the instance |
| Memory, steady | about 15–40% | mostly under 20% |
| CPU, peak | about 100% at startup | about 3% |
| Startup (`Started ChatbotApplication in`) | 49.2 s | 1.1 s |

That is roughly 3x less peak memory and about 45x faster startup. Memory and CPU are percentages of the instance's limits, read by eye from the Render metrics charts, so treat them as approximate. Startup comes straight from the application logs.

Both runs were deployed as web services on Render, and the charts show "average across all instances" from Render's metrics.

### The charts and logs

| | JVM (Java 27) | Native (GraalVM 25) |
|---|---|---|
| CPU | ![JVM CPU](posts/images/figure1.jpeg) | ![Native CPU](posts/images/figure4.jpeg) |
| Memory | ![JVM memory](posts/images/figure2.jpeg) | ![Native memory](posts/images/figure5.jpeg) |
| Startup logs | ![JVM startup logs](posts/images/figure3.jpeg) | ![Native startup logs](posts/images/figure6.jpeg) |

Note: the two runs use different JDKs (Java 27 on the JVM, GraalVM 25 for native), so the gap comes from ahead-of-time compilation, not from anything new in Java 27.

## 2. One codebase, two ways to run it

- **Local, RC and dev:** run on the normal JVM (debugger, fast feedback).
- **QA and prod:** build a native image.

The application code is the same. Native is a Maven profile (`-Pnative`) in [`backend/pom.xml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/pom.xml), plus a small file of reflection hints, [`NativeHints.java`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/backend/src/main/java/com/alumnx/chatbot/NativeHints.java). The hints are additive and don't affect JVM runs.

## 3. How the native image is built and deployed

1. A manual GitHub Actions workflow ([`native-image.yml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/.github/workflows/native-image.yml)) runs `mvn -Pnative native:compile` in a GraalVM 25 container. Native compilation needs about 8–11 GB of RAM. In this project, 8 GB runners failed and 16 GB runners succeeded, so the build runs on CI and not on the host.
2. The workflow pushes the finished binary, packaged as a container image, to GitHub Container Registry (GHCR).
3. Render pulls that image and runs it. Nothing is built on Render. The service is described in [`render.yaml`](https://github.com/DhawanRachakonda/assignments-chat-bot/blob/master/render.yaml) (`runtime: image`). See [Render: deploy an image](https://render.com/docs/deploy-an-image).

## 4. Why the JVM uses more memory by default

It's a trade. CPU costs more than RAM, so the JVM's garbage collectors (G1, ZGC) spend extra memory to reduce the CPU spent on memory management. A bigger heap means less GC work. You can tune it, and native image lets you pick the other end: a small footprint and instant start.

Sources:
- JavaOne 2026, "Principles of Memory Management in Java": https://www.youtube.com/watch?v=xr73mR7ii9M
- Session page: https://dev.java/community/javaone-2026/sessions/lrn1449
- Write-up: https://inside.java/2026/09/04/memory-management-principles-java

## 5. Java for GenAI

- **Spring AI 2.0 went GA on 12 June 2026**: ChatClient, tool calling, chat memory, streaming and MCP support, with Spring's security and observability behind them. ([Spring blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/), [Visual Studio Magazine](https://visualstudiomagazine.com/articles/2026/06/29/spring-ai-2-0-goes-ga-giving-java-developers-a-more-mature-ai-app-stack.aspx))
- **62% of surveyed organisations use Java to code AI functionality, up from 50% a year earlier** (Azul 2026 State of Java survey, Feb 2026). It's a vendor-sponsored survey of Java users, so read it as a share of Java shops. ([press release](https://www.businesswire.com/news/home/20260210972138/en/Azul-2026-State-of-Java-Survey-Report-62-of-Enterprises-Now-Leverage-Java-to-Power-AI-Functionality-41-Rely-on-High-Performance-Java-Platforms-to-Reduce-Cloud-Compute-Costs))
- **If your ecosystem is already Java, you don't need to migrate to Python to ship GenAI.** Keep your auth, data layer, CI and team, and add Spring AI.

## 6. Deterministic workflows with Embabel

Production systems need predictable flows, not only prompts. [Embabel](https://github.com/embabel), created by Rod Johnson, is a JVM agent framework built on Spring AI. You declare actions and goals, and a classical GOAP planner, not an LLM, chooses the sequence. That makes the flow deterministic, debuggable and cheap. It has reached 1.0 GA.

Sources: [InfoQ](https://www.infoq.com/news/2025/06/introducing-embabel-ai-agent), [The New Stack](https://thenewstack.io/meet-embabel-a-framework-for-building-ai-agents-with-java/), [Dan Vega on 1.0 GA](https://www.danvega.dev/blog/embabel-1-0-ga).

## 7. Worth watching (both experimental)

- **Vector API (SIMD):** still incubating, now in its 12th incubator in JDK 27, waiting on Project Valhalla. The stated goal is performance comparable to hand-written SIMD in native code. ([JEP 529](https://openjdk.org/jeps/529), [JEP 537](https://openjdk.org/jeps/537))
- **Project Babylon and HAT:** GPU programming from Java with CUDA and OpenCL backends. It needs the Babylon JDK build (base JDK 26+), not mainline OpenJDK yet. ([Project Babylon](https://openjdk.org/projects/babylon), [HAT article](https://openjdk.org/projects/babylon/articles/hat-matmul/hat-matmul), JavaOne 2026 talk: https://www.youtube.com/watch?v=rL3sWS_8b-g)
