"""Exercise native startup admission over real MCP stdio and Windows process/port observation."""

import contextlib
import json
import os
import queue
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time


class Mcp:
    def __init__(self, exe, address, manifest=None):
        args = [exe, "--server-address", address]
        if manifest is not None:
            args += ["--startup-config", manifest]
        self.proc = subprocess.Popen(
            args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
        )
        self.frames = queue.Queue()
        self.reader = threading.Thread(target=self.read_frames)
        self.reader.start()
        self.next_id = 0
        self.send("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {}})
        assert "result" in self.receive()
        self.send("notifications/initialized")

    def read_frames(self):
        try:
            for line in self.proc.stdout:
                self.frames.put(json.loads(line))
        finally:
            self.frames.put(None)

    def send(self, method, params=None):
        request = {"jsonrpc": "2.0", "method": method}
        if not method.startswith("notifications/"):
            self.next_id += 1
            request["id"] = self.next_id
        if params is not None:
            request["params"] = params
        self.proc.stdin.write(json.dumps(request) + "\n")
        self.proc.stdin.flush()
        return request.get("id")

    def receive(self, request_id=None):
        response = self.frames.get(timeout=20)
        assert response is not None, "MCP closed before replying"
        if request_id is not None:
            assert response["id"] == request_id, response
        return response

    def tool(self, workspace, name="vibris_ensure_runtime", **args):
        return self.send("tools/call", {
            "name": name, "arguments": {"worktree_root": str(workspace), **args},
        })

    def close(self):
        if not self.proc.stdin.closed:
            self.proc.stdin.close()
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=10)
            raise
        finally:
            self.reader.join(timeout=10)
            self.proc.stdout.close()
        assert self.proc.returncode == 0, self.proc.returncode


def payload(response):
    return response["result"]["structuredContent"]


def assert_success(response):
    result = payload(response)
    assert result["success"], result
    return result["result"]


def assert_error(response, code, phase=None):
    result = payload(response)
    assert not result["success"] and result["error"]["code"] == code, result
    if phase is not None:
        assert result["error"]["details"]["phase"] == phase, result


def await_condition(predicate, description):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.025)
    raise AssertionError(description)


