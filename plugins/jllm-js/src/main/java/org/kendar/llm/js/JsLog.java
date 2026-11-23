package org.kendar.llm.js;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;

/** The plugin's logger and the bridge that turns the engine's diagnostic stream into DEBUG log lines. */
final class JsLog {

    static final Logger LOG = LoggerFactory.getLogger("org.kendar.llm.js");

    private JsLog() {
    }

    /** Engine diagnostics (such as the interpreter-only warning) go to the DEBUG log, one line per entry. */
    static OutputStream engineStream() {
        return new OutputStream() {
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
        };
    }
}
