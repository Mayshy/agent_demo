package askred.controller;

import askred.agent.AgentGraph;
import askred.eval.EvalQuery;
import askred.eval.Evaluator;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final AgentGraph agentGraph;
    private final Evaluator evaluator;

    public ChatController(AgentGraph agentGraph, Evaluator evaluator) {
        this.agentGraph = agentGraph;
        this.evaluator = evaluator;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest request) throws Exception {
        String response = agentGraph.execute(
            request.userId(),
            request.message(),
            request.sessionId() != null ? request.sessionId() : "default"
        );
        return Map.of("response", response);
    }

    @GetMapping("/eval")
    public List<Evaluator.EvalResult> eval() throws Exception {
        return evaluator.evaluate(EvalQuery.preset());
    }

    public record ChatRequest(String userId, String message, String sessionId) {}
}
