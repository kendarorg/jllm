package org.kendar.llm.js;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GraalProbeTest {

    /** A class loader that does not see the classes whose names start with one of the prefixes. */
    static final class Hiding extends ClassLoader {
        private final String[] hidden;

        Hiding(String... hidden) {
            super(GraalProbeTest.class.getClassLoader());
            this.hidden = hidden;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String h : hidden) if (name.startsWith(h)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void theRealJarsGiveAUsableProbe() {
        GraalProbe.Result r = GraalProbe.probe();

        assertTrue(r.graal() && r.js() && r.regex(), r.toString());
        assertTrue(r.usable());
        assertNull(r.unusableReason());
        assertFalse(r.version().isEmpty());
        assertTrue(r.summary().contains("GraalJS"), r.summary());
        if (!r.isolate()) assertFalse(r.isolateNote().isEmpty(), "a missing isolate has a reason");
    }

    @Test
    void theIsolateIsVerifiedByBuildingOne() {
        GraalProbe.Result r = GraalProbe.probe();

        // the probe's answer must agree with what the isolated profile does
        if (r.isolate()) {
            try (JsSandbox box = JsTest.box("isolated")) {
                assertTrue(box.run("return 1;").ok());
            }
        } else {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> JsTest.box("isolated"));
            assertTrue(e.getMessage().contains("isolate"), e.getMessage());
        }
    }

    @Test
    void aLoaderThatHidesGraalJsGivesAbsentWithTheReason() {
        GraalProbe.Result r = GraalProbe.probe(new Hiding("org.graalvm.", "com.oracle.truffle."));

        assertFalse(r.graal());
        assertFalse(r.usable());
        assertTrue(r.unusableReason().contains("polyglot"), r.unusableReason());
        assertEquals(GraalEdition.UNKNOWN, r.edition());
    }

    @Test
    void aMissingRegexLanguageDisablesTheToolNamingTheRegexJar() {
        GraalProbe.Result r = GraalProbe.probe(new Hiding("com.oracle.truffle.regex."));

        assertTrue(r.graal() && r.js());
        assertFalse(r.regex());
        assertFalse(r.usable());
        assertTrue(r.unusableReason().contains("regex"), r.unusableReason());
    }

    @Test
    void aMissingJsLanguageIsNamed() {
        GraalProbe.Result r = GraalProbe.probe(new Hiding("com.oracle.truffle.js."));

        assertFalse(r.usable());
        assertTrue(r.unusableReason().contains("js-language"), r.unusableReason());
    }

    // ---- edition detection (diagnostics only) ---------------------------------------------------------------

    private static ClassLoader withMavenMetadata(Path dir, String groupAndArtifact) throws Exception {
        Path meta = dir.resolve("META-INF/maven/" + groupAndArtifact);
        Files.createDirectories(meta);
        Files.writeString(meta.resolve("pom.properties"), "version=25.5.5.0.3\n");
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}, null);
    }

    @Test
    void communityMavenMetadataGivesCommunity(@TempDir Path dir) throws Exception {
        ClassLoader l = withMavenMetadata(dir, "org.graalvm.polyglot/js-isolate-community");

        assertEquals(GraalEdition.COMMUNITY, GraalEdition.detect(l, "", null));
    }

    @Test
    void oracleMavenMetadataGivesOracle(@TempDir Path dir) throws Exception {
        ClassLoader l = withMavenMetadata(dir, "org.graalvm.js/js-isolate-linux-amd64");

        assertEquals(GraalEdition.ORACLE, GraalEdition.detect(l, "", null));
    }

    @Test
    void noMetadataAndNoHintsIsUnknown(@TempDir Path dir) throws Exception {
        ClassLoader l = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null);

        assertEquals(GraalEdition.UNKNOWN, GraalEdition.detect(l, "Interpreted", dir));
    }

    @Test
    void theImplementationNameIsAHintWhenThereIsNoMetadata(@TempDir Path dir) throws Exception {
        ClassLoader l = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null);

        assertEquals(GraalEdition.COMMUNITY, GraalEdition.detect(l, "GraalVM CE Isolated", null));
        assertEquals(GraalEdition.ORACLE, GraalEdition.detect(l, "Oracle GraalVM", null));
    }

    @Test
    void theReleaseManifestWinsOverMetadata(@TempDir Path dir) throws Exception {
        Path meta = Files.createDirectories(dir.resolve("meta"));
        ClassLoader l = withMavenMetadata(meta, "org.graalvm.polyglot/js-isolate-community");
        Path lib = Files.createDirectories(dir.resolve("lib-ext"));
        Files.writeString(lib.resolve("graaljs-oracle.manifest"), "org.graalvm.js:js-language 25.5.5.0.3 abc\n");

        assertEquals(GraalEdition.ORACLE, GraalEdition.detect(l, "GraalVM CE", lib));
    }

    @Test
    void theEditionNeverChangesWhichProfileIsChosen() {
        GraalProbe.Result base = GraalProbe.probe();
        JsSettings s = JsSettings.defaults();
        String name = null;
        for (GraalEdition e : GraalEdition.values()) {
            GraalProbe.Result r = new GraalProbe.Result(true, true, true, base.sandboxLimits(), base.isolate(),
                    base.isolateNote(), base.implementation(), base.version(), e);
            JsProfiles.Selection sel = JsProfiles.select(s, r);
            try {
                assertNotNull(sel.profile(), sel.reason());
                if (name == null) name = sel.profile().name();
                assertEquals(name, sel.profile().name(), "edition " + e);
            } finally {
                if (sel.profile() != null) sel.profile().close();
            }
        }
    }
}
