import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const protocolVersions = ["2025-06-18", "2024-11-05"];

async function readConfig() {
  for (const name of [".pi/mcp.json", ".mcp.json"]) {
    try {
      const parsed = JSON.parse(await readFile(resolve(process.cwd(), name), "utf8"));
      const server = parsed?.mcpServers?.kompile;
      if (server) return server;
    } catch (_) {
      // The adapter is optional; an absent or malformed project file is reported below.
    }
  }
  return null;
}

class JsonRpcClient {
  constructor(server) {
    this.server = server;
    this.nextId = 1;
    this.pending = new Map();
    this.endpoint = null;
    this.child = null;
  }

  async connect() {
    if (this.server.url) {
      await this.connectSse(this.server.url);
    } else {
      const command = this.server.command;
      if (!command) throw new Error("Kompile MCP server has neither url nor command");
      this.child = spawn(command, this.server.args || [], {
        cwd: this.server.cwd || process.cwd(),
        stdio: ["pipe", "pipe", "inherit"],
      });
      const lines = createInterface({ input: this.child.stdout });
      lines.on("line", (line) => this.receive(line));
      this.child.on("error", (error) => this.fail(error));
      this.child.on("exit", (code) => this.fail(new Error("MCP server exited: " + code)));
      this.endpoint = "stdio";
    }

    let lastError;
    for (const version of protocolVersions) {
      try {
        await this.request("initialize", {
          protocolVersion: version,
          capabilities: {},
          clientInfo: { name: "kompile-pi-adapter", version: "2.21.0" },
        });
        this.notify("notifications/initialized", {});
        return;
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError || new Error("MCP initialization failed");
  }

  async connectSse(url) {
    const response = await fetch(url, { headers: { Accept: "text/event-stream" } });
    if (!response.ok || !response.body) {
      throw new Error("MCP SSE connection failed: HTTP " + response.status);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let event = "";
    let data = "";
    const consume = async () => {
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        for (const line of decoder.decode(next.value, { stream: true }).split(/\r?\n/)) {
          if (line.startsWith("event:")) event = line.slice(6).trim();
          else if (line.startsWith("data:")) data += line.slice(5).trim();
          else if (!line) {
            if (event === "endpoint") {
              this.endpoint = new URL(data, url).toString();
            } else if (data) {
              this.receive(data);
            }
            event = "";
            data = "";
          }
        }
      }
    };
    consume().catch((error) => this.fail(error));
    for (let i = 0; i < 100 && !this.endpoint; i++) {
      await new Promise((r) => setTimeout(r, 25));
    }
    if (!this.endpoint) throw new Error("MCP SSE endpoint was not advertised");
  }

  request(method, params) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.send({ jsonrpc: "2.0", id, method, params });
    });
  }

  notify(method, params) {
    this.send({ jsonrpc: "2.0", method, params });
  }

  send(message) {
    const encoded = JSON.stringify(message);
    if (this.endpoint === "stdio") {
      this.child.stdin.write(encoded + "\n");
    } else if (this.endpoint) {
      fetch(this.endpoint, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: encoded,
      }).catch((error) => this.fail(error));
    } else {
      throw new Error("MCP transport is not connected");
    }
  }

  receive(line) {
    try {
      const message = JSON.parse(line);
      if (message.id !== undefined && this.pending.has(message.id)) {
        const pending = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) pending.reject(new Error(message.error.message || "MCP request failed"));
        else pending.resolve(message.result);
      }
    } catch (_) {
      // Ignore non-JSON diagnostics on the MCP stream.
    }
  }

  fail(error) {
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
  }

  close() {
    this.child?.kill();
    this.child = null;
  }
}

export default async function (pi) {
  const server = await readConfig();
  if (!server) {
    console.error("[kompile] no mcpServers.kompile configuration found");
    return;
  }

  const client = new JsonRpcClient(server);
  await client.connect();
  if (typeof pi.on === "function") {
    pi.on("session_shutdown", () => client.close());
  }
  const listed = await client.request("tools/list", {});
  for (const tool of listed?.tools || []) {
    const name = "mcp__kompile__" + tool.name.replace(/[^a-zA-Z0-9_-]/g, "_");
    pi.registerTool({
      name,
      label: tool.name,
      description: tool.description || "Kompile MCP tool",
      parameters: tool.inputSchema || { type: "object", properties: {} },
      execute: async (_callId, params) => {
        const result = await client.request("tools/call", {
          name: tool.name,
          arguments: params || {},
        });
        return {
          content: (result?.content || []).map((item) => {
            if (item.type === "image") {
              return { type: "image", data: item.data, mimeType: item.mimeType || "image/png" };
            }
            return { type: "text", text: item.text ?? JSON.stringify(item) };
          }),
          details: result,
        };
      },
    });
  }
}
