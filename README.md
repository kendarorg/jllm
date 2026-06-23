# JLLM

> ⚠️ **Work in progress.** This project is an exploration / demonstration, not a finished product. APIs, config formats and module layout will change.

**JLLM** is a small Java framework that demonstrates how to build an **agent / tool / MCP based CLI on top of local LLMs** - without depending on any cloud provider. It talks to a locally-running model server ([Ollama](https://ollama.com)) and layers a configurable pipeline of *classifiers*, *agents* and *tools* on top of it.

The goal is educational: to show, end to end, what the moving parts of an "agentic" CLI look like when you build them yourself in plain Java.

---

## Concepts

JLLM is built from a few small, composable pieces:

| Piece | Role |
|-------|------|
| **Client** (`LLMClient`) | Talks to the local model server. The only implementation today is `OllamaClient`. |
| **Classifier** (`LLMClassifier`) | Asks the LLM to bucket an incoming prompt into one of a set of named categories (e.g. *plan / code / oneShot / research*). Used to route a request to the right agent. |
| **Agent** (`LLMAgent`) | A named "persona" with a description and an expected output format (e.g. a *planning* agent that turns a request into a todo list). |
| **Tool** (`LLMTool`) | A callable capability the model can invoke, with a JSON schema, an `available()` / `requiresApproval()` gate, and an `act(args)` method. |
| **Config Manager** (`LLMConfigManager`) | Loads classifiers (JSON) and agents (XML) from a `.jllm/` directory tree and registers them by name. |
| **Entry Point** (`LLMEntryPoint`) | Wires it together: classify the prompt, pick an agent, call the model. |

The flow for a single request looks like:

```
prompt ──► scopeClassifier ──► (plan | code | oneShot | research) ──► agent ──► LLMClient ──► local model
```

---

## Requirements

- **Java 25**
- **Maven 3.x**
- **[Ollama](https://ollama.com)** running locally (default `http://localhost:11434`) with at least one model pulled, e.g.:
  ```bash
  ollama pull llama3
  ```

---

## Project layout

This is a Maven multi-module (reactor) project:

```
jllm/
├── pom.xml            parent reactor - org.kendar:j-llm:1.0-SNAPSHOT, Java 25
├── jllm-lib/          core library (clients, agents, tools, classifiers, config)
├── jllm-cli/          command-line front end (placeholder, in progress)
└── default/           default .jllm configuration tree shipped with the project
    └── .jllm/
        ├── classifiers/default/scopeClassifier.json
        └── agents/default/*.xml
```

**Key dependencies:** Apache HttpClient 5 (HTTP), Jackson (JSON/XML), JUnit 5 (tests).

---

## Build

```bash
mvn clean install
```

This builds and installs both modules. Coordinates for the core library:

```xml
<dependency>
  <groupId>org.kendar</groupId>
  <artifactId>jllm-lib</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

---

## Quick start (library)

```java
// 1. Point the client at your local Ollama server and model
LLMSettings settings = new LLMSettings();
settings.setModel("llama3");
settings.setServer("http://localhost:11434"); // default

// 2. Load agents + classifiers from the .jllm/ config tree
settings.setSettingDirs(List.of("default"));
LLMConfigManager.initialize(settings);

// 3. Create a client and the entry point
LLMClient client = new OllamaClient(settings);
LLMEntryPoint entryPoint = new LLMEntryPoint(client);

// 4. Run a prompt - it gets classified and routed to an agent
String result = entryPoint.call("Write a function that reverses a string");
System.out.println(result);
```

Talking to the client directly:

```java
LLMClient client = new OllamaClient(settings);

LLMRequest request = new LLMRequest()
        .withPrompt("Hello there");
request.setSystem("You are a helpful assistant");

LLMResponse response = client.call(request);
System.out.println(response.getResponse());
```

---

## Configuration

Configuration lives under a `.jllm/` directory (created automatically if missing). Each setting directory in `LLMSettings.settingDirs` is scanned recursively.

### Classifiers - JSON

`.jllm/classifiers/default/scopeClassifier.json`

```json
{
  "name": "scopeClassifier",
  "description": "Classify the scope of the prompt",
  "classification": {
    "plan": "Create a plan for the request",
    "code": "Generate code for the request",
    "oneShot": "Simple request",
    "research": "Research the request"
  },
  "retries": 3
}
```

The classifier builds a prompt from the `classification` map, asks the model to pick one of the keys, and retries up to `retries` times if the answer doesn't match a known category.

### Agents - XML

`.jllm/agents/default/planning.xml`

```xml
<agent name="planning">
  <description><![CDATA[
Break the request down into an ordered, actionable todo list.
Analyze the goal, identify the concrete steps required to achieve it,
and list them in execution order. Do not implement anything, only plan.
]]></description>
  <outputFormat><![CDATA[
A todo list, each line starting with '[] ' followed by a short imperative description.
Example:
[] Read the existing configuration
[] Add the new field to the model
[] Wire the field into the loader
]]></outputFormat>
</agent>
```

The default tree ships four agents: `oneShot`, `planning`, `code`, `research`.

---

## Core API

```java
public interface LLMClient {
    LLMResponse call(LLMRequest request) throws LLMClientException;
}

public interface LLMAgent {
    String getName();
    String getDescription();
    String getOutputFormat();
}

public interface LLMTool {
    boolean available();
    boolean requiresApproval();
    String name();
    String toolSchema();                                 // JSON schema
    String act(Map<String, String> args) throws LLMToolException;
}

public abstract class LLMClassifier {
    public abstract String getName();
    public abstract String getDescription();
    public abstract Map<String, String> getClassification();
    public abstract int getRetries();

    public String classify(LLMContext context, String text);
}
```

`LLMRequest` exposes the usual generation knobs with sensible defaults: `model`, `prompt`, `system`, `temperature` (0.4), `topP` (0.9), `topK` (30), `numCtx` (128k), `format` (`json`), `keepAlive` (`5m`), `stream` (false), `think`.

---

## Status & roadmap

Foundational types are in place (see `THE_PROCESS.md`):

- [x] Core types - Client (request/response), Tool, Classifier, exceptions
- [x] Config-driven agents (XML) and classifiers (JSON)
- [x] Ollama client
- [x] Root agents: `oneShot`, `planning`, `code`, `research`
- [ ] CLI front end (`jllm-cli` is currently a placeholder)
- [ ] Tool invocation loop wired into the entry point
- [ ] MCP (Model Context Protocol) support
- [ ] Additional local backends (llama.cpp, etc.)

---

## License

See [LICENSE](LICENSE) (Apache 2.0).
