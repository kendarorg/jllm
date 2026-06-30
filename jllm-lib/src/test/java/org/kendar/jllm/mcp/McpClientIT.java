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
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'protocolVersion':'2024-11-05',"
      + "'capabilities':{'tools':{},'resources':{},'prompts':{}}}})\n"
      + "    elif method == 'tools/list':\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'tools':[{'name':'echo','description':'Echo the input',"
      + "'inputSchema':{'type':'object','properties':{'text':{'type':'string'}},'required':['text']}}]}})\n"
      + "    elif method == 'tools/call':\n"
      + "        args = msg.get('params',{}).get('arguments',{})\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'content':[{'type':'text','text':'echo:'+args.get('text','')}]}})\n"
      + "    elif method == 'resources/list':\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'resources':[{'uri':'file:///readme','name':'readme',"
      + "'description':'The readme','mimeType':'text/plain'}]}})\n"
      + "    elif method == 'resources/read':\n"
      + "        uri = msg.get('params',{}).get('uri','')\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'contents':[{'uri':uri,'mimeType':'text/plain',"
      + "'text':'contents of '+uri}]}})\n"
      + "    elif method == 'prompts/list':\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'prompts':[{'name':'greet','description':'Greet someone',"
      + "'arguments':[{'name':'who','description':'name','required':True}]}]}})\n"
      + "    elif method == 'prompts/get':\n"
      + "        who = msg.get('params',{}).get('arguments',{}).get('who','')\n"
      + "        send({'jsonrpc':'2.0','id':mid,'result':{'description':'a greeting','messages':[{'role':'user',"
      + "'content':{'type':'text','text':'Hello '+who}}]}})\n"
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

      assertTrue(client.supports("resources"));
      assertTrue(client.supports("prompts"));

      List<McpResourceDefinition> resources = client.listResources();
      assertEquals(1, resources.size());
      assertEquals("file:///readme", resources.get(0).getUri());
      assertEquals("contents of file:///readme", client.readResource("file:///readme"));

      List<McpPromptDefinition> prompts = client.listPrompts();
      assertEquals(1, prompts.size());
      assertEquals("greet", prompts.get(0).getName());
      assertEquals(1, prompts.get(0).getArguments().size());
      assertTrue(prompts.get(0).getArguments().get(0).isRequired());
      String rendered = client.getPrompt("greet", Map.of("who", "World"));
      assertTrue(rendered.contains("Hello World"), rendered);
    }
  }

  @Test
  void managerExposesResourceAndPromptToolsAndCatalogs(@TempDir Path dir) throws Exception {
    assumeTrue(pythonAvailable(), "python3 not available");
    Path script = dir.resolve("server.py");
    Files.writeString(script, SERVER);

    McpServerConfig cfg = new McpServerConfig();
    cfg.setName("test");
    cfg.setCommand("python3");
    cfg.setArgs(List.of(script.toString()));

    McpManager manager = new McpManager();
    try {
      org.kendar.jllm.tools.LLMToolRegistry registry =
          org.kendar.jllm.tools.LLMToolRegistry.withDefaults(dir);
      // Drive startServer indirectly by writing mcp.json the manager loads.
      Path cfgFile = dir.resolve(".jllm").resolve("mcp.json");
      Files.createDirectories(cfgFile.getParent());
      Files.writeString(cfgFile, "{\"mcpServers\":{\"test\":{\"command\":\"python3\",\"args\":[\""
          + script.toString().replace("\\", "\\\\") + "\"]}}}");

      manager = McpManager.loadAndRegister(dir, registry);

      assertTrue(manager.hasResources());
      assertTrue(manager.hasPrompts());
      assertNotNull(registry.get("read_mcp_resource"));
      assertNotNull(registry.get("get_mcp_prompt"));
      assertNotNull(registry.get("mcp__test__echo"));

      assertTrue(manager.resourcesCatalog().contains("file:///readme"));
      assertTrue(manager.promptsCatalog().contains("greet"));

      // Exercise the aggregate tools end-to-end.
      String res = registry.dispatch("read_mcp_resource", Map.of("uri", "file:///readme"));
      assertEquals("contents of file:///readme", res);
      String prompt = registry.dispatch("get_mcp_prompt",
          Map.of("name", "greet", "args", "{\"who\":\"Bob\"}"));
      assertTrue(prompt.contains("Hello Bob"), prompt);
    } finally {
      manager.close();
    }
  }
}
