package org.kendar.jllm.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test of {@link McpClient} against a minimal Python MCP server speaking
 * the stdio JSON-RPC transport. Skipped when {@code python3} is unavailable.
 */
class McpClientIT {

  private static final String SERVER = ""
      + "import sys, json\n"
      + "def send(o):\n"
      + "    sys.stdout.write(json.dumps(o) + '\\n'); sys.stdout.flush()\n"
      + "for line in sys.stdin:\n"
      + "    line = line.strip()\n"
      + "    if not line:\n"
      + "        continue\n"
      + "    msg = json.loads(line)\n"
      + "    mid = msg.get('id')\n"
      + "    method = msg.get('method')\n"
      + "    if mid is None:\n"
      + "        continue\n"
      + "    if method == 'initialize':\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'protocolVersion':'2024-11-05','capabilities':{}}})\n"
      + "    elif method == 'tools/list':\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'tools':[{'name':'echo','description':'Echo the input',"
      + "'inputSchema':{'type':'object','properties':{'text':{'type':'string'}},'required':['text']}}]}})\n"
      + "    elif method == 'tools/call':\n"
      + "        args = msg.get('params',{}).get('arguments',{})\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'content':[{'type':'text','text':'echo:'+args.get('text','')}]}})\n"
      + "    else:\n"
      + "        send({'jsonrpc':'2.0','id':mid,'error':{'code':-32601,'message':'unknown'}})\n";

  private static boolean pythonAvailable() {
    try {
      Process p = new ProcessBuilder("python3", "--version").start();
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void listsAndCallsToolsOverStdio(@TempDir Path dir) throws Exception {
    assumeTrue(pythonAvailable(), "python3 not available");
    Path script = dir.resolve("server.py");
    Files.writeString(script, SERVER);

    McpServerConfig cfg = new McpServerConfig();
    cfg.setName("test");
    cfg.setCommand("python3");
    cfg.setArgs(List.of(script.toString()));

    try (McpClient client = new McpClient(cfg)) {
      client.start();
      List<McpToolDefinition> tools = client.listTools();
      assertEquals(1, tools.size());
      assertEquals("echo", tools.get(0).getName());

      McpTool tool = new McpTool(client, tools.get(0));
      assertEquals("mcp__test__echo", tool.name());
      assertTrue(tool.toolSchema().contains("echo"));

      String result = tool.act(Map.of("text", "hello"));
      assertEquals("echo:hello", result);
    }
  }
}
