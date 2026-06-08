package askred.agent;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ResponseActor {

    private static final Logger log = LoggerFactory.getLogger(ResponseActor.class);

    private final ChatModel normalModel;
    private final ChatModel expensiveModel;

    public ResponseActor(
        @Qualifier("normalModel") ChatModel normalModel,
        @Qualifier("expensiveModel") ChatModel expensiveModel) {
        this.normalModel = normalModel;
        this.expensiveModel = expensiveModel;
    }

    public void act(AskRedState state) {
        String response = switch (state.getDecisionStage()) {
            case CLARIFY -> generateClarification(state);
            case RECOMMEND -> generateRecommendation(state);
            default -> normalModel.chat(state.getMessages().get(state.getMessages().size() - 1).content());
        };
        state.setFinalResponse(response);
    }

    private String generateClarification(AskRedState state) {
        String missing = String.join("、", state.getMissingInfo());
        String history = state.getMessages().stream()
            .map(m -> m.role() + ": " + m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        String prompt = """
            你是旅行决策助手。用户信息不足，需要温和追问。
            缺少的信息：%s
            对话历史：%s

            问2-3个问题，让用户轻松选择而非填空。友好、自然、像朋友聊天。
            """.formatted(missing, history);

        return normalModel.chat(prompt);
    }

    private String generateRecommendation(AskRedState state) {
        var results = state.getRankedResults();
        boolean hasProfile = state.getUserProfile() != null;
        String profile = hasProfile
            ? state.getUserProfile().toPromptFragment()
            : "无已知偏好";
        log.info("  [ACT-RECOMMEND] usingProfile={}, profileFragment='{}', resultCount={}",
            hasProfile, profile, results.size());

        StringBuilder notesText = new StringBuilder();
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            var n = results.get(i);
            notesText.append("笔记").append(i + 1).append(": ")
                .append(n.originalTitle()).append("\n")
                .append("  内容: ").append(truncate(n.originalDesc(), 300)).append("\n")
                .append("  目的地: ").append(n.destination()).append("\n")
                .append("  标签: ").append(n.tags()).append("\n\n");
        }

        String prompt = """
            基于以下笔记生成旅行推荐。必须引用笔记内容。

            用户偏好：%s

            搜索结果：
            %s

            用中文回复，格式：
            1. 一句话总结推荐理由
            2. Top 3 推荐（每个含：地点名、亮点、预算提示、适合原因）
            3. 一个友好的追问（"要不要帮你细化某一天的行程？"之类）
            """.formatted(profile, notesText);

        return expensiveModel.chat(prompt);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
