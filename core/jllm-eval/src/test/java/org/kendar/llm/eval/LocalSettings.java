package org.kendar.llm.eval;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Settings of the llama.cpp container plus the one model selected for this run. */
public record LocalSettings(String image, boolean gpu, String modelsDir, String cacheDir, int port,
                            boolean keepRunning, int startupTimeoutSeconds, Model model) {

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

    /** Fingerprint of everything that requires a different server process. */
    public String fingerprint() {
        return String.join("|", image, String.valueOf(gpu), String.valueOf(port), model.file(), model.alias(),
                String.valueOf(model.contextTokens()), model.extraArgs() == null ? "" : model.extraArgs().trim());
    }
}
