"""Invoke the built stdio MCP directly and retain its complete JSON-RPC response."""
import argparse
import json
import pathlib
import subprocess
import sys
import threading


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("exe", type=pathlib.Path)
    parser.add_argument("request", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    request = json.loads(args.request.read_text(encoding="utf-8-sig"))
    process = subprocess.Popen(
        [str(args.exe), "--server-address", "127.0.0.1:50051"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", creationflags=subprocess.CREATE_NO_WINDOW,
    )
    errors = []
    reader = threading.Thread(target=lambda: errors.extend(process.stderr), daemon=True)
    reader.start()
    sequence = 0

    def call(method, params):
        nonlocal sequence
        sequence += 1
        process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": sequence,
                                       "method": method, "params": params}) + "\n")
        process.stdin.flush()
        while True:
            line = process.stdout.readline()
            if not line:
                raise RuntimeError("MCP exited before responding: " + "".join(errors))
            response = json.loads(line)
            if response.get("id") == sequence:
                return response

    try:
        initialized = call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                           "clientInfo": {"name": "vibris-manual-qa", "version": "1"}})
        if "error" in initialized:
            raise RuntimeError(initialized["error"])
        process.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
        process.stdin.flush()
        response = call(request.get("method", "tools/call"), request["params"])
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(response, indent=2), encoding="utf-8")
        print(json.dumps(response, separators=(",", ":")))
        outcome = response.get("result", {}).get("structuredContent", {})
        result = outcome.get("result", {})
        if ("error" in response or response.get("result", {}).get("isError", False)
                or outcome.get("success") is False or result.get("success") is False
                or result.get("workflow_state") == "failed"):
            raise RuntimeError("MCP request failed; see " + str(args.output))
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        reader.join(timeout=5)
        sys.stderr.write("".join(errors))


if __name__ == "__main__":
    main()
