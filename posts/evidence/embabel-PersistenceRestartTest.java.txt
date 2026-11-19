package jobagent;

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import jobagent.JdDomain.ExtractedFields;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A run paused for the recruiter is snapshotted to a file-backed database. A brand-new
 * application context (empty in-memory state) over the same database file can resume it.
 */
@SpringBootTest(classes = {JobAgentApplication.class, PersistenceRestartTest.TestStore.class})
class PersistenceRestartTest extends EmbabelMockitoIntegrationTest {

    @TestConfiguration
    static class TestStore {
        @Bean
        JobStore jobStore() {
            return new InMemoryJobStore();
        }
    }

    private static final String DB = "./target/restart-test-" + UUID.randomUUID();

    private static String[] persistenceProperties() {
        return new String[] {
                "spring.datasource.url=jdbc:h2:file:" + DB + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:com/embabel/agent/jdbc/schema.sql",
                "embabel.agent.platform.persistence.enabled=true",
                "embabel.agent.platform.persistence.provider=jdbc",
                "embabel.agent.platform.persistence.checkpoint-policy=WAITING",
        };
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        for (String p : persistenceProperties()) {
            String[] kv = p.split("=", 2);
            registry.add(kv[0], () -> kv[1]);
        }
    }

    @Autowired
    PipelineService pipeline;

    @Test
    void pausedRunCanBeResumedByAFreshApplicationContext() {
        whenCreateObject(p -> p.contains("Extract the job posting details"), ExtractedFields.class).thenReturn(
                new ExtractedFields("SRE", "Globex", "", "", ""));

        var first = pipeline.start("We need an SRE at Globex.");
        assertEquals("What is the location?", first.question());

        // "Restart": a second, independent application context over the same database file.
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(
                JobAgentApplication.class, TestStore.class)
                .web(WebApplicationType.NONE)
                .properties(persistenceProperties())
                .run()) {
            PipelineService restarted = second.getBean(PipelineService.class);
            var next = restarted.answer(first.processId(), "Hyderabad");
            assertEquals("What is the exp?", next.question());
        }
    }
}
