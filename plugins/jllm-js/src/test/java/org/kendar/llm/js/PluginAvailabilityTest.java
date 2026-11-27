package org.kendar.llm.js;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.plugin.HarnessPlugin;
import org.kendar.llm.harness.plugin.PluginRegistrar;
import org.kendar.llm.harness.tools.HarnessTool;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The plugin starts without run_js when GraalJS, its regex language or the requested isolate is missing. */
class PluginAvailabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger pluginLog = (Logger) LoggerFactory.getLogger(JsPlugin.class);
    private Level before;

    @BeforeEach
    void captureLog() {
        before = pluginLog.getLevel();
        pluginLog.setLevel(Level.INFO);
        logged.start();
        pluginLog.addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        pluginLog.detachAppender(logged);
        pluginLog.setLevel(before);
    }

    /** A registrar that records the tools it is given. */
    private static PluginRegistrar recorder(List<HarnessTool> tools) {
        PluginRegistrar[] self = new PluginRegistrar[1];
        self[0] = (PluginRegistrar) Proxy.newProxyInstance(PluginRegistrar.class.getClassLoader(),
                new Class<?>[]{PluginRegistrar.class}, (proxy, method, args) -> {
                    if (method.getName().equals("tool")) tools.add((HarnessTool) args[0]);
                    return method.getReturnType() == PluginRegistrar.class ? self[0] : null;
                });
        return self[0];
    }

    private List<String> warnings() {
        return logged.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** Loads the plugin classes themselves through a loader that does not see GraalJS. */
    private static final class PluginLoader extends ClassLoader {
        PluginLoader() {
            super(PluginAvailabilityTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.graalvm.") || name.startsWith("com.oracle.truffle.")) {
                throw new ClassNotFoundException(name);
            }
            if (name.startsWith("org.kendar.llm.js.") && !name.contains("Test")) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (in == null) throw new ClassNotFoundException(name);
                            byte[] b = in.readAllBytes();
                            c = defineClass(name, b, 0, b.length);
                        } catch (java.io.IOException e) {
                            throw new ClassNotFoundException(name, e);
                        }
                    }
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void withoutGraalJsThePluginLoadsInstallsNothingAndWarnsOnce() throws Exception {
        Class<?> plugin = new PluginLoader().loadClass("org.kendar.llm.js.JsPlugin");
        assertNotSame(JsPlugin.class, plugin, "a separate copy, defined by the hiding loader");
        HarnessPlugin p = (HarnessPlugin) plugin.getDeclaredConstructor().newInstance();
        List<HarnessTool> tools = new ArrayList<>();

        p.install(recorder(tools), MAPPER.createObjectNode());

        assertTrue(tools.isEmpty(), "no run_js without GraalJS");
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).contains("GraalJS is not on the class path"), warnings().get(0));
    }

    @Test
    void withGraalJsThePluginRegistersRunJsWithAStatus() {
        List<HarnessTool> tools = new ArrayList<>();

        new JsPlugin().install(recorder(tools), MAPPER.createObjectNode());

        assertEquals(1, tools.size());
        assertEquals("run_js", tools.get(0).name());
        assertTrue(tools.get(0).status().contains("profile") && tools.get(0).status().contains("edition"),
                tools.get(0).status());
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void isolatedWithoutAnIsolateDisablesTheToolWithAReasonAndNoDowngrade() {
        GraalProbe.Result real = GraalProbe.probe();
        GraalProbe.Result noIsolate = new GraalProbe.Result(true, true, true, real.sandboxLimits(), false,
                "No native isolate library is available", real.implementation(), real.version(), real.edition());
        List<HarnessTool> tools = new ArrayList<>();
        JsSettings s = JsTest.settingsFor("isolated");

        JsInstaller.install(recorder(tools), s, noIsolate);

        assertTrue(tools.isEmpty());
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().get(0).contains("engine=isolated") && warnings().get(0).contains("isolate"),
                warnings().get(0));
        assertNull(JsProfiles.select(s, noIsolate).profile());
    }

    @Test
    void theDefaultEngineIsTheIsolateAndNeverFallsBackSilently() {
        assertEquals(JsSettings.EngineChoice.ISOLATED, JsSettings.defaults().engine());
        GraalProbe.Result real = GraalProbe.probe();
        GraalProbe.Result noIsolate = new GraalProbe.Result(true, true, true, real.sandboxLimits(), false, "none",
                real.implementation(), real.version(), real.edition());
        JsProfiles.Selection sel = JsProfiles.select(JsSettings.defaults(), noIsolate);

        assertNull(sel.profile(), "no isolate, no run_js: fail closed");
        assertTrue(sel.reason().contains("run_js is disabled"), sel.reason());
    }

    @Test
    void constrainedIsHonouredEvenWhenAnIsolateExists() {
        JsProfiles.Selection sel = JsProfiles.select(JsTest.settingsFor("constrained"), GraalProbe.probe());

        try {
            assertEquals("constrained", sel.profile().name());
        } finally {
            sel.profile().close();
        }
    }

    @Test
    void theDefaultPicksTheIsolateWhenTheProbeVerifiedOne() {
        org.junit.jupiter.api.Assumptions.assumeTrue(JsTest.isolateAvailable(), "no isolate on this platform");
        JsProfiles.Selection sel = JsProfiles.select(JsSettings.defaults(), GraalProbe.probe());

        try {
            assertEquals("isolated", sel.profile().name());
        } finally {
            sel.profile().close();
        }
    }

    @Test
    void constrainedWarnsThatItIsNoBoundary() {
        Logger js = (Logger) LoggerFactory.getLogger("org.kendar.llm.js");
        ListAppender<ILoggingEvent> seen = new ListAppender<>();
        Level was = js.getLevel();
        js.setLevel(Level.WARN);
        seen.start();
        js.addAppender(seen);
        JsProfiles.Selection sel = JsProfiles.select(JsTest.settingsFor("constrained"), GraalProbe.probe());
        try {
            js.detachAppender(seen);
            js.setLevel(was);
            assertTrue(seen.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("not a security boundary")), seen.list.toString());
        } finally {
            sel.profile().close();
        }
    }

    private static GraalProbe.Result probeOf(GraalEdition edition, boolean isolate, String note) {
        return new GraalProbe.Result(true, true, true, true, isolate, note, "impl", "25.5", edition);
    }

    @Test
    void theMissingIsolateMessageNamesTheArtifactOfTheEdition() {
        String community = JsProfiles.noIsolate(probeOf(GraalEdition.COMMUNITY, false,
                "No native isolate library is available for the requested language(s) [js]."));
        String oracle = JsProfiles.noIsolate(probeOf(GraalEdition.ORACLE, false, ""));
        String unknown = JsProfiles.noIsolate(probeOf(GraalEdition.UNKNOWN, false, ""));

        assertTrue(community.contains("js-isolate-community") && !community.contains("(type pom; Oracle"), community);
        assertTrue(community.contains("No native isolate library"), community);
        assertTrue(oracle.contains("org.graalvm.polyglot:js-isolate (type pom; Oracle") && !oracle.contains("-community"),
                oracle);
        assertTrue(unknown.contains("js-isolate-community") && unknown.contains("org.graalvm.polyglot:js-isolate "), unknown);
        for (String m : List.of(community, oracle, unknown)) assertTrue(m.contains("constrained"), m);
    }

    @Test
    void bothEditionsSelectTheIsolateWhenOneWasVerified() {
        org.junit.jupiter.api.Assumptions.assumeTrue(JsTest.isolateAvailable(), "no isolate on this platform");
        for (GraalEdition e : List.of(GraalEdition.COMMUNITY, GraalEdition.ORACLE)) {
            JsProfiles.Selection sel = JsProfiles.select(JsSettings.defaults(), probeOf(e, true, ""));
            try {
                assertEquals("isolated", sel.profile().name(), e.name());
            } finally {
                sel.profile().close();
            }
        }
    }

    @Test
    void theEditionIsReadFromTheImplementationNameOfTheEngine() {
        assertEquals(GraalEdition.COMMUNITY, GraalEdition.detect(null, "GraalVM CE Isolated", null));
        assertEquals(GraalEdition.COMMUNITY, GraalEdition.detect(null, "GraalVM CE JVM", null));
        assertEquals(GraalEdition.ORACLE, GraalEdition.detect(null, "Oracle GraalVM Isolated", null));
        assertEquals(GraalEdition.UNKNOWN, GraalEdition.detect(null, "something", null));
    }

    @Test
    void aBadSettingFailsTheInstallNamingTheKey() throws Exception {
        JsonNode bad = MAPPER.readTree("{\"engine\":\"turbo\"}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JsPlugin().install(recorder(new ArrayList<>()), bad));

        assertTrue(e.getMessage().contains("plugins.js.settings.engine"), e.getMessage());
    }
}
