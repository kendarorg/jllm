package org.kendar.jllm.cli;

import org.kendar.jllm.LLMSettings;
import org.kendar.jllm.base.LLMClient;
import org.kendar.jllm.base.LLMConfigManager;
import org.kendar.jllm.chat.ChatStore;
import org.kendar.jllm.chat.H2ChatStore;
import org.kendar.jllm.ollama.OllamaClient;
import org.kendar.jllm.session.CodingSession;
import org.kendar.jllm.session.CodingSessionFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Thin command-line front end for jllm-lib. Builds an Ollama-backed
 * {@link CodingSession} (with tools, skills, context and sub-agents wired up by
 * {@link CodingSessionFactory}) and either answers a single prompt or runs an
 * interactive read-eval-print loop.
 */
@Command(name = "jllm",
    mixinStandardHelpOptions = true,
    version = "jllm-cli 1.0",
    description = "Drive a jllm agentic coding session from the command line.")
public class JllmCli implements Callable<Integer> {

  @Option(names = {"-m", "--model"}, description = "Model name (default: ${DEFAULT-VALUE}).")
  String model = "danielsheep/Qwen3-Coder-30B-A3B-Instruct-1M-Unsloth:UD-Q4_K_XL";

  @Option(names = {"-s", "--server"}, description = "Ollama base URL (default: ${DEFAULT-VALUE}).")
  String server = "http://192.168.1.96:11434";

  @Option(names = {"-d", "--dir"}, description = "Working directory / config dir (default: current dir).")
  Path dir = Path.of("target");


  @Option(names = {"-a", "--agent"}, description = "Root agent name (default: ${DEFAULT-VALUE}).")
  String agent = "oneShot";

  @Option(names = {"--plan"}, description = "Start in plan mode (read-only tools, produces a plan).")
  boolean plan = false;

  @Option(names = {"--db"}, description = "Chat store JDBC URL (default: in-memory H2).")
  String db;

  @Option(names = {"--resume"}, description = "Resume an existing conversation by id.")
  String resume;

  @Option(names = {"-p", "--prompt"}, description = "One-shot prompt; prints the answer and exits.")
  String prompt;

  @Parameters(arity = "0..*", description = "Prompt words (one-shot); if omitted, an interactive REPL starts.")
  List<String> promptWords;

  @Override
  public Integer call() {
    String oneShot = resolvePrompt();

    LLMSettings settings = new LLMSettings();
    settings.setModel(model);
    settings.setServer(server);
    settings.setSettingDirs(List.of(dir.toAbsolutePath().toString()));
    LLMConfigManager.initialize(settings);

    LLMClient client = new OllamaClient(settings);
    ChatStore store = db == null ? H2ChatStore.inMemory() : new H2ChatStore(db);

    try (CodingSession session = resume != null
        ? CodingSessionFactory.resume(dir, client, store, resume, agent)
        : CodingSessionFactory.create(dir, client, store, "jllm-cli session", agent, plan)) {

      if (oneShot != null) {
        System.out.println(session.send(oneShot));
        return 0;
      }
      return repl(session);
    } catch (Exception e) {
      System.err.println("jllm: " + e.getMessage());
      return 1;
    }
  }

  private String resolvePrompt() {
    if (prompt != null && !prompt.isBlank()) {
      return prompt.trim();
    }
    if (promptWords != null && !promptWords.isEmpty()) {
      return String.join(" ", promptWords).trim();
    }
    return null;
  }

  private int repl(CodingSession session) {
    System.out.println("jllm session " + session.getConversationId()
        + (session.isPlanMode() ? " [plan mode]" : ""));
    System.out.println("Type your message. Commands: /id, /exit (or Ctrl-D).");

    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    try {
      String line;
      while (true) {
        System.out.print("> ");
        System.out.flush();
        line = in.readLine();
        if (line == null) {
          break;
        }
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        if (line.equals("/exit") || line.equals("/quit")) {
          break;
        }
        if (line.equals("/id")) {
          System.out.println(session.getConversationId());
          continue;
        }
        try {
          System.out.println(session.send(line));
        } catch (Exception e) {
          System.err.println("jllm: " + e.getMessage());
        }
      }
    } catch (Exception e) {
      System.err.println("jllm: " + e.getMessage());
      return 1;
    }
    return 0;
  }

  public static void main(String[] args) {
    System.exit(new CommandLine(new JllmCli()).execute(args));
  }
}
