package askred.agent;

import askred.llm.LlmConfig.MeituanEmbedder;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import askred.pipeline.CleanedNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final ElasticsearchClient esClient;
    private final MeituanEmbedder embedder;

    public HybridRetriever(ElasticsearchClient esClient, MeituanEmbedder embedder) {
        this.esClient = esClient;
        this.embedder = embedder;
    }

    public void retrieve(AgentState state) throws IOException {
        String userMsg = state.getMessages().get(state.getMessages().size() - 1).content();
        String searchQuery = userMsg.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();

        // 生成 query embedding
        float[] queryVector = null;
        try {
            queryVector = embedder.embed(searchQuery);
        } catch (Exception e) {
            log.warn("  [RETRIEVE] embedding failed, fallback to text-only: {}", e.getMessage());
        }

        // 构建混合检索：BM25 + kNN 向量 + RRF 融合
        final float[] qv = queryVector;  // effectively final for lambda
        float[] finalQueryVector = queryVector;
        var response = esClient.search(s -> {
            var builder = s.index("xhs_notes").size(10);

            // kNN 向量检索（如果 embedding 可用）
            if (qv != null) {
                List<Float> vec = new ArrayList<>(qv.length);
                for (float v : qv) vec.add(v);
                builder = builder.knn(k -> k
                    .field("embedding")
                    .queryVector(vec)
                    .k(20)
                    .numCandidates(50)
                );
            }

            // BM25 文本检索
            builder = builder.query(q -> q
                .match(m -> m.field("textForEmbedding").query(searchQuery))
            );

            // RRF 融合排序
            if (finalQueryVector != null) {
                builder = builder.rank(rk -> rk.rrf(r -> r));
            }

            return builder;
        }, Map.class);

        List<CleanedNote> results = new ArrayList<>();
        for (var hit : response.hits().hits()) {
            var source = hit.source();
            if (source == null) continue;
            results.add(mapToCleanedNote(source));
        }
        log.info("  [RETRIEVE] hybrid (embed={}, rrf={}) -> {} results",
            qv != null, qv != null, results.size());
        state.setSearchResults(results);
    }

    private CleanedNote mapToCleanedNote(Map<String, Object> source) {
        return new CleanedNote(
            (String) source.get("noteId"),
            (String) source.getOrDefault("textForDisplay", ""),
            (String) source.getOrDefault("textForDisplay", ""),
            (String) source.getOrDefault("textForEmbedding", ""),
            (String) source.getOrDefault("destination", ""),
            safeList(source.get("tags")),
            (String) source.getOrDefault("priceHint", null),
            (String) source.getOrDefault("durationHint", null),
            safeList(source.get("suitableFor")),
            (String) source.getOrDefault("season", null),
            safeList(source.get("highlights")),
            toInt(source.get("likes")),
            toInt(source.get("collects")),
            toInt(source.get("comments")),
            (String) source.getOrDefault("publishDate", ""),
            toDouble(source.get("qualityScore"))
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> safeList(Object obj) {
        if (obj instanceof List) return (List<String>) obj;
        return List.of();
    }

    private int toInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        return 0;
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        return 0.0;
    }
}
