package askred.llm;

import askred.util.RateLimiter;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Configuration
public class LlmConfig {

    @Value("${DEEPSEEK_API_KEY}")
    private String apiKey;

    @Value("${MEITUAN_EMBEDDING_KEY:your-embedding-key-here}")
    private String embeddingKey;

    // ── Chat models (DeepSeek, via LangChain4j) ──

    @Bean("cheapModel")
    public ChatModel cheapModel() {
        return OpenAiChatModel.builder()
            .baseUrl("https://api.deepseek.com")
            .apiKey(apiKey)
            .modelName("deepseek-chat")
            .temperature(0.0)
            .maxTokens(50)
            .build();
    }

    @Bean("normalModel")
    public ChatModel normalModel() {
        return OpenAiChatModel.builder()
            .baseUrl("https://api.deepseek.com")
            .apiKey(apiKey)
            .modelName("deepseek-chat")
            .temperature(0.3)
            .maxTokens(500)
            .build();
    }

    @Bean("expensiveModel")
    public ChatModel expensiveModel() {
        return OpenAiChatModel.builder()
            .baseUrl("https://api.deepseek.com")
            .apiKey(apiKey)
            .modelName("deepseek-reasoner")
            .temperature(0.7)
            .maxTokens(2000)
            .build();
    }

    // ── Embedding (美团 aigc, custom HTTP) ──

    @Bean
    public MeituanEmbedder meituanEmbedder() {
        return new MeituanEmbedder(embeddingKey);
    }

    public static class MeituanEmbedder {

        private static final int MAX_RETRIES = 5;
        private static final long INITIAL_BACKOFF_MS = 1000;
        private static final int REQUESTS_PER_MINUTE = 60;
        private static final int CONNECT_TIMEOUT_SEC = 10;
        private static final int READ_TIMEOUT_SEC = 30;

        private final RestClient restClient;
        private final RateLimiter rateLimiter;

        public MeituanEmbedder(String apiKey) {
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
            requestFactory.setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SEC));

            this.restClient = RestClient.builder()
                .baseUrl("https://aigc.sankuai.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
            this.rateLimiter = new RateLimiter(REQUESTS_PER_MINUTE, 5);
        }

        public float[] embed(String text) {
            return embedWithRetry(text, 0);
        }

        private float[] embedWithRetry(String text, int attempt) {
            rateLimiter.acquire();

            try {
                var response = restClient.post()
                    .uri("/v1/openai/native/embeddings")
                    .body(new EmbedRequest("text-embedding-miffy-002", text))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        if (resp.getStatusCode().value() == 429) {
                            throw HttpClientErrorException.create(
                                resp.getStatusCode(), resp.getStatusText(),
                                resp.getHeaders(), null, null);
                        }
                    })
                    .body(EmbedResponse.class);

                if (response == null || response.data == null || response.data.isEmpty()) {
                    throw new RuntimeException("Empty embedding response");
                }
                List<Double> vec = response.data.get(0).embedding();
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
                return arr;
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt >= MAX_RETRIES) {
                    throw new RuntimeException("Embedding API rate limit exceeded after " + MAX_RETRIES + " retries", e);
                }
                long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                System.err.println("Embedding 429, retry " + (attempt + 1) + "/" + MAX_RETRIES
                    + " after " + backoff + "ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during backoff", ie);
                }
                return embedWithRetry(text, attempt + 1);
            }
        }

        public int dimension() { return 1024; }

        record EmbedData(List<Double> embedding) {}
        record EmbedResponse(List<EmbedData> data) {}
        record EmbedRequest(String model, String input) {}
    }
}
