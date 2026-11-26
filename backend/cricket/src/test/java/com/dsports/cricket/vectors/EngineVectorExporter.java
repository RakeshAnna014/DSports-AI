package com.dsports.cricket.vectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes {@code testdata/cricket-engine}. Not a test: it only runs when asked, after a deliberate change to the rules, and the result is reviewed as a diff.
 *
 * <pre>mvn -pl cricket test -Dtest=EngineVectorExporter -Dvectors.write=true</pre>
 *
 * Remember to bump {@code MatchScoring.ENGINE_VERSION} first when a rule changes an outcome.
 */
@EnabledIfSystemProperty(named = "vectors.write", matches = "true")
class EngineVectorExporter {

    @Test
    void writesTheVectorFiles() throws Exception {
        var files = VectorCorpus.build();
        VectorCorpus.write(files);
        assertThat(VectorCorpus.folder().resolve("manifest.json")).exists();
    }
}
