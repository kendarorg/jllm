package org.kendar.jllm.tools;

import org.kendar.jllm.base.LLMTool;
import org.kendar.jllm.exceptions.LLMToolException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ShellTool implements LLMTool {

  private final Path root;

  public ShellTool(Path root) {
    if (root == null) {
      throw new LLMToolException("root path must not be null");
    }
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public boolean requiresApproval() {
    return true;
  }

  @Override
  public String name() {
    return "run_shell_command";
  }

  @Override
  public String toolSchema() {
    return ToolSchemas.builder("run_shell_command", "Run a shell command in the working directory and capture its output and exit code.")
        .prop("command", "string", "The shell command to execute.", true)
        .prop("timeout_ms", "integer", "Timeout in milliseconds (default 60000).", false)
        .build();
  }

  @Override
  public String act(Map<String, String> args) throws LLMToolException {
    String command = args == null ? null : args.get("command");
    if (command == null || command.isEmpty()) {
      throw new LLMToolException("Missing required argument: command");
    }
    long timeoutMs = 60000;
    if (args.get("timeout_ms") != null && !args.get("timeout_ms").isEmpty()) {
      try {
        timeoutMs = Long.parseLong(args.get("timeout_ms"));
      } catch (NumberFormatException e) {
        throw new LLMToolException("timeout_ms must be an integer", e);
      }
    }

    String[] shell = isExecutable("/bin/bash") ? new String[]{"bash", "-c", command}
        : new String[]{"sh", "-c", command};

    ProcessBuilder pb = new ProcessBuilder(shell);
    pb.directory(root.toFile());
    pb.redirectErrorStream(true);

    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw new LLMToolException("Unable to start command", e);
    }

    StringBuilder output = new StringBuilder();
    try {
      Thread reader = new Thread(() -> {
        try (InputStream is = process.getInputStream()) {
          byte[] buf = new byte[4096];
          int n;
          while ((n = is.read(buf)) != -1) {
            synchronized (output) {
              output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
          }
        } catch (IOException ignored) {
          // process ended
        }
      });
      reader.setDaemon(true);
      reader.start();

      boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        reader.join(1000);
        synchronized (output) {
          return output + "\n[command timed out after " + timeoutMs + " ms]";
        }
      }
      reader.join(1000);
      synchronized (output) {
        String out = output.toString();
        if (!out.isEmpty() && !out.endsWith("\n")) {
          out += "\n";
        }
        return out + "exit code: " + process.exitValue();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LLMToolException("Command interrupted", e);
    }
  }

  private boolean isExecutable(String path) {
    java.io.File f = new java.io.File(path);
    return f.exists() && f.canExecute();
  }
}
