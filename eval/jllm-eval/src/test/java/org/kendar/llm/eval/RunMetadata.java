package org.kendar.llm.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.kendar.llm.eval.pin.BuildInfo;
import org.kendar.llm.eval.pin.JsInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes the run for every later step. Written as {@code run.json} in the run's output folder. {@code targetModel}
 * and {@code judgeModel} are the reported names ({@code eval.target.modelName}, {@code eval.judge.modelName});
 * {@code targetModelId} and {@code judgeModelId} are the ids sent to the servers. {@code build} pins the harness
 * version, {@code settingsHash} and {@code promptsHash} what it was configured with ({@code settings.json} beside this
 * file holds the hashed text), {@code passK} the k values of pass@k and pass^k, {@code dirtyTree} the policy applied.
 */
public record RunMetadata(String targetType, String targetName, String targetModel, String quantization,
                          int contextTokens, boolean smallModel, String serverVersion, String judgeType,
                          String judgeModel, String fastModel, boolean selfGraded, String startTime,
                          BuildInfo build, String settingsHash, String promptsHash, List<Integer> passK,
                          String dirtyTree, JsInfo js, Map<String, Long> warmupMillis, int trials,
                          String targetModelId, String judgeModelId) {

    public static RunMetadata of(EvalConfig c, ConnectionCheck.Result check, Instant start, BuildInfo build,
                                 String settingsHash, String promptsHash, JsInfo js) {
        ModelEndpoint t = c.target();
        String quant = c.local() != null ? c.local().model().quantization() : "unknown";
        String server = switch (c.targetKind()) {
            case LOCAL -> "llama.cpp via docker compose (not managed)";
            case ANTHROPIC -> "anthropic-api";
            case REMOTE -> "remote (not managed)";
        };
        Map<String, Long> warm = new LinkedHashMap<>();
        check.warmupMillis().forEach((k, v) -> warm.put(k.name().toLowerCase(), v));
        return new RunMetadata(c.targetKind().name().toLowerCase(), c.targetName(), c.targetModelName(), quant,
                t.contextTokens(), t.smallModel(), server, c.judgeKind(), c.judgeModelName(), c.fast().model(),
                c.selfGraded(), start.toString(), build, settingsHash, promptsHash, c.passK(), c.dirtyTree().key(), js,
                warm, c.trials(), t.model(), c.judge().model());
    }

    /** Writes run.json (secrets masked) and returns its path. */
    public Path write(Path dir, Secrets secrets) throws IOException {
        Files.createDirectories(dir);
        ObjectMapper m = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String json = secrets.maskJson(m.writeValueAsString(this));
        Path file = dir.resolve("run.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }
}
