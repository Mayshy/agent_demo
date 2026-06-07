package askred.agent;

import askred.pipeline.CleanedNote;

import java.util.ArrayList;
import java.util.List;

public class AgentState {

    private String userId;
    private String sessionId;
    private List<Message> messages;
    private int turnCount;
    private String intent;
    private DecisionStage decisionStage;
    private UserProfile userProfile;
    private List<String> missingInfo;
    private List<CleanedNote> searchResults;
    private List<CleanedNote> rankedResults;
    private String finalResponse;

    public AgentState() {
        this.messages = new ArrayList<>();
        this.missingInfo = new ArrayList<>();
        this.searchResults = new ArrayList<>();
        this.rankedResults = new ArrayList<>();
        this.decisionStage = DecisionStage.CLARIFY;
        this.intent = "";
    }

    public static Builder builder() {
        return new Builder();
    }

    public record Message(String role, String content) {}

    public enum DecisionStage { CLARIFY, SEARCH, COMPARE, RECOMMEND }

    public record UserProfile(
        String userId,
        List<String> preferredDestinations,
        String budgetTier,
        List<String> travelStyle,
        String typicalDuration,
        String companion
    ) {
        public String toPromptFragment() {
            StringBuilder sb = new StringBuilder();
            if (preferredDestinations != null && !preferredDestinations.isEmpty())
                sb.append("偏好目的地: ").append(String.join("、", preferredDestinations)).append(" ");
            if (budgetTier != null) sb.append("预算: ").append(budgetTier).append(" ");
            if (travelStyle != null && !travelStyle.isEmpty())
                sb.append("风格: ").append(String.join("、", travelStyle)).append(" ");
            if (typicalDuration != null) sb.append("天数: ").append(typicalDuration).append(" ");
            if (companion != null) sb.append("同行: ").append(companion);
            return sb.toString();
        }
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }
    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public DecisionStage getDecisionStage() { return decisionStage; }
    public void setDecisionStage(DecisionStage decisionStage) { this.decisionStage = decisionStage; }
    public UserProfile getUserProfile() { return userProfile; }
    public void setUserProfile(UserProfile userProfile) { this.userProfile = userProfile; }
    public List<String> getMissingInfo() { return missingInfo; }
    public void setMissingInfo(List<String> missingInfo) { this.missingInfo = missingInfo; }
    public List<CleanedNote> getSearchResults() { return searchResults; }
    public void setSearchResults(List<CleanedNote> searchResults) { this.searchResults = searchResults; }
    public List<CleanedNote> getRankedResults() { return rankedResults; }
    public void setRankedResults(List<CleanedNote> rankedResults) { this.rankedResults = rankedResults; }
    public String getFinalResponse() { return finalResponse; }
    public void setFinalResponse(String finalResponse) { this.finalResponse = finalResponse; }

    public static class Builder {
        private final AgentState state = new AgentState();
        public Builder userId(String v) { state.userId = v; return this; }
        public Builder sessionId(String v) { state.sessionId = v; return this; }
        public Builder messages(List<Message> v) { state.messages = v; return this; }
        public Builder userProfile(UserProfile v) { state.userProfile = v; return this; }
        public AgentState build() { return state; }
    }
}
