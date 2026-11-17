package org.kendar.llm.eval.fixture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Three sample workflows as flow definitions. None needs a model: they use Java actions
 * ({@link SampleFlowActions}), a Java chooser, questions to the user and a sub-flow call.
 * <ul>
 *   <li>{@code linear}: input {@code text}; normalizes, counts words, finishes with {@code upper} and {@code words}.</li>
 *   <li>{@code branching}: input {@code amount}; chooses small, large or huge and finishes with
 *       {@code decision} approved, review or rejected.</li>
 *   <li>{@code ask-and-call}: asks the order number (pattern {@code A-[0-9]+}) and how to pay back (money or
 *       credit), calls {@code lookup-order}, finishes with {@code order}, {@code how} and {@code status}.</li>
 *   <li>{@code lookup-order}: the sub-flow; input {@code order}; finishes with {@code status}.</li>
 * </ul>
 */
public final class SampleFlows {

    /** The user that chat slash commands act as (the plugin default). */
    public static final String USER = "local";

    public static final String LINEAR = """
            {'name':'linear','start':'norm','steps':{
              'norm':{'type':'action','action':'normalizeText','next':'count'},
              'count':{'type':'action','action':'countWords','next':'end'},
              'end':{'type':'finish','result':{'upper':'{{steps.norm.text}}','words':'{{steps.count.words}}'}}}}""";

    public static final String BRANCHING = """
            {'name':'branching','start':'rule','steps':{
              'rule':{'type':'decide','by':'java','chooser':'amountBand','question':'How large is the amount?',
                      'outcomes':{'small':'auto','large':'review','huge':'reject'}},
              'auto':{'type':'action','action':'approve','next':'approved'},
              'review':{'type':'action','action':'flagReview','next':'inreview'},
              'approved':{'type':'finish','result':{'decision':'approved'}},
              'inreview':{'type':'finish','result':{'decision':'review'}},
              'reject':{'type':'finish','result':{'decision':'rejected'}}}}""";

    public static final String LOOKUP_ORDER = """
            {'name':'lookup-order','start':'load','steps':{
              'load':{'type':'action','action':'loadOrder','next':'end'},
              'end':{'type':'finish','result':{'status':'{{steps.load.status}}'}}}}""";

    public static final String ASK_AND_CALL = """
            {'name':'ask-and-call','start':'order','steps':{
              'order':{'type':'ask','kind':'text','text':'Which order number?','pattern':'A-[0-9]+','next':'how'},
              'how':{'type':'ask','kind':'single','text':'How should we pay you back?',
                     'options':{'money':'Money back','credit':'Store credit'},'next':'look'},
              'look':{'type':'call','flow':'lookup-order','scope':'personal','input':{'order':'{{steps.order}}'},'next':'end'},
              'end':{'type':'finish','result':{'order':'{{steps.order}}','how':'{{steps.how}}','status':'{{steps.look.status}}'}}}}""";

    private SampleFlows() {
    }

    /** Flow name to definition JSON, sub-flow last. */
    public static Map<String, String> all() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("linear", LINEAR);
        m.put("branching", BRANCHING);
        m.put("ask-and-call", ASK_AND_CALL);
        m.put("lookup-order", LOOKUP_ORDER);
        return m;
    }

    /** Writes the personal flows of {@link #USER} as the plugin's file store reads them; {@code dir} is the plugin's {@code dir} setting. */
    public static void install(Path dir) {
        Path folder = dir.resolve("flows").resolve("users").resolve(USER);
        try {
            Files.createDirectories(folder);
            for (var e : all().entrySet()) {
                Files.writeString(folder.resolve(e.getKey() + ".json"), e.getValue().replace('\'', '"'));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
