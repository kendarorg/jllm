package org.kendar.llm.eval.grade.embedding;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("unit")
class BertTokenizerTest {

    private static final int UNK = 1;
    private static final int CLS = 2;
    private static final int SEP = 3;

    private static BertTokenizer tiny(int maxLength) {
        String[] tokens = {"[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello", "world", "un", "##aff", "##able", "cafe", ",",
                "!", "play", "##ing", "a", "b", "c", "x", "中", "文", "-", "don", "'", "t"};
        Map<String, Integer> v = new HashMap<>();
        for (int i = 0; i < tokens.length; i++) v.put(tokens[i], i);
        return new BertTokenizer(v, maxLength);
    }

    private static int id(String token) {
        return switch (token) {
            case "hello" -> 4;
            case "world" -> 5;
            case "un" -> 6;
            case "##aff" -> 7;
            case "##able" -> 8;
            case "cafe" -> 9;
            case "," -> 10;
            case "!" -> 11;
            case "play" -> 12;
            case "##ing" -> 13;
            case "a" -> 14;
            case "b" -> 15;
            case "c" -> 16;
            case "x" -> 17;
            case "中" -> 18;
            case "文" -> 19;
            case "-" -> 20;
            case "don" -> 21;
            case "'" -> 22;
            case "t" -> 23;
            default -> throw new IllegalArgumentException(token);
        };
    }

    private static int[] ids(String... tokens) {
        int[] out = new int[tokens.length + 2];
        out[0] = CLS;
        for (int i = 0; i < tokens.length; i++) out[i + 1] = id(tokens[i]);
        out[out.length - 1] = SEP;
        return out;
    }

    @Test
    void framesWithClsAndSep() {
        assertArrayEquals(new int[]{CLS, SEP}, tiny(512).encode(""));
        assertArrayEquals(ids("hello", "world"), tiny(512).encode("hello world"));
    }

    @Test
    void lowerCases() {
        assertArrayEquals(ids("hello", "world"), tiny(512).encode("HeLLo   WORLD"));
    }

    @Test
    void stripsAccents() {
        assertArrayEquals(ids("cafe"), tiny(512).encode("Café"));
        assertArrayEquals(ids("cafe"), tiny(512).encode("café"));
    }

    @Test
    void splitsPunctuation() {
        assertArrayEquals(ids("hello", ",", "world", "!"), tiny(512).encode("hello,world!"));
        assertArrayEquals(ids("don", "'", "t"), tiny(512).encode("don't"));
        assertEquals(List.of("a", "-", "b"), BertTokenizer.words("a-b"));
    }

    @Test
    void splitsCjkCharacters() {
        assertArrayEquals(ids("中", "文"), tiny(512).encode("中文"));
        assertEquals(List.of("hello", "中", "文", "world"), BertTokenizer.words("hello中文world"));
    }

    @Test
    void wordPieceLongestMatchFirstWithContinuations() {
        assertArrayEquals(ids("un", "##aff", "##able"), tiny(512).encode("unaffable"));
        assertArrayEquals(ids("play", "##ing"), tiny(512).encode("playing"));
    }

    @Test
    void unmatchablePieceMakesWholeWordUnknown() {
        assertArrayEquals(new int[]{CLS, id("hello"), UNK, id("world"), SEP}, tiny(512).encode("hello unaffz world"));
        assertArrayEquals(new int[]{CLS, UNK, SEP}, tiny(512).encode("zzz"));
    }

    @Test
    void overlongWordIsUnknown() {
        assertArrayEquals(new int[]{CLS, UNK, SEP}, tiny(512).encode("a".repeat(101)));
        assertArrayEquals(new int[]{CLS, id("a"), SEP}, tiny(512).encode("a"));
    }

    @Test
    void dropsControlCharactersAndNormalizesWhitespace() {
        assertArrayEquals(ids("hello", "world"), tiny(512).encode("hel\u0000lo\t\n world​"));
    }

    @Test
    void truncatesKeepingSep() {
        int[] out = tiny(6).encode("a b c a b c a b c");
        assertEquals(6, out.length);
        assertEquals(CLS, out[0]);
        assertEquals(SEP, out[5]);
        assertArrayEquals(new int[]{CLS, id("a"), id("b"), id("c"), id("a"), SEP}, out);
    }

    @Test
    void truncatesAt512() {
        int[] out = tiny(BertTokenizer.MAX_LENGTH).encode("a ".repeat(1000));
        assertEquals(512, out.length);
        assertEquals(SEP, out[511]);
    }

    @Test
    void referenceIdsMatchWhenModelFolderHasVocabulary() throws IOException {
        Path dir = Path.of(System.getProperty("eval.embedding.modelDir", "eval-models/bge-small-en-v1.5"));
        assumeTrue(java.nio.file.Files.isRegularFile(dir.resolve(OnnxEmbedder.VOCAB_FILE)), "vocab.txt not present");
        BertTokenizer t = BertTokenizer.load(dir.resolve(OnnxEmbedder.VOCAB_FILE));
        List<String> lines;
        try (var in = BertTokenizerTest.class.getResourceAsStream("/tokenizer/bge-reference-ids.tsv")) {
            lines = new ArrayList<>(Arrays.asList(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
        }
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            int[] expected = Arrays.stream(parts[1].trim().split(" ")).mapToInt(Integer::parseInt).toArray();
            assertArrayEquals(expected, t.encode(parts[0]), parts[0]);
        }
    }
}
