package askred.agent;
import askred.pipeline.CleanedNote;
import java.util.*;
public class AskRedState {
    private String userId; private String sessionId; private List<Message> messages; private int turnCount;
    private String intent; private DecisionStage decisionStage; private UserProfile userProfile;
    private List<String> missingInfo; private List<CleanedNote> searchResults; private List<CleanedNote> rankedResults; private String finalResponse;
    public AskRedState() { this.messages=new ArrayList<>(); this.missingInfo=new ArrayList<>(); this.searchResults=new ArrayList<>(); this.rankedResults=new ArrayList<>(); this.decisionStage=DecisionStage.CLARIFY; this.intent=""; }
    public static Builder builder() { return new Builder(); }
    public record Message(String role, String content) {}
    public enum DecisionStage { CLARIFY, SEARCH, COMPARE, RECOMMEND }
    public record UserProfile(String userId, List<String> preferredDestinations, String budgetTier, List<String> travelStyle, String typicalDuration, String companion) {
        public String toPromptFragment() { StringBuilder sb=new StringBuilder(); if(preferredDestinations!=null&&!preferredDestinations.isEmpty()) sb.append("偏好目的地: ").append(String.join("、",preferredDestinations)).append(" "); if(budgetTier!=null) sb.append("预算: ").append(budgetTier).append(" "); if(travelStyle!=null&&!travelStyle.isEmpty()) sb.append("风格: ").append(String.join("、",travelStyle)).append(" "); if(typicalDuration!=null) sb.append("天数: ").append(typicalDuration).append(" "); if(companion!=null) sb.append("同行: ").append(companion); return sb.toString(); } }
    public String getUserId() { return userId; } public void setUserId(String v) { this.userId=v; }
    public String getSessionId() { return sessionId; } public void setSessionId(String v) { this.sessionId=v; }
    public List<Message> getMessages() { return messages; } public void setMessages(List<Message> v) { this.messages=v; }
    public int getTurnCount() { return turnCount; } public void setTurnCount(int v) { this.turnCount=v; }
    public String getIntent() { return intent; } public void setIntent(String v) { this.intent=v; }
    public DecisionStage getDecisionStage() { return decisionStage; } public void setDecisionStage(DecisionStage v) { this.decisionStage=v; }
    public UserProfile getUserProfile() { return userProfile; } public void setUserProfile(UserProfile v) { this.userProfile=v; }
    public List<String> getMissingInfo() { return missingInfo; } public void setMissingInfo(List<String> v) { this.missingInfo=v; }
    public List<CleanedNote> getSearchResults() { return searchResults; } public void setSearchResults(List<CleanedNote> v) { this.searchResults=v; }
    public List<CleanedNote> getRankedResults() { return rankedResults; } public void setRankedResults(List<CleanedNote> v) { this.rankedResults=v; }
    public String getFinalResponse() { return finalResponse; } public void setFinalResponse(String v) { this.finalResponse=v; }
    public static class Builder { private final AskRedState s=new AskRedState(); public Builder userId(String v) { s.userId=v; return this; } public Builder sessionId(String v) { s.sessionId=v; return this; } public Builder messages(List<Message> v) { s.messages=v; return this; } public Builder userProfile(UserProfile v) { s.userProfile=v; return this; } public AskRedState build() { return s; } }
}