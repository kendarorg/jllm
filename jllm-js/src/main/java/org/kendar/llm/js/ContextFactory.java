package org.kendar.llm.js;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;

/** One shared {@link Engine} (code cache) and a locked-down fresh {@link Context} per run. */
final class ContextFactory implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("org.kendar.llm.js");

    private final Engine engine;
    private final ResourceLimits limits;

    ContextFactory(JsSettings settings) {
        this(settings, LOG.isDebugEnabled(), new Slf4jLogStream());
    }

    /** @param debug engine diagnostics (the interpreter-only warning) are written to {@code debugSink}; else dropped */
    ContextFactory(JsSettings settings, boolean debug, OutputStream debugSink) {
        Engine.Builder b = Engine.newBuilder("js", "regex").option("engine.WarnInterpreterOnly", Boolean.toString(debug));
        b.logHandler(debug ? debugSink : OutputStream.nullOutputStream());
        this.engine = b.build();
        this.limits = ResourceLimits.newBuilder().statementLimit(settings.statementLimit(), null).build();
    }

    /** @param out receives console output (stdout and stderr) */
    Context create(OutputStream out) {
        return Context.newBuilder("js", "regex")
                .engine(engine)
                .allowAllAccess(false)
                .allowIO(IOAccess.NONE)
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(c -> false)
                .allowHostClassLoading(false)
                .allowCreateProcess(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowExperimentalOptions(true)
                .resourceLimits(limits)
                .option("js.print", "false")
                .option("js.load", "false")
                .option("js.graal-builtin", "false")
                .option("js.polyglot-builtin", "false")
                .option("js.java-package-globals", "false")
                .option("js.commonjs-require", "false")
                .out(out)
                .err(out)
                .build();
    }

    @Override
    public void close() {
        engine.close();
    }

    /** Engine diagnostics go to the DEBUG log. */
    private static final class Slf4jLogStream extends OutputStream {
        private final StringBuilder line = new StringBuilder();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                LOG.debug("{}", line);
                line.setLength(0);
            } else if (b != '\r') {
                line.append((char) b);
            }
        }
    }
}
