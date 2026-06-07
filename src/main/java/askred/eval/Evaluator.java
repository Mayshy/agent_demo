package askred.eval;

import askred.agent.AgentGraph;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class Evaluator {

    private final AgentGraph agent;
    private final ChatModel cheapModel;

    public Evaluator(AgentGraph agent, @Qualifier("cheapModel") ChatModel cheapModel) {
        this.agent = agent;
        this.cheapModel = cheapModel;
    }

    public record EvalResult(String query, int relevance, int completeness,
                             int personalization, int actionability, boolean pass) {}

    public List<EvalResult> evaluate(List<EvalQuery> queries) throws Exception {
        return queries.stream().map(q -> {
            try {
                return evaluateOne(q);
            } catch (Exception e) {
                return new EvalResult(q.query(), 0, 0, 0, 0, false);
            }
        }).toList();
    }

    private EvalResult evaluateOne(EvalQuery query) throws Exception {
        String response = agent.execute("eval-user", query.query(), "eval-session");

        String expected = String.join(", ", query.expectedAspects());
        String prompt = """
            对以下回答评分（每项1-5分），返回严格JSON：
            {"relevance":N,"completeness":N,"personalization":N,"actionability":N}

            回答：%s
            应包含：%s
            """.formatted(response, expected);

        String json = cheapModel.chat(prompt).trim();
        if (json.contains("{")) json = json.substring(json.indexOf("{"), json.lastIndexOf("}") + 1);

        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        int r = node.get("relevance").asInt();
        int c = node.get("completeness").asInt();
        int p = node.get("personalization").asInt();
        int a = node.get("actionability").asInt();
        boolean pass = (r + c + p + a) >= 12;

        return new EvalResult(query.query(), r, c, p, a, pass);
    }
}
