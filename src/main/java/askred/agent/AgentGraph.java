package askred.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class AgentGraph {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    private final IntentRouter router;
    private final HybridRetriever retriever;
    private final DecisionReasoner reasoner;
    private final ResponseActor actor;
    private final MemoryManager memory;
    private final SessionStore sessionStore;

    public AgentGraph(IntentRouter router, HybridRetriever retriever,
                      DecisionReasoner reasoner, ResponseActor actor,
                      MemoryManager memory, SessionStore sessionStore) {
        this.router = router;
        this.retriever = retriever;
        this.reasoner = reasoner;
        this.actor = actor;
        this.memory = memory;
        this.sessionStore = sessionStore;
    }

    public String execute(String userId, String message, String sessionId) throws Exception {
        log.info("> AgentGraph START userId={}, sessionId={}", userId, sessionId);

        AgentState state = sessionStore.load(sessionId);
        boolean isNewSession = (state == null);

        if (isNewSession) {
            var profile = memory.loadProfile(userId);
            log.info("  [INIT] new session profile={}", profile != null ? "LOADED" : "EMPTY");
            state = AgentState.builder()
                .userId(userId).sessionId(sessionId)
                .messages(new ArrayList<>(List.of(new AgentState.Message("user", message))))
                .userProfile(profile).build();
        } else {
            List<AgentState.Message> history = new ArrayList<>(state.getMessages());
            history.add(new AgentState.Message("user", message));
            state.setMessages(history);
            log.info("  [INIT] session resumed {} msgs in history", history.size());
        }

        router.route(state);
        log.info("  [ROUTE] intent={}", state.getIntent());

        if ("chat".equals(state.getIntent())) {
            actor.act(state);
            log.info("  [ACT/CHAT] {}chars", state.getFinalResponse() != null ? state.getFinalResponse().length() : 0);
        } else {
            retriever.retrieve(state);
            log.info("  [RETRIEVE] {} results", state.getSearchResults().size());
            reasoner.reason(state);
            log.info("  [REASON] decisionStage={} missingInfo={}", state.getDecisionStage(), state.getMissingInfo());
            actor.act(state);
            log.info("  [ACT] decisionStage={} {}chars", state.getDecisionStage(),
                state.getFinalResponse() != null ? state.getFinalResponse().length() : 0);
        }

        sessionStore.save(sessionId, state);
        memory.saveFromState(state);
        log.info("< AgentGraph END session saved (active sessions: {})", sessionStore.activeSessionCount());
        return state.getFinalResponse();
    }

    @Service
    public static class SessionStore {

        private static final long TTL_MINUTES = 30;
        private final ConcurrentHashMap<String, SessionEntry> store = new ConcurrentHashMap<>();
        private final ScheduledExecutorService cleaner;

        public SessionStore() {
            this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-cleaner");
                t.setDaemon(true);
                return t;
            });
            // 每 5 分钟清理一次过期 session
            cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.MINUTES);
        }

        public AgentState load(String sessionId) {
            SessionEntry entry = store.get(sessionId);
            if (entry == null) return null;
            // 检查是否过期
            if (System.currentTimeMillis() - entry.createdAt > TimeUnit.MINUTES.toMillis(TTL_MINUTES)) {
                store.remove(sessionId);
                return null;
            }
            // 刷新时间戳（活跃 session 续期）
            entry.touch();
            return entry.state;
        }

        public void save(String sessionId, AgentState state) {
            store.put(sessionId, new SessionEntry(state));
        }

        public void evict(String sessionId) {
            store.remove(sessionId);
        }

        public int activeSessionCount() {
            evictExpired(); // 返回前先清理
            return store.size();
        }

        void evictExpired() {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(TTL_MINUTES);
            int evicted = 0;
            Iterator<Map.Entry<String, SessionEntry>> it = store.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next().getValue();
                if (entry.createdAt < cutoff) {
                    it.remove();
                    evicted++;
                }
            }
            if (evicted > 0) {
                LoggerFactory.getLogger(SessionStore.class)
                    .info("  [SESSION-CLEAN] evicted {} expired sessions", evicted);
            }
        }

        private static class SessionEntry {
            final AgentState state;
            volatile long createdAt;

            SessionEntry(AgentState state) {
                this.state = state;
                this.createdAt = System.currentTimeMillis();
            }

            void touch() {
                this.createdAt = System.currentTimeMillis();
            }
        }
    }
}
