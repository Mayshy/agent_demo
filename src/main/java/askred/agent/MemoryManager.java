package askred.agent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final ElasticsearchClient esClient;
    private final ChatModel cheapModel;

    public MemoryManager(ElasticsearchClient esClient,
                         @Qualifier("cheapModel") ChatModel cheapModel) {
        this.esClient = esClient;
        this.cheapModel = cheapModel;
    }

    public AskRedState.UserProfile loadProfile(String userId) {
        long start = System.currentTimeMillis();
        try {
            GetResponse<Map> resp = esClient.get(g -> g
                .index("user_profile")
                .id(userId), Map.class);
            if (!resp.found() || resp.source() == null) {
                log.info("  [MEMORY-LOAD] userId={} → NO PROFILE (new user, took {}ms)",
                    userId, System.currentTimeMillis() - start);
                return null;
            }

            var src = resp.source();
            var profile = new AskRedState.UserProfile(
                userId,
                stringList(src.get("preferredDestinations")),
                str(src.get("budgetTier")),
                stringList(src.get("travelStyle")),
                str(src.get("typicalDuration")),
                str(src.get("companion"))
            );
            log.info("  [MEMORY-LOAD] userId={} → PROFILE LOADED | budget={} | style={} | duration={} | companion={} | destinations={} (took {}ms)",
                userId,
                profile.budgetTier() != null ? profile.budgetTier() : "null",
                profile.travelStyle() != null && !profile.travelStyle().isEmpty() ? profile.travelStyle() : "[]",
                profile.typicalDuration() != null ? profile.typicalDuration() : "null",
                profile.companion() != null ? profile.companion() : "null",
                profile.preferredDestinations() != null && !profile.preferredDestinations().isEmpty() ? profile.preferredDestinations() : "[]",
                System.currentTimeMillis() - start);
            return profile;
        } catch (IOException e) {
            log.error("  [MEMORY-LOAD] userId={} → FAILED (ES error): {}", userId, e.getMessage(), e);
            return null;
        }
    }

    public void saveFromState(AskRedState state) {
        String userId = state.getUserId();
        if (userId == null || state.getMessages().isEmpty()) return;

        String conversation = state.getMessages().stream()
            .map(m -> m.role() + ": " + m.content())
            .reduce("", (a, b) -> a + "\n" + b);
        if (conversation.isBlank()) return;

        String prompt = """
            分析以下对话，提取用户偏好和事实（仅JSON，不要其他文字）：
            {
              "newPreferences": [{"key": "travelStyle", "value": "拍照"}],
              "newFacts": [{"key": "beenToBali", "value": true}],
              "updatedPreferences": []
            }

            对话：%s
            """.formatted(conversation);

        try {
            String json = cheapModel.chat(prompt).trim();
            log.info("  [MEMORY-SAVE] userId={} → LLM extracted: {}", userId, json.length() > 200 ? json.substring(0, 200) + "..." : json);
            if (json.contains("{")) {
                json = json.substring(json.indexOf("{"), json.lastIndexOf("}") + 1);
            }
            saveMemories(state.getUserId(), json);
            rebuildProfile(state.getUserId());
            log.info("  [MEMORY-SAVE] userId={} → memories persisted + profile rebuilt", userId);
        } catch (Exception e) {
            log.warn("  [MEMORY-SAVE] userId={} → FAILED: {}", userId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void saveMemories(String userId, String json) throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> data = mapper.readValue(json, Map.class);

        List<Map<String, Object>> prefs = (List<Map<String, Object>>) data.getOrDefault("newPreferences", List.of());
        int saved = 0;
        for (var pref : prefs) {
            esClient.index(i -> i
                .index("user_memory")
                .document(Map.of(
                    "userId", userId,
                    "type", "preference",
                    "key", pref.getOrDefault("key", ""),
                    "value", pref.getOrDefault("value", ""),
                    "confidence", 1.0
                )));
            saved++;
        }
        if (saved > 0) {
            log.info("  [MEMORY-SAVE] → {} preference docs indexed to user_memory", saved);
        }
    }

    private void rebuildProfile(String userId) throws IOException {
        var resp = esClient.search(s -> s
            .index("user_memory")
            .query(q -> q.term(t -> t.field("userId").value(userId)))
            .size(50), Map.class);

        List<String> destinations = new ArrayList<>();
        List<String> styles = new ArrayList<>();
        String budget = null;
        String duration = null;
        String companion = null;

        for (var hit : resp.hits().hits()) {
            var src = hit.source();
            if (src == null) continue;
            String key = str(src.get("key"));
            String value = str(src.get("value"));
            if (key == null || value == null) continue;

            switch (key) {
                case "travelStyle" -> styles.add(value);
                case "budget" -> budget = value;
                case "duration" -> duration = value;
                case "companion" -> companion = value;
                case "destination" -> destinations.add(value);
            }
        }

        var profile = new AskRedState.UserProfile(
            userId, destinations, budget, styles, duration, companion);

        esClient.index(i -> i
            .index("user_profile")
            .id(userId)
            .document(Map.of(
                "userId", userId,
                "preferredDestinations", profile.preferredDestinations(),
                "budgetTier", profile.budgetTier() != null ? profile.budgetTier() : "",
                "travelStyle", profile.travelStyle(),
                "typicalDuration", profile.typicalDuration() != null ? profile.typicalDuration() : "",
                "companion", profile.companion() != null ? profile.companion() : ""
            )));
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object obj) {
        if (obj instanceof List) return (List<String>) obj;
        return new ArrayList<>();
    }

    private String str(Object obj) {
        return obj != null ? obj.toString() : null;
    }
}
