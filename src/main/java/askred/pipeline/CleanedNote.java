package askred.pipeline;

import java.util.List;

/**
 * 清洗后的笔记。比 RawNote 更结构化。
 * Step 3 (NoteCleaner) 填充基础字段，Step 4 (NoteEnricher) 通过 LLM 填充 summary/priceHint 等富化字段。
 */
public record CleanedNote(
    String noteId,
    String originalTitle,
    String originalDesc,
    String summary,
    String destination,
    List<String> tags,
    String priceHint,
    String durationHint,
    List<String> suitableFor,
    String season,
    List<String> highlights,
    int likes,
    int collects,
    int comments,
    String publishDate,
    double qualityScore
) {}
