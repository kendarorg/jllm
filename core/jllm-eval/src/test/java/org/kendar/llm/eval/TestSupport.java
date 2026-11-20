package org.kendar.llm.eval;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class TestSupport {
    private TestSupport() {
    }

    static Properties file() {
        Properties p = new Properties();
        try (InputStream in = TestSupport.class.getResourceAsStream("/eval.properties")) {
            p.load(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static EvalConfig load(Map<String, String> sys, Map<String, String> env) {
        return EvalConfig.load(file(), sys, env);
    }
}
