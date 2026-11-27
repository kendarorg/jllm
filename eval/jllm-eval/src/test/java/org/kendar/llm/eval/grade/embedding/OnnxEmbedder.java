package org.kendar.llm.eval.grade.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * bge-small-en-v1.5 (int8) through ONNX Runtime: WordPiece tokens, CLS pooling, L2 normalization. Vectors are cached on
 * disk, keyed by the model hash and the text. Not thread-safe by design (the eval is sequential).
 */
public final class OnnxEmbedder implements Embedder, AutoCloseable {

    public static final String MODEL_FILE = "model_quantized.onnx";
    public static final String VOCAB_FILE = "vocab.txt";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final BertTokenizer tokenizer;
    private final Path cacheDir;
    private final String modelSha;

    private OnnxEmbedder(OrtEnvironment env, OrtSession session, BertTokenizer tokenizer, Path cacheDir, String modelSha) {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;
        this.cacheDir = cacheDir;
        this.modelSha = modelSha;
    }

    /** True when the model files are present in the folder. */
    public static boolean filesPresent(Path modelDir) {
        return modelDir != null && Files.isRegularFile(modelDir.resolve(MODEL_FILE))
                && Files.isRegularFile(modelDir.resolve(VOCAB_FILE));
    }

    /**
     * Verifies the model hash and opens the model.
     *
     * @param expectedSha SHA-256 of the model file; null or blank fails verification
     * @param cacheDir    folder of the vector cache, created on demand
     */
    public static OnnxEmbedder open(Path modelDir, String expectedSha, Path cacheDir) {
        if (expectedSha == null || expectedSha.isBlank()) {
            throw new IllegalStateException("eval.embedding.sha256 is not set: refusing to load an unverified model");
        }
        Path model = modelDir.resolve(MODEL_FILE);
        String actual = sha256(model);
        if (!actual.equalsIgnoreCase(expectedSha.trim())) {
            throw new IllegalStateException("Embedding model " + model + " has SHA-256 " + actual + ", expected "
                    + expectedSha.trim());
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession session = env.createSession(model.toString(), new OrtSession.SessionOptions());
            return new OnnxEmbedder(env, session, BertTokenizer.load(modelDir.resolve(VOCAB_FILE)), cacheDir, actual);
        } catch (OrtException e) {
            throw new IllegalStateException("Cannot load embedding model " + model + ": " + e.getMessage(), e);
        }
    }

    @Override
    public float[] embed(String text) {
        String key = sha256Bytes((modelSha + "\n" + text).getBytes(StandardCharsets.UTF_8));
        Path cached = cacheDir.resolve(key + ".vec");
        float[] v = readCache(cached);
        if (v != null) return v;
        v = compute(text);
        writeCache(cached, v);
        return v;
    }

    private float[] compute(String text) {
        int[] ids = tokenizer.encode(text);
        long[] in = new long[ids.length];
        long[] mask = new long[ids.length];
        long[] types = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            in[i] = ids[i];
            mask[i] = 1;
        }
        long[] shape = {1, ids.length};
        Set<String> names = session.getInputNames();
        try (OnnxTensor idT = OnnxTensor.createTensor(env, LongBuffer.wrap(in), shape);
             OnnxTensor maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
             OnnxTensor typeT = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idT);
            inputs.put("attention_mask", maskT);
            if (names.contains("token_type_ids")) inputs.put("token_type_ids", typeT);
            try (OrtSession.Result r = session.run(inputs)) {
                float[][][] hidden = (float[][][]) r.get(0).getValue();
                return normalize(hidden[0][0]);
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Embedding failed: " + e.getMessage(), e);
        }
    }

    /** L2 normalization; a zero vector stays zero. */
    static float[] normalize(float[] cls) {
        double n = 0;
        for (float f : cls) n += (double) f * f;
        n = Math.sqrt(n);
        float[] out = new float[cls.length];
        if (n == 0) return out;
        for (int i = 0; i < cls.length; i++) out[i] = (float) (cls[i] / n);
        return out;
    }

    private static float[] readCache(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file));
            float[] v = new float[b.remaining() / 4];
            b.asFloatBuffer().get(v);
            return v.length == 0 ? null : v;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(Path file, float[] v) {
        try {
            Files.createDirectories(file.getParent());
            ByteBuffer b = ByteBuffer.allocate(v.length * 4);
            b.asFloatBuffer().put(v);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, b.array());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // a cache that cannot be written only costs time
        }
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot hash " + file + ": " + e.getMessage(), e);
        }
    }

    static String sha256Bytes(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            // closing only
        }
    }
}
