package askred.agent;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.bsc.langgraph4j.StateGraph.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"unchecked","rawtypes"})
class AgentGraphTest {

    @Test
    void testFourNodeDecisionFlow() throws Exception {
        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);

        g.addNode("route", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data());
            m.put("intent", "decision"); m.put("routeDone", true);
            return CompletableFuture.completedFuture(m);
        });
        g.addNode("retrieve", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data());
            m.put("retrieveDone", true); m.put("results", 5);
            return CompletableFuture.completedFuture(m);
        });
        g.addNode("reason", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data());
            m.put("reasonDone", true); m.put("stage", "RECOMMEND");
            return CompletableFuture.completedFuture(m);
        });
        g.addNode("act", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data());
            m.put("actDone", true); m.put("reply", "推荐巴厘岛");
            return CompletableFuture.completedFuture(m);
        });

        g.addEdge(START, "route");
        g.addConditionalEdges("route", (AsyncEdgeAction<AgentState>) s ->
            CompletableFuture.completedFuture("chat".equals(s.data().get("intent"))?"act":"retrieve"),
            Map.of("retrieve","retrieve","act","act"));
        g.addEdge("retrieve","reason"); g.addEdge("reason","act"); g.addEdge("act",END);

        AgentState result = g.compile().invoke(new HashMap<>()).orElseThrow();

        assertEquals(true, result.data().get("routeDone"));
        assertEquals(true, result.data().get("retrieveDone"));
        assertEquals(true, result.data().get("reasonDone"));
        assertEquals(true, result.data().get("actDone"));
        assertEquals(5, result.data().get("results"));
        assertEquals("RECOMMEND", result.data().get("stage"));
        assertEquals("推荐巴厘岛", result.data().get("reply"));
    }

    @Test
    void testChatSkipsRetrieveAndReason() throws Exception {
        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);

        g.addNode("route", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data()); m.put("intent", "chat");
            return CompletableFuture.completedFuture(m);
        });
        g.addNode("retrieve", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data()); m.put("retrieveCalled", true);
            return CompletableFuture.completedFuture(m);
        });
        g.addNode("reason", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data()); m.put("reasonCalled", true);
            return CompletableFuture.completedFuture(m);
        });
        g.addNode("act", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data()); m.put("actCalled", true);
            return CompletableFuture.completedFuture(m);
        });

        g.addEdge(START, "route");
        g.addConditionalEdges("route", (AsyncEdgeAction<AgentState>) s ->
            CompletableFuture.completedFuture("chat".equals(s.data().get("intent"))?"act":"retrieve"),
            Map.of("retrieve","retrieve","act","act"));
        g.addEdge("retrieve","reason"); g.addEdge("reason","act"); g.addEdge("act",END);

        AgentState result = g.compile().invoke(new HashMap<>()).orElseThrow();

        assertEquals(true, result.data().get("actCalled"));
        assertNull(result.data().get("retrieveCalled"));
        assertNull(result.data().get("reasonCalled"));
    }

    @Test
    void testAskRedStatePassThrough() throws Exception {
        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);

        g.addNode("step", (AsyncNodeAction<AgentState>) s -> {
            Map<String,Object> m = new HashMap<>(s.data());
            m.put("intent", "decision");
            m.put("stage", "RECOMMEND");
            m.put("reply", "done");
            return CompletableFuture.completedFuture(m);
        });
        g.addEdge(START,"step"); g.addEdge("step",END);

        Map<String,Object> input = new HashMap<>();
        input.put("userId", "u1");
        input.put("sessionId", "s1");

        AgentState result = g.compile().invoke(input).orElseThrow();

        assertEquals("decision", result.data().get("intent"));
        assertEquals("RECOMMEND", result.data().get("stage"));
        assertEquals("done", result.data().get("reply"));
        assertEquals("u1", result.data().get("userId"));
    }
}
