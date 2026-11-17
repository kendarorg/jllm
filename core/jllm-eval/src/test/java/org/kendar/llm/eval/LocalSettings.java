package org.kendar.llm.eval;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The externally managed llama.cpp server (docker compose) and the model selected for this run. */
public record LocalSettings(int port, Model model) {

    /** @param alias model name exposed by the server (no quantization) */
    public record Model(String key, String file, String alias, int contextTokens, boolean smallModel,
                        String extraArgs) {
        private static final Pattern QUANT =
                Pattern.compile("(?i)(?:^|[-_.])((?:i?q\\d(?:_[a-z0-9]+)*)|mxfp4|f16|bf16|f32)(?=[-_.]|$)");

        /** Quantization parsed from the file name, or "unknown". */
        public String quantization() {
            Matcher m = QUANT.matcher(file);
            return m.find() ? m.group(1).toUpperCase() : "unknown";
        }
    }

    public String baseUrl() {
        return "http://localhost:" + port + "/v1";
    }

}
