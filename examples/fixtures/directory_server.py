"""Stdio MCP server: list regular files directly inside the supplied demo directory."""

import json
import os
import sys


def reply(method, params):
    if method == "initialize":
        return {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                "serverInfo": {"name": "samples", "version": "1"}}
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": [{"name": "list_files", "description": "List files in the demo directory, one name per line.",
                           "inputSchema": {"type": "object", "properties": {},
                                           "additionalProperties": False},
                           "annotations": {"readOnlyHint": True}}]}
    if method == "tools/call":
        if params.get("name") != "list_files" or params.get("arguments", {}) != {}:
            raise ValueError("Expected list_files with no arguments")
        with os.scandir(".") as entries:
            names = sorted(e.name for e in entries if e.is_file(follow_symlinks=False))
        return {"content": [{"type": "text", "text": "\n".join(names)}]}
    raise KeyError(method)


for line in sys.stdin:
    request = json.loads(line)
    if "id" not in request:  # Notifications have no reply.
        continue
    response = {"jsonrpc": "2.0", "id": request["id"]}
    try:
        response["result"] = reply(request["method"], request.get("params", {}))
    except KeyError:
        response["error"] = {"code": -32601, "message": "Method not found"}
    except ValueError:
        response["error"] = {"code": -32602, "message": "Invalid tool or arguments"}
    print(json.dumps(response), flush=True)
