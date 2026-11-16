package org.kendar.llm.workflows;

/**
 * Contributes Java actions and choosers under registered names. Found with {@link java.util.ServiceLoader} (a jar in
 * the plugin directory or on the classpath listing its class under {@code META-INF/services}), so the chat plugin and
 * the command line see the same actions. Only deployers add providers; users never create actions.
 */
public interface ActionProvider {
    void register(Registry registry);

    /** A registry holding the actions of every provider found with the given class loader. */
    static Registry load(ClassLoader loader) {
        Registry registry = new Registry();
        for (ActionProvider p : java.util.ServiceLoader.load(ActionProvider.class, loader)) p.register(registry);
        return registry;
    }
}
