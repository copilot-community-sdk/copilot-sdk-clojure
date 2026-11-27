"""From the checkout: python3 examples/fixtures/optional_smoke.py (cached SDK dependencies)."""

import json
import os
import platform
import shutil
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread


def completion(request):
    assert request["model"] == "probe-deployment"
    tools = {t["function"]["name"] for t in request.get("tools", [])}
    listed = "samples-list_files"
    summary = "summarize_files"
    if listed in tools:
        assert tools in ({listed}, {listed, summary})
        results = [m["content"] for m in request["messages"] if m["role"] == "tool"]
        if not results:
            return tool_call(listed, {})
        assert results[0] == "garden.md\nweather.csv"
        if summary in tools:
            if len(results) == 1:
                return tool_call(summary, {"files": results[0].splitlines()})
            assert results[1:] == ["2 files: garden.md, weather.csv."]
    return {"role": "assistant", "content": "Fixture complete."}, "stop"


def tool_call(name, args):
    call = {"index": 0, "id": "call-" + name, "type": "function",
            "function": {"name": name, "arguments": json.dumps(args)}}
    return {"role": "assistant", "tool_calls": [call]}, "tool_calls"


class Model(BaseHTTPRequestHandler):
    def do_POST(self):
        assert self.path == "/v1/chat/completions" and "Authorization" not in self.headers
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        delta, finish = self.server.completion(request)
        self.server.requests += 1
        chunks = [{"id": "fixture", "object": "chat.completion.chunk", "created": 1,
                   "model": request["model"], "choices": [{"index": 0, "delta": d, "finish_reason": f}]}
                  for d, f in [(delta, None), ({}, finish)]]
        stream = ("".join("data: " + json.dumps(c) + "\n\n" for c in chunks) + "data: [DONE]\n\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(stream)))
        self.end_headers()
        self.wfile.write(stream)


def run(namespace, completion, expected_requests):
    classifier = {("Darwin", "arm64"): "native-darwin-arm64",
                  ("Linux", "x86_64"): "native-linux-x64"}[platform.system(), platform.machine()]
    cp = subprocess.check_output(["clojure", "-Spath", "-M:examples:" + classifier], text=True).strip()
    cp = os.pathsep.join(str(Path(p).resolve()) for p in cp.split(os.pathsep))
    java = shutil.which("java")
    work = Path("target/example-work").resolve()
    work.mkdir(exist_ok=True, parents=True)
    with TemporaryDirectory(dir=work) as home, HTTPServer(("127.0.0.1", 0), Model) as model:
        port = model.server_port
        model.completion = completion
        model.requests = 0
        thread = Thread(target=model.serve_forever)
        thread.start()
        try:
            guard = (["/usr/bin/sandbox-exec", "-p", "(version 1)(allow default)(deny network*)"
                      f'(allow network-outbound (remote ip "localhost:{port}"))']
                     if platform.system() == "Darwin" else [])
            env = {"HOME": home, "USERPROFILE": home, "TMPDIR": home, "PATH": os.defpath,
                   "COPILOT_DEMO_BASE_URL": f"http://127.0.0.1:{port}/v1", "COPILOT_DEMO_MODEL": "probe-deployment",
                   "COPILOT_DEMO_PYTHON": sys.executable, "COPILOT_DEMO_OPENAI_KEY": "unused-test-value"}
            subprocess.run(guard + [java, "--enable-native-access=ALL-UNNAMED", "-Dclojure.main.report=stderr",
                                    "-Duser.home=" + home, "-Djava.io.tmpdir=" + home, "-cp", cp,
                                    "clojure.main", "-m", namespace], cwd=home, env=env,
                           check=True, timeout=210)
            assert model.requests == expected_requests
        finally:
            model.shutdown()
            thread.join()


if __name__ == "__main__":
    run("fixtures.optional-smoke", completion, 7)
