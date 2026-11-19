package jobagent;

import org.springframework.ai.chat.client.ChatClient;
import org.bsc.langgraph4j.CompiledGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the pieces as Spring beans: Claude through Spring AI, the job store, and the
 * LangGraph4j graph with SQLite checkpoints. Tests replace the model and the store.
 */
@Configuration
public class JobAgentConfig {

    /**
     * Spring AI auto-configures the Anthropic chat model from {@code spring.ai.anthropic.*}
     * (see application.yml) and hands us a ChatClient.Builder. Tests replace this bean.
     */
    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** Only created when MONGO_URI is set; tests provide their own in-memory JobStore instead. */
    @Bean
    @ConditionalOnProperty(name = "MONGO_URI")
    JobStore jobStore(@Value("${MONGO_URI}") String mongoUri,
                      @Value("${job-agent.mongo.database}") String database,
                      @Value("${job-agent.mongo.collection}") String collection) {
        return new MongoJobStore(mongoUri, database, collection);
    }

    @Bean
    CompiledGraph<JdState> jdIntakeGraph(ChatClient chatClient, JobStore store,
                                         @Value("${job-agent.checkpoint-db}") String checkpointDb) throws Exception {
        return new JdIntakePipeline(chatClient, store).buildGraph(JdIntakePipeline.checkpointer(checkpointDb));
    }
}
