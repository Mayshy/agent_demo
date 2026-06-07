package askred;

import askred.pipeline.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;

@SpringBootApplication
public class AskRedApplication {

    static {
        System.setProperty("jdk.httpclient.keepalive.timeout", "60");
        System.setProperty("jdk.httpclient.connectionPoolSize", "10");
        System.setProperty("jdk.httpclient.websocket.writeBufferSize", "1024");
    }

    public static void main(String[] args) {
        SpringApplication.run(AskRedApplication.class, args);
    }

    @Bean
    CommandLineRunner importData(EsIndexer esIndexer, NoteEnricher enricher,
                                  ElasticsearchClient esClient) {
        return args -> {
            Path dataPath = Path.of("data/raw/notes.jsonl");
            if (!dataPath.toFile().exists()) {
                System.out.println("No data file, skip import");
                return;
            }

            long existingDocs = esClient.count(c -> c.index("xhs_notes")).count();
            long minExpected = 2000L;
            if (existingDocs >= minExpected) {
                System.out.println("ES already has " + existingDocs
                    + " docs (>= " + minExpected + "), skip import. "
                    + "Delete index to force reload: curl -X DELETE localhost:9200/xhs_notes");
                return;
            }
            if (existingDocs > 0) {
                System.out.println("ES has " + existingDocs + " docs (< " + minExpected
                    + "), re-importing...");
            }

            NoteLoader loader = new NoteLoader();
            List<RawNote> rawNotes = loader.load(dataPath);
            System.out.println("Loaded " + rawNotes.size() + " raw notes");

            QualityProfiler profiler = new QualityProfiler();
            QualityProfiler.QualityReport report = profiler.profile(rawNotes);
            System.out.println("Quality: " + report.totalNotes() + " notes, "
                + report.duplicateCandidates() + " dup candidates");

            NoteCleaner cleaner = NoteCleaner.withDefaults();
            List<CleanedNote> cleaned = cleaner.clean(rawNotes);
            System.out.println("Cleaned: " + cleaned.size() + " notes (filtered "
                + (rawNotes.size() - cleaned.size()) + ")");

            boolean skipEnrich = "true".equals(System.getenv("SKIP_ENRICH"));
            if (!skipEnrich) {
                List<CleanedNote> enriched = enricher.enrichParallel(cleaned, 10);
                System.out.println("Enriched: " + enriched.size() + " notes");
                cleaned = enriched;
            }

            int indexed = esIndexer.indexNotes(cleaned);
            System.out.println("Indexed: " + indexed + " notes to ES");
        };
    }
}
