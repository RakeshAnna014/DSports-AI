package com.dsports.cricket.vectors;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed vector files are exactly what the Java engine produces today. If a change to the engine alters any outcome this fails, naming the files,
 * until someone bumps {@code MatchScoring.ENGINE_VERSION}, regenerates ({@link EngineVectorExporter}) and commits the diff, where a reviewer sees which rules changed.
 */
class EngineVectorsAreCurrentTest {

    @Test
    void everyCommittedFileIsWhatTheEngineProducesNow() throws Exception {
        var expected = new java.util.LinkedHashMap<>(VectorCorpus.build());
        expected.putAll(VectorCorpus.serverSamples());
        var root = VectorCorpus.folder();
        var different = new ArrayList<String>();
        for (var e : expected.entrySet()) {
            var file = root.resolve(e.getKey());
            if (!Files.exists(file)) {
                different.add(e.getKey() + " (missing)");
            } else if (!java.util.Arrays.equals(Files.readAllBytes(file), e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                different.add(e.getKey());
            }
        }
        assertThat(different).as("vector files whose bytes differ from the engine's output (regenerate with -Dvectors.write=true, bump ENGINE_VERSION if a rule changed, review the diff; "
            + "if every file differs, check that .gitattributes keeps testdata/cricket-engine unconverted)").isEmpty();
    }

    @Test
    void thereAreNoStrayFilesInTheFolders() throws Exception {
        var expected = new java.util.HashSet<>(VectorCorpus.build().keySet());
        expected.addAll(VectorCorpus.serverSamples().keySet());
        var root = VectorCorpus.folder();
        var onDisk = new ArrayList<String>();
        for (var dir : new String[] {"golden", "random", "server"}) {
            try (Stream<java.nio.file.Path> list = Files.list(root.resolve(dir))) {
                list.forEach(f -> onDisk.add(dir + "/" + f.getFileName()));
            }
        }
        assertThat(onDisk).as("files in golden/ and random/ that the generator does not produce").allMatch(expected::contains);
    }
}
