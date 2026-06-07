package askred.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class NoteLoader {

    private static final ObjectMapper mapper = new ObjectMapper();

    public List<RawNote> load(Path jsonlPath) throws IOException {
        List<RawNote> notes = new ArrayList<>();
        try (Stream<String> lines = Files.lines(jsonlPath)) {
            lines.forEach(line -> {
                try {
                    RawNote note = mapper.readValue(line, RawNote.class);
                    if (note.description() != null && !note.description().isBlank()) {
                        notes.add(note);
                    }
                } catch (IOException e) {
                    System.err.println("Skip malformed line: " + e.getMessage());
                }
            });
        }
        return notes;
    }
}
