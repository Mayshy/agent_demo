package askred.agent;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class IntentRouter {

    private final ChatModel cheapModel;

    public IntentRouter(@Qualifier("cheapModel") ChatModel cheapModel) {
        this.cheapModel = cheapModel;
    }

    public void route(AgentState state) {
        String lastMsg = state.getMessages().get(state.getMessages().size() - 1).content();

        String prompt = """
            判断用户意图，只返回一个词：chat, decision, refuse。

            - chat: 闲聊、问候、非决策类问题
            - decision: 涉及旅行/美食/购物等需要推荐决策的问题
            - refuse: 与生活决策完全无关

            用户消息：%s
            """.formatted(lastMsg);

        String intent = cheapModel.chat(prompt).trim().toLowerCase();
        if (!intent.equals("chat") && !intent.equals("refuse")) {
            intent = "decision";
        }
        state.setIntent(intent);
    }
}
