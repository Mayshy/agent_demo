package askred.pipeline;

import askred.llm.LlmConfig.MeituanEmbedder;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingRequest;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
public class EsIndexer {

    private static final int BATCH_SIZE = 50;

    private final ElasticsearchClient esClient;
    private final MeituanEmbedder embedder;

    public EsIndexer(ElasticsearchClient esClient, MeituanEmbedder embedder) {
        this.esClient = esClient;
        this.embedder = embedder;
    }

    private void ensureIndex() {
        int expectedDims = embedder.dimension();
        try {
            boolean exists = esClient.indices().exists(e -> e.index("xhs_notes")).value();
            if (!exists) {
                createIndex(expectedDims);
                return;
            }
            int currentDims = getEmbeddingDims();
            if (currentDims == -1) {
                // No embedding field or can't read — assume it's fine (dynamic mapping)
                return;
            }
            if (currentDims != expectedDims) {
                System.out.println("Index xhs_notes has embedding dims=" + currentDims
                    + ", but embedder returns dims=" + expectedDims
                    + ". Recreating index with correct dims...");
                esClient.indices().delete(d -> d.index("xhs_notes"));
                createIndex(expectedDims);
            }
        } catch (IOException e) {
            System.err.println("ensureIndex failed, will attempt indexing anyway: " + e.getMessage());
        }
    }

