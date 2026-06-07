package askred.pipeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 数据管道编排器：加载 → 质量画像 → 清洗。
 * 独立于 Spring Boot，可直接 main() 运行验证 pipeline。
 */
public class PipelineRunner {

    public static void main(String[] args) throws IOException {
        Path dataPath = Path.of("data/raw/notes.jsonl");
        if (args.length > 0) {
            dataPath = Path.of(args[0]);
        }

        NoteLoader loader = new NoteLoader();
        List<RawNote> rawNotes = loader.load(dataPath);
        System.out.println("Step 1 - Loaded: " + rawNotes.size() + " notes");

        QualityProfiler profiler = new QualityProfiler();
        QualityProfiler.QualityReport report = profiler.profile(rawNotes);
        System.out.println("Step 2 - Quality Report:");
        System.out.println("  Total: " + report.totalNotes());
        System.out.println("  Text Length Distribution: " + report.textLengthDistribution());
        System.out.println("  Avg Chinese Ratio: " + String.format("%.2f", report.chineseRatioAvg()));
        System.out.println("  Notes with no Chinese: " + report.notesWithNoChinese());
        System.out.println("  Destination Distribution: " + report.destinationDistribution());
        System.out.println("  Keyword Folder Distribution: " + report.keywordFolderDistribution());
        System.out.println("  Duplicate Candidates: " + report.duplicateCandidates());
        System.out.println("  Total Images: " + report.totalImages());

        NoteCleaner cleaner = NoteCleaner.withDefaults();
        List<CleanedNote> cleaned = cleaner.clean(rawNotes);
        System.out.println("Step 3 - Cleaned: " + cleaned.size() + " notes (filtered " +
            (rawNotes.size() - cleaned.size()) + ")");
        System.out.println("  Sample titles:");
        cleaned.stream().limit(5).forEach(n ->
            System.out.println("    [" + n.destination() + "] " + n.originalTitle().substring(0,
                Math.min(40, n.originalTitle().length())) + "...")
        );
    }
}
