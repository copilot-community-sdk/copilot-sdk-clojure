"""Run the native Bash permission example with a local model and an empty home."""

from optional_smoke import run, tool_call


def completion(request):
    assert [tool["function"]["name"] for tool in request["tools"]] == ["bash"]
    last = request["messages"][-1]
    for greeting in ("Hello", "Goodbye"):
        command = f"/usr/bin/printf '{greeting} from Bash.\\n'"
        if last["role"] == "user" and command in last["content"]:
            delta, finish = tool_call("bash", {"command": command, "description": "Print a greeting"})
            delta["tool_calls"][0]["id"] = greeting.lower()
            return delta, finish
    assert last["role"] == "tool"
    if last["tool_call_id"] == "hello":
        assert last["content"].splitlines()[0] == "Hello from Bash.", last["content"]
    else:
        assert last["tool_call_id"] == "goodbye" and "Only the demo greeting is allowed." in last["content"]
    return {"role": "assistant", "content": last["content"]}, "stop"


if __name__ == "__main__":
    run("fixtures.permission-smoke", completion, 4)
