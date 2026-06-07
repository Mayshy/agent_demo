package askred.pipeline;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class NoteCleaner {

    private final int minDescLength;
    private final double minChineseRatio;

    public NoteCleaner(int minDescLength, double minChineseRatio) {
        this.minDescLength = minDescLength;
        this.minChineseRatio = minChineseRatio;
    }

    public static NoteCleaner withDefaults() {
        return new NoteCleaner(50, 0.3);
    }

    public List<CleanedNote> clean(List<RawNote> rawNotes) {
        List<RawNote> filtered = rawNotes.stream()
            .filter(n -> n.description() != null)
            .filter(n -> n.description().length() >= minDescLength)
            .filter(n -> chineseRatio(n) >= minChineseRatio)
            .toList();

        List<RawNote> deduped = deduplicateByTitle(filtered);

        return deduped.stream().map(this::toCleanedNote).toList();
    }

    private List<RawNote> deduplicateByTitle(List<RawNote> notes) {
        Map<String, RawNote> best = new LinkedHashMap<>();
        for (var note : notes) {
            String key = normalizeTitle(note.title());
            RawNote existing = best.get(key);
            if (existing == null || note.likedCount() > existing.likedCount()) {
                best.put(key, note);
            }
        }
        return new ArrayList<>(best.values());
    }

    private CleanedNote toCleanedNote(RawNote raw) {
        return new CleanedNote(
            raw.noteId(),
            raw.title(),
            raw.description(),
            null,
            raw.destination(),
            raw.tags() != null ? raw.tags() : List.of(),
            null, null, null, null, null,
            raw.likedCount(),
            raw.collectedCount(),
            raw.commentCount(),
            raw.postedAt(),
            computeQualityScore(raw)
        );
    }

    private double computeQualityScore(RawNote raw) {
        double engagement = Math.log1p(raw.likedCount() + raw.collectedCount() * 2 + raw.commentCount() * 3);
        double textRichness = Math.min(raw.description().length() / 500.0, 1.0);
        double imageBonus = Math.min(raw.totalImages() / 5.0, 1.0);
        return 0.3 * engagement / 10 + 0.4 * textRichness + 0.3 * imageBonus;
    }

    private double chineseRatio(RawNote note) {
        String text = note.description();
        if (text == null || text.isEmpty()) return 0;
        long chinese = text.codePoints()
            .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
            .count();
        return (double) chinese / text.length();
    }

    private static String normalizeTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("[\\s\\p{Punct}【】《》（）\\uD83C-\\uDBFF\\uDC00-\\uDFFF]", "")
            .toLowerCase().trim();
    }
}