    private int getEmbeddingDims() throws IOException {
        try {
            var response = esClient.indices().getMapping(
                GetMappingRequest.of(m -> m.index("xhs_notes")));
            IndexMappingRecord record = response.get("xhs_notes");
            if (record == null || record.mappings() == null) return -1;
            TypeMapping mappings = record.mappings();
            if (mappings.properties() == null) return -1;
            Property embeddingProp = mappings.properties().get("embedding");
            if (embeddingProp == null || !embeddingProp.isDenseVector()) return -1;
            DenseVectorProperty dv = embeddingProp.denseVector();
            return dv.dims() != null ? dv.dims() : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private void createIndex(int dims) throws IOException {
        System.out.println("Creating index xhs_notes with embedding dims=" + dims);
        esClient.indices().create(CreateIndexRequest.of(c -> c
            .index("xhs_notes")
            .mappings(m -> m
                .properties("embedding", Property.of(p -> p
                    .denseVector(DenseVectorProperty.of(dv -> dv
                        .dims(dims)
                        .similarity("cosine")
                    ))
                ))
                .properties("noteId", Property.of(p -> p.keyword(k -> k)))
                .properties("textForEmbedding", Property.of(p -> p.text(t -> t)))
                .properties("textForDisplay", Property.of(p -> p.text(t -> t)))
                .properties("destination", Property.of(p -> p.keyword(k -> k)))
                .properties("tags", Property.of(p -> p.keyword(k -> k)))
                .properties("priceHint", Property.of(p -> p.keyword(k -> k)))
                .properties("suitableFor", Property.of(p -> p.keyword(k -> k)))
                .properties("likes", Property.of(p -> p.integer(i -> i)))
                .properties("qualityScore", Property.of(p -> p.float_(f -> f)))
            )
        ));
    }

    public int indexNotes(List<CleanedNote> notes) throws IOException {
        ensureIndex();

        Set<String> existingIds = getExistingNoteIds();
        List<CleanedNote> remaining = new ArrayList<>();

        for (var note : notes) {
            if (existingIds.contains(note.noteId())) continue;
            String embedText = note.summary() != null ? note.summary() : note.originalDesc();
            if (embedText == null || embedText.isBlank()) continue;
            remaining.add(note);
        }

        int skipped = notes.size() - remaining.size();
        if (skipped > 0) {
            System.out.println("Skipped " + skipped + " already-indexed notes, "
                + remaining.size() + " remaining");
        }
        if (remaining.isEmpty()) {
            System.out.println("All " + notes.size() + " notes already indexed");
            return 0;
        }

        int total = remaining.size();
        int indexed = 0;
        int failed = 0;
        List<BulkOperation> batch = new ArrayList<>();
        List<String> batchIds = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            CleanedNote note = remaining.get(i);
            String embedText = note.summary() != null ? note.summary() : note.originalDesc();
            String preview = truncate(note.originalDesc(), 30);

            float[] vector;
            try {
                vector = embedder.embed(embedText);
            } catch (RuntimeException e) {
                failed++;
                System.err.println("Embedding FAILED [" + (i + 1) + "/" + total + "] "
                    + note.noteId() + " " + preview + " — " + e.getMessage());
                continue;
            }
            System.out.println("Embedding OK [" + (i + 1) + "/" + total + "] "
                + note.noteId() + " — " + preview);

            Map<String, Object> doc = buildDoc(note, embedText, vector);
            batch.add(BulkOperation.of(b -> b.index(idx -> idx
                .index("xhs_notes")
                .id(note.noteId())
                .document(doc)
            )));
            batchIds.add(note.noteId());

            boolean lastBatch = (i == total - 1);
            if (batch.size() >= BATCH_SIZE || lastBatch) {
                FlushResult result = flushBatch(batch, batchIds);
                indexed += result.success;
                failed += result.errors;
                batch.clear();
                batchIds.clear();

                System.out.println("  → flush: " + result.success + " ok, "
                    + result.errors + " err  (total: " + indexed + "/" + total + ")");
            }
        }

        System.out.println("Indexing done: " + indexed + " indexed, "
            + failed + " failed, " + skipped + " skipped");
        return indexed;
    }

    private FlushResult flushBatch(List<BulkOperation> batch, List<String> batchIds) {
        try {
            BulkResponse response = esClient.bulk(BulkRequest.of(b -> b.operations(batch)));

            int success = 0;
            int errors = 0;

            for (BulkResponseItem item : response.items()) {
                ErrorCause error = item.error();
                if (error != null) {
                    errors++;
                    System.err.println("  ES reject [" + item.id() + "] "
                        + item.index() + "/" + error.type() + ": " + error.reason());
                } else {
                    success++;
                }
            }

            return new FlushResult(success, errors);
        } catch (Exception e) {
            System.err.println("  Bulk FAILED (batch " + batchIds.get(0) + ".."
                + batchIds.get(batchIds.size() - 1) + "): " + e.getMessage());
            // Don't throw — return as all errors so remaining batches still process
            return new FlushResult(0, batch.size());
        }
    }

    private Set<String> getExistingNoteIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try {
            var response = esClient.search(s -> s
                .index("xhs_notes")
                .query(q -> q.matchAll(m -> m))
                .source(src -> src.filter(f -> f.includes("noteId")))
                .size(10000),
                Map.class
            );
            for (Hit<Map> hit : response.hits().hits()) {
                Object noteId = hit.source() != null ? hit.source().get("noteId") : null;
                if (noteId != null) ids.add(noteId.toString());
                if (hit.id() != null && noteId == null) ids.add(hit.id());
            }
        } catch (IOException e) {
            System.err.println("Failed to query existing noteIds (index may not exist yet): "
                + e.getMessage());
        }
        return ids;
    }

    private Map<String, Object> buildDoc(CleanedNote note, String embedText, float[] vector) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("noteId", note.noteId());
        doc.put("textForEmbedding", embedText);
        doc.put("textForDisplay", note.originalDesc() != null ? note.originalDesc() : "");
        doc.put("embedding", toDoubleList(vector));
        doc.put("destination", note.destination());
        doc.put("tags", note.tags());
        doc.put("priceHint", note.priceHint() != null ? note.priceHint() : "");
        doc.put("suitableFor", note.suitableFor() != null ? note.suitableFor() : List.of());
        doc.put("likes", note.likes());
        doc.put("qualityScore", note.qualityScore());
        return doc;
    }

    private List<Double> toDoubleList(float[] array) {
        List<Double> list = new ArrayList<>(array.length);
        for (float v : array) list.add((double) v);
        return list;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "(empty)";
        String cleaned = s.replace("\n", " ").replace("\r", "");
        if (cleaned.length() <= maxLen) return cleaned;
        return cleaned.substring(0, maxLen - 3) + "...";
    }

    private record FlushResult(int success, int errors) {}
}
