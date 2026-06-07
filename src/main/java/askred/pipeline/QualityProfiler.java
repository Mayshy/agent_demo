package askred.pipeline;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Step 2: 不修改数据，只输出质量统计，指导后续清洗策略。
 */
public class QualityProfiler {

    public record QualityReport(
        int totalNotes,
        Map<String, Integer> textLengthDistribution,
        double chineseRatioAvg,
        int notesWithNoChinese,
        Map<String, Long> destinationDistribution,
        Map<String, Long> keywordFolderDistribution,
        Map<Integer, Long> likesDistribution,
        int duplicateCandidates,
        long totalImages
    ) {}

    public QualityReport profile(List<RawNote> notes) {
        int total = notes.size();

        var lenDist = buildLengthDistribution(notes);
        double chineseAvg = notes.stream().mapToDouble(this::chineseRatio).average().orElse(0);
        int noChinese = (int) notes.stream().filter(n -> chineseRatio(n) < 0.1).count();
        var destDist = notes.stream()
            .collect(Collectors.groupingBy(RawNote::destination, Collectors.counting()));
        var folderDist = notes.stream()
            .filter(n -> n.keywordFolder() != null)
            .collect(Collectors.groupingBy(RawNote::keywordFolder, Collectors.counting()));
        var likesDist = buildLikesDistribution(notes);

        // 简单去重检测：标题相似的笔记
        int dupCandidates = findDuplicateCandidates(notes);
        long totalImages = notes.stream().mapToLong(RawNote::totalImages).sum();

        return new QualityReport(total, lenDist, chineseAvg, noChinese,
            destDist, folderDist, likesDist, dupCandidates, totalImages);
    }

    private Map<String, Integer> buildLengthDistribution(List<RawNote> notes) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        dist.put("<30字", 0);
        dist.put("30-100", 0);
        dist.put("100-300", 0);
        dist.put("300-1000", 0);
        dist.put(">1000字", 0);

        for (var n : notes) {
            int len = n.description() != null ? n.description().length() : 0;
            if (len < 30) dist.merge("<30字", 1, Integer::sum);
            else if (len < 100) dist.merge("30-100", 1, Integer::sum);
            else if (len < 300) dist.merge("100-300", 1, Integer::sum);
            else if (len < 1000) dist.merge("300-1000", 1, Integer::sum);
            else dist.merge(">1000字", 1, Integer::sum);
        }
        return dist;
    }

    private double chineseRatio(RawNote note) {
        String text = note.description();
        if (text == null || text.isEmpty()) return 0;
        long chinese = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        return (double) chinese / text.length();
    }

    private Map<Integer, Long> buildLikesDistribution(List<RawNote> notes) {
        int[] buckets = {0, 100, 500, 1000, 5000};
        var dist = new LinkedHashMap<Integer, Long>();
        var likes = notes.stream().mapToInt(RawNote::likedCount).sorted().toArray();
        for (int i = 0; i < buckets.length; i++) {
            int low = buckets[i];
            int high = i < buckets.length - 1 ? buckets[i + 1] : Integer.MAX_VALUE;
            final int lo = low, hi = high;
            long count = notes.stream().filter(n -> n.likedCount() >= lo && n.likedCount() < hi).count();
            dist.put(low, count);
        }
        return dist;
    }

    private int findDuplicateCandidates(List<RawNote> notes) {
        Set<String> seen = new HashSet<>();
        int dup = 0;
        for (var n : notes) {
            String normalized = normalizeForDedup(n.title());
            if (!seen.add(normalized)) dup++;
        }
        return dup;
    }

    private static String normalizeForDedup(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}【】《》（）]", "").toLowerCase().trim();
    }
}
