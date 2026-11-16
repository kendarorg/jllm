package org.kendar.llm.workflows.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * What the workflows engine offers to front ends (chat, command line, web). Every call is made for a user; a user
 * sees only their own flows, the global flows their roles allow, and only the runs they started. Views are plain JSON
 * so this module has no dependency on the engine.
 *
 * <p>Run view: {@code runId, status, flow, pending (question), result, error, parentRunId}. Question: {@code key,
 * kind (see QuestionKind), title, text, options[{value,label}], min, max, required, who, pattern, facts}.
 */
public interface WorkflowApi {

    /** The three ways a person can be asked something. */
    enum QuestionKind { TEXT, SINGLE, MULTI }

    /** Run states. */
    enum RunStatus { PENDING, RUNNING, WAITING, COMPLETED, FAILED, CANCELLED }

    /** @return {@code [{scope, name}]}: the flows the user may run, both scopes, labelled */
    JsonNode list(String user, Set<String> roles);

    /** The definition of a flow the user may see; {@code scope} may be null when the name is unambiguous. */
    JsonNode flow(String user, Set<String> roles, String name, String scope);

    /** Names of the inputs a flow expects. */
    List<String> inputs(String user, Set<String> roles, String name, String scope);

    /** Saves a flow in place after the save-time checks; global flows need the administrator role. */
    void save(String user, Set<String> roles, String scope, JsonNode flow);

    void delete(String user, Set<String> roles, String scope, String name);

    /** Copies a global flow into the user's own space. */
    JsonNode copy(String user, Set<String> roles, String name, String newName);

    /** Starts a run as the user. {@code chatHistory} (may be null) is copied in as optional context. */
    JsonNode start(String user, Set<String> roles, String name, String scope, JsonNode input, JsonNode chatHistory);

    /** Answers the open question of a run; a refused answer throws and leaves the question open. */
    JsonNode answer(String user, Set<String> roles, String runId, JsonNode answer);

    /** Sends a customer request (see the flow's {@code accepts}). */
    JsonNode request(String user, Set<String> roles, String runId, String kind, JsonNode payload);

    void cancel(String user, Set<String> roles, String runId);

    JsonNode runs(String user, Set<String> roles);

    /** Runs of the user that wait for an answer. */
    JsonNode waiting(String user, Set<String> roles);

    JsonNode run(String user, Set<String> roles, String runId);

    JsonNode history(String user, Set<String> roles, String runId);
}
