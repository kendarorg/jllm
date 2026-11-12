package org.kendar.llm.harness.llm;

/** The model call was aborted through a {@link CancelToken}. */
public class ModelCancelledException extends ModelException {
    public ModelCancelledException() {
        super("Model call cancelled");
    }
}
