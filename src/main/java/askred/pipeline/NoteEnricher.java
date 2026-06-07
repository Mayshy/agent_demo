package askred.pipeline;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@Service
public class NoteEnricher {

    private static final int CHAT_MAX_RETRIES = 3;
    private static final long CHAT_BACKOFF_BASE_MS = 2000;
    private static final int DEFAULT_PARALLELISM = 4;

    private final ChatModel normalModel;

    public NoteEnricher(@Qualifier("normalModel") ChatModel normalModel) {
        this.normalModel = normalModel;
    }

    public List<CleanedNote> enrich(List<CleanedNote> notes) {
        List<CleanedNote> enriched = new ArrayList<>();
        for (int i = 0; i < notes.size(); i++) {
            CleanedNote result = enrichWithRetry(notes.get(i));
            enriched.add(result != null ? result : notes.get(i));
            if ((i + 1) % 10 == 0) {
                System.out.println("Enriched " + (i + 1) + "/" + notes.size());
            }
        }
        return enriched;
    }

    public List<CleanedNote> enrichParallel(List<CleanedNote> notes, int parallelism) {
        int effectiveParallelism = Math.max(1, Math.min(parallelism, DEFAULT_PARALLELISM));
        AtomicInteger done = new AtomicInteger(0);
        int total = notes.size();

        ForkJoinPool pool = new ForkJoinPool(effectiveParallelism);
        try {
            return pool.submit(() -> IntStream.range(0, total).parallel()
                .mapToObj(i -> {
                    CleanedNote result = enrichWithRetry(notes.get(i));
                    int n = done.incrementAndGet();
                    if (n % 50 == 0) {
                        System.out.println("Enriched " + n + "/" + total);
                    }
                    return result != null ? result : notes.get(i);
                })
                .toList()
            ).get();
        } catch (Exception e) {
            System.err.println("Parallel enrich failed, falling back to serial: " + e.getMessage());
            return enrich(notes);
        } finally {
            pool.shutdown();
        }
    }

    private CleanedNote enrichWithRetry(CleanedNote note) {
        for (int attempt = 0; attempt <= CHAT_MAX_RETRIES; attempt++) {
            try {
                return enrichOne(note);
            } catch (RuntimeException e) {
                if (isRecoverable(e) && attempt < CHAT_MAX_RETRIES) {
                    long backoff = CHAT_BACKOFF_BASE_MS * (1L << attempt);
                    System.err.println("Chat model error (attempt " + (attempt + 1) + "/"
                        + (CHAT_MAX_RETRIES + 1) + "), retry after " + backoff + "ms: " + e.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    System.err.println("Chat model failed after " + (attempt + 1)
                        + " attempts, skipping note: " + e.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    private boolean isRecoverable(RuntimeException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.contains("GOAWAY") || msg.contains("timeout")
            || msg.contains("IOException") || msg.contains("connection reset")) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            String causeMsg = cause.getMessage() != null ? cause.getMessage() : "";
            if (causeMsg.contains("GOAWAY") || causeMsg.contains("connection")
                || causeMsg.contains("timeout") || causeMsg.contains("reset")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    CleanedNote enrichOne(CleanedNote note) {
        String prompt = """
            提取以下旅行笔记的结构化信息，返回严格JSON（不要任何其他文字）：
            {
              "summary": "50字以内的结构化摘要（包含地点、亮点、预算）",
              "priceHint": "budget/mid/luxury/免费 之一",
              "durationHint": "半天/1天/2-3天/3-5天/一周 之一",
              "suitableFor": ["独自","情侣","家庭","朋友","摄影","美食","冒险","文化" 中选1-3个],
              "season": "推荐季节（如'3-5月'）",
              "highlights": ["2-3个亮点短语"]
            }

            笔记内容：%s
            """.formatted(note.originalDesc());

        String json = normalModel.chat(prompt).trim();
        if (json.contains("{")) {
            json = json.substring(json.indexOf("{"), json.lastIndexOf("}") + 1);
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);

            return new CleanedNote(
                note.noteId(), note.originalTitle(), note.originalDesc(),
                node.has("summary") ? node.get("summary").asText() : null,
                note.destination(), note.tags(),
                node.has("priceHint") ? node.get("priceHint").asText() : null,
                node.has("durationHint") ? node.get("durationHint").asText() : null,
                jsonArrayToList(node, "suitableFor"),
                node.has("season") ? node.get("season").asText() : null,
                jsonArrayToList(node, "highlights"),
                note.likes(), note.collects(), note.comments(),
                note.publishDate(), note.qualityScore()
            );
        } catch (Exception e) {
            return note;
        }
    }

    private List<String> jsonArrayToList(com.fasterxml.jackson.databind.JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        if (node.has(field) && node.get(field).isArray()) {
            node.get(field).forEach(n -> list.add(n.asText()));
        }
        return list;
    }
}
