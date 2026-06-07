package askred.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 原始 XHS Travel Photos JSONL 中的一条笔记记录。
 * 字段名与原数据集中的 JSON key 完全对齐（snake_case → camelCase 自动映射）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawNote(
    @JsonProperty("note_id") String noteId,
    String title,
    String description,
    @JsonProperty("ip_location") String ipLocation,
    List<String> tags,
    @JsonProperty("liked_count") int likedCount,
    @JsonProperty("collected_count") int collectedCount,
    @JsonProperty("comment_count") int commentCount,
    @JsonProperty("share_count") int shareCount,
    @JsonProperty("posted_at") String postedAt,
    @JsonProperty("last_updated_at") String lastUpdatedAt,
    @JsonProperty("note_url") String noteUrl,
    @JsonProperty("source_keyword") String sourceKeyword,
    @JsonProperty("keyword_folder") String keywordFolder,
    @JsonProperty("total_images") int totalImages,
    @JsonProperty("user_id") String userId,
    @JsonProperty("user_nickname") String userNickname,
    @JsonProperty("user_avatar_url") String userAvatarUrl
) {
    /**
     * 从 ipLocation 推断目的地。原始数据中 ip_location 是"巴厘岛"、"云南"等。
     */
    public String destination() {
        return ipLocation != null && !ipLocation.isBlank() ? ipLocation : "未知";
    }
}
