package askred.agent;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DecisionReasoner {

    private static final Logger log = LoggerFactory.getLogger(DecisionReasoner.class);

    private final ChatModel normalModel;

    public DecisionReasoner(@Qualifier("normalModel") ChatModel normalModel) {
        this.normalModel = normalModel;
    }

    public void reason(AgentState state) {
        List<String> missing = checkIfNeedClarify(state);
        if (!missing.isEmpty()) {
            state.setDecisionStage(AgentState.DecisionStage.CLARIFY);
            state.setMissingInfo(missing);
            return;
        }
        state.setDecisionStage(AgentState.DecisionStage.RECOMMEND);
        state.setRankedResults(state.getSearchResults());
    }

    private List<String> checkIfNeedClarify(AgentState state) {
        String lastMsg = state.getMessages().get(state.getMessages().size() - 1).content();
        boolean hasProfile = state.getUserProfile() != null;
        String profile = hasProfile
            ? state.getUserProfile().toPromptFragment()
            : "无已知偏好";
        log.info("  [REASON-CLARIFY] usingProfile={}, profileFragment='{}'", hasProfile, profile);

        String prompt = """
            用户问题：%s
            已知偏好：%s

            如果缺少关键决策信息，列出缺少的维度（如：预算/天数/同行人/风格偏好）。
            每行一个维度，不要编号。如果信息充足，返回 "SUFFICIENT"。
            """.formatted(lastMsg, profile);

        String response = normalModel.chat(prompt).trim();
        if ("SUFFICIENT".equalsIgnoreCase(response)) {
            return List.of();
        }
        return response.lines()
            .map(String::trim)
            .filter(l -> !l.isEmpty())
            .toList();
    }
}