def run(exe, fixture, root):
    workspace_a = root / "worktree A"
    workspace_b = root / "worktree B"
    git_env = {name: value for name, value in os.environ.items() if not name.upper().startswith("GIT_")}
    for workspace in (workspace_a, workspace_b):
        subprocess.run(["git", "init", "--quiet", workspace], check=True, env=git_env)
    game = root / "Dedicated fixture game"
    game_config = game / "config" / "vibris"
    game_config.mkdir(parents=True)
    launch_guard = game_config / "mcp-start.guard"
    state = root / "fixture state with spaces"
    state.mkdir()
    game_exe = root / "java.exe"
    shutil.copyfile(fixture, game_exe)
    with socket.socket() as port_reservation:
        port_reservation.bind(("127.0.0.1", 0))
        port = port_reservation.getsockname()[1]
    address = f"127.0.0.1:{port}"
    manifest = root / "startup.json"
    arguments = ["--port", str(port), "--work-root", str(state), "--gameDir", str(game)]
    manifest.write_text(json.dumps({
        "game_directory": str(game), "launcher_executable": str(game_exe), "launcher_arguments": arguments,
    }), encoding="utf-8")
    batch = root / "configured restart entry.bat"
    batch.write_text(
        f'@echo off\n"%~dp0java.exe" --port {port} --work-root "%~dp0{state.name}" --gameDir "%~dp0{game.name}"\n',
        encoding="ascii",
    )
    server_config = game_config / "server.json"
    batch_manifest = root / "startup-using-restart.json"
    batch_manifest.write_text(json.dumps({
        "game_directory": str(game), "legacy_restart_entry": str(batch), "use_configured_restart_entry": True,
    }), encoding="utf-8")
    launches = state / "launches"
    bind = state / "bind"
    restart = state / "restart"
    stop = state / "exit"
    stopped = state / "stopped"

    def launch_count():
        return len(launches.read_text().splitlines()) if launches.exists() else 0

    def stop_count():
        return len(stopped.read_text().splitlines()) if stopped.exists() else 0

    def online():
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                return True
        except OSError:
            return False

    with contextlib.ExitStack() as stack:
        def client(config=manifest):
            mcp = Mcp(exe, address, config)
            stack.callback(mcp.close)
            return mcp

        try:
            a = client(batch_manifest)
            b = client(batch_manifest)
            server_config.write_text(json.dumps({"restart_executable": str(game_exe)}), encoding="utf-8")
            mismatch = a.tool(workspace_a, timeout_ms=0)
            assert_error(a.receive(mismatch), "STARTUP_NOT_CONFIGURED")
            assert not launch_guard.exists()
            server_config.write_text(json.dumps({"restart_executable": str(batch)}), encoding="utf-8")
            disabled = client(None)
            missing = disabled.tool(workspace_a, timeout_ms=0)
            assert_error(disabled.receive(missing), "STARTUP_NOT_CONFIGURED")
            tools = a.send("tools/list")
            tool = next(item for item in a.receive(tools)["result"]["tools"]
                        if item["name"] == "vibris_ensure_runtime")
            assert set(tool["inputSchema"]["properties"]) == {"worktree_root", "timeout_ms"}, tool
            invalid = a.tool(workspace_a, timeout_ms=0, launcher_executable=str(game_exe))
            assert a.receive(invalid)["error"]["code"] == -32602

            status_id = a.tool(workspace_a, "vibris_get_status")
            assert_error(a.receive(status_id), "SERVER_OFFLINE")
            assert launch_count() == 0 and not launch_guard.exists()

            with socket.socket() as foreign:
                foreign.bind(("127.0.0.1", port))
                foreign.listen()
                unknown = a.tool(workspace_a, timeout_ms=0)
                assert_error(a.receive(unknown), "STARTUP_IDENTITY_UNKNOWN")
                assert launch_count() == 0

            observation_only = root / "observe.json"
            observation_only.write_text(json.dumps({"game_directory": str(game)}), encoding="utf-8")
            observer = client(observation_only)
            observe = observer.tool(workspace_a, timeout_ms=0)
            assert_error(observer.receive(observe), "STARTUP_LAUNCH_NOT_CONFIGURED")

            bad_launcher = root / "bad-launcher.exe"
            bad_launcher.write_bytes(b"not a Windows executable")
            invalid_manifest = root / "bad-launch.json"
            invalid_manifest.write_text(json.dumps({
                "game_directory": str(game), "launcher_executable": str(bad_launcher),
            }), encoding="utf-8")
            broken = client(invalid_manifest)
            bad_launch = broken.tool(workspace_a, timeout_ms=0)
            assert_error(broken.receive(bad_launch), "STARTUP_LAUNCH_FAILED")
            assert launch_guard.read_bytes() == bytes(8)

            first = a.tool(workspace_a, timeout_ms=1_500)
            second = b.tool(workspace_b, timeout_ms=1_500)
            await_condition(lambda: launch_count() == 1, "launcher did not start exactly once")
            assert_error(a.receive(first), "STARTUP_PENDING")
            assert_error(b.receive(second), "STARTUP_PENDING")

            abandoned = client(batch_manifest)
            abandoned.tool(workspace_b, timeout_ms=180_000)
            abandoned.close()
            assert launch_count() == 1

            retry = b.tool(workspace_b, timeout_ms=10_000)
            a_follow = a.tool(workspace_a, timeout_ms=10_000)
            # tools/list completes while the instance is gated, on the same stdio connection.
            discovery = a.send("tools/list")
            assert "tools" in a.receive(discovery)["result"]
            bind.touch()
            first_online = assert_success(a.receive(a_follow))
            second_online = assert_success(b.receive(retry))
            assert first_online["process_id"] == second_online["process_id"]
            assert not first_online["launched_by_this_call"] and not second_online["launched_by_this_call"]
            assert first_online["status"]["operational"] and launch_count() == 1
            assert launch_guard.read_bytes() == bytes(8)

            status_id = b.tool(workspace_b, "vibris_get_status")
            status = assert_success(b.receive(status_id))
            assert status["operational"] and status["workspace_id"] == second_online["workspace_id"]
            assert first_online["workspace_id"] != second_online["workspace_id"]

            restart.touch()
            planned = a.tool(workspace_a, timeout_ms=0)
            assert_error(a.receive(planned), "STARTUP_PENDING", "planned_restart")
            stop.touch()
            await_condition(lambda: stop_count() == 1, "planned runtime did not stop")
            still_pending = b.tool(workspace_b, timeout_ms=0)
            assert_error(b.receive(still_pending), "STARTUP_PENDING")
            assert launch_count() == 1
            restart.unlink()
            stop.unlink()
            replacement = subprocess.Popen([game_exe, *arguments], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            stack.callback(lambda: replacement.terminate() if replacement.poll() is None else None)
            await_condition(online, "replacement runtime did not bind")
            reconnected = a.tool(workspace_a, timeout_ms=5_000)
            state_after = assert_success(a.receive(reconnected))
            assert state_after["process_id"] == replacement.pid
            assert state_after["process_id"] != first_online["process_id"] and launch_count() == 2
            assert state_after["status"]["operational"] and not state_after["launched_by_this_call"]

            stop.touch()
            replacement.wait(timeout=10)
            stop.unlink()
            native = client()
            cold_again = native.tool(workspace_a, timeout_ms=10_000)
            later = assert_success(native.receive(cold_again))
            assert later["launched_by_this_call"] and later["status"]["operational"] and launch_count() == 3
            stop.touch()
            await_condition(lambda: stop_count() == 3, "second cold start did not stop")
            launch_guard.write_bytes(struct.pack("<Q", int(time.time()) - 3600))
            expired = a.tool(workspace_a, timeout_ms=0)
            assert_error(a.receive(expired), "STARTUP_UNCONFIRMED")
            assert launch_count() == 3
            print("Verified the deployed restart batch match, concurrent batch startup, timeout/EOF retry, "
                  "cross-worktree handshake, planned replacement, native launch and unresolved receipt.")
        finally:
            if launch_count() > stop_count():
                stop.touch()
                bind.touch()
                await_condition(lambda: stop_count() >= launch_count(), "fixture runtime did not stop")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="vibris-start-") as directory:
        run(Path(sys.argv[1]), Path(sys.argv[2]), Path(directory))
