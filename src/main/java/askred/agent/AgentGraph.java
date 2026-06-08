package askred.agent;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.action.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import static org.bsc.langgraph4j.StateGraph.*;

@SuppressWarnings({"unchecked","rawtypes"})
@Service
public class AgentGraph {
    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);
    private final IntentRouter router; private final HybridRetriever retriever;
    private final DecisionReasoner reasoner; private final ResponseActor actor;
    private final MemoryManager memory; private final SessionStore sessionStore;
    private final CompiledGraph<AgentState> compiledGraph;

    public AgentGraph(IntentRouter router, HybridRetriever retriever,
            DecisionReasoner reasoner, ResponseActor actor,
            MemoryManager memory, SessionStore sessionStore) throws Exception {
        this.router=router; this.retriever=retriever; this.reasoner=reasoner;
        this.actor=actor; this.memory=memory; this.sessionStore=sessionStore;
        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);
        g.addNode("route", n(router)); g.addNode("retrieve", r(retriever));
        g.addNode("reason", d(reasoner)); g.addNode("act", a(actor));
        g.addEdge(START, "route");
        g.addConditionalEdges("route", ar(), Map.of("retrieve","retrieve","act","act"));
        g.addEdge("retrieve","reason"); g.addEdge("reason","act"); g.addEdge("act",END);
        this.compiledGraph = g.compile();
    }

    private AsyncNodeAction<AgentState> n(IntentRouter x) {
        return s -> {
            AskRedState a = stateFrom(s.data()); x.route(a);
            return CompletableFuture.completedFuture(toMap(a));
        };
    }
    private AsyncNodeAction<AgentState> r(HybridRetriever x) {
        return s -> {
            AskRedState a = stateFrom(s.data());
            try { x.retrieve(a); } catch(Exception e){}
            return CompletableFuture.completedFuture(toMap(a));
        };
    }
    private AsyncNodeAction<AgentState> d(DecisionReasoner x) {
        return s -> { AskRedState a = stateFrom(s.data()); x.reason(a); return CompletableFuture.completedFuture(toMap(a)); };
    }
    private AsyncNodeAction<AgentState> a(ResponseActor x) {
        return s -> { AskRedState a = stateFrom(s.data()); x.act(a); return CompletableFuture.completedFuture(toMap(a)); };
    }
    private AsyncEdgeAction<AgentState> ar() {
        return s -> CompletableFuture.completedFuture("chat".equals(s.data().get("intent"))?"act":"retrieve");
    }

    public String execute(String uid, String msg, String sid) throws Exception {
        AskRedState s = sessionStore.load(sid);
        if(s==null){var p=memory.loadProfile(uid);s=AskRedState.builder().userId(uid).sessionId(sid).messages(new ArrayList<>(List.of(new AskRedState.Message("user",msg)))).userProfile(p).build();}
        else s.getMessages().add(new AskRedState.Message("user",msg));
        AgentState result = compiledGraph.invoke(toMap(s)).orElseThrow();
        s = stateFrom(result.data());
        sessionStore.save(sid,s); memory.saveFromState(s);
        return s.getFinalResponse();
    }

    // AskRedState 持有复杂对象（List<CleanedNote>），invoke() 用 Gson 克隆 state 时
    // 会把整个 data map 序列化。所以我们必须把 AskRedState 还原为平铺的 Map 条目，
    // 不把 Java 对象引用放入 data map。
    static AskRedState stateFrom(Map<String,Object> m) {
        AskRedState s = new AskRedState();
        s.setUserId((String)m.get("userId")); s.setSessionId((String)m.get("sessionId"));
        s.setIntent((String)m.getOrDefault("intent",""));
        s.setDecisionStage(m.get("decisionStage") instanceof AskRedState.DecisionStage
            ? (AskRedState.DecisionStage)m.get("decisionStage") : AskRedState.DecisionStage.CLARIFY);
        s.setFinalResponse((String)m.get("finalResponse"));
        s.setUserProfile((AskRedState.UserProfile)m.get("userProfile"));
        s.setMessages((List)m.getOrDefault("messages", new ArrayList<>()));
        s.setMissingInfo((List)m.getOrDefault("missingInfo", new ArrayList<>()));
        s.setSearchResults((List)m.getOrDefault("searchResults", new ArrayList<>()));
        s.setRankedResults((List)m.getOrDefault("rankedResults", new ArrayList<>()));
        return s;
    }
    static Map<String,Object> toMap(AskRedState s) {
        Map<String,Object> m = new HashMap<>();
        m.put("userId", s.getUserId()); m.put("sessionId", s.getSessionId());
        m.put("intent", s.getIntent()); m.put("decisionStage", s.getDecisionStage());
        m.put("finalResponse", s.getFinalResponse()); m.put("userProfile", s.getUserProfile());
        m.put("messages", s.getMessages()); m.put("missingInfo", s.getMissingInfo());
        m.put("searchResults", s.getSearchResults()); m.put("rankedResults", s.getRankedResults());
        return m;
    }

    @Service public static class SessionStore {
        private static final long T=30; private final ConcurrentHashMap<String,E> m=new ConcurrentHashMap<>();
        private final ScheduledExecutorService c=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"sc");t.setDaemon(true);return t;});
        public SessionStore(){c.scheduleAtFixedRate(this::ev,5,5,TimeUnit.MINUTES);}
        public AskRedState load(String id){E e=m.get(id);if(e==null)return null;if(System.currentTimeMillis()-e.t>TimeUnit.MINUTES.toMillis(T)){m.remove(id);return null;}e.t=System.currentTimeMillis();return e.s;}
        public void save(String id,AskRedState s){m.put(id,new E(s));}
        void ev(){long cut=System.currentTimeMillis()-TimeUnit.MINUTES.toMillis(T);int n=0;for(var it=m.entrySet().iterator();it.hasNext();)if(it.next().getValue().t<cut){it.remove();n++;}}
        static class E{final AskRedState s;volatile long t;E(AskRedState s){this.s=s;t=System.currentTimeMillis();}}
    }
}
