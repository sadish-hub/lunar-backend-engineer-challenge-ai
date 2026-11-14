#!/usr/bin/env python3
"""Live vendor-harness e2e with read verification.

Ensures the app is up, runs ./rockets/<platform>/rockets through a recording
reverse proxy (harness stdout has no channel UUIDs), captures channel IDs +
envelopes from POST bodies, reduces expected rocket state, polls until apply
catches up, then asserts GET /rockets and GET /rockets/{id}.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASE = os.environ.get("E2E_BASE_URL", "http://localhost:8088")
DEFAULT_MAX_MESSAGES = int(os.environ.get("E2E_MAX_MESSAGES", "60"))
DEFAULT_MESSAGE_DELAY = os.environ.get("E2E_MESSAGE_DELAY", "100ms")
DEFAULT_CONCURRENCY = int(os.environ.get("E2E_CONCURRENCY", "1"))
DEFAULT_POLL_TIMEOUT = float(os.environ.get("E2E_POLL_TIMEOUT_SEC", "90"))
DEFAULT_SEED = int(os.environ.get("E2E_SEED", "444"))

_ANSI_RESET = "\033[0m"
_ANSI_GREEN_BOLD = "\033[32;1m"
_ANSI_RED_BOLD = "\033[31;1m"


class E2eError(Exception):
    """Expected e2e failure; entrypoint prints red FAIL + reason."""


class E2eRetryable(E2eError):
    """Apply still catching up; poller may retry until timeout."""


def color_enabled() -> bool:
    if os.environ.get("NO_COLOR", ""):
        return False
    return sys.stdout.isatty()


def _paint(text: str, code: str) -> str:
    if not color_enabled():
        return text
    return f"{code}{text}{_ANSI_RESET}"


def print_pass() -> None:
    print(_paint("PASS", _ANSI_GREEN_BOLD))


def print_fail(reason: str) -> None:
    print(f"{_paint('FAIL', _ANSI_RED_BOLD)}: {reason}")


class CaptureStore:
    """First-wins store of harness POST envelopes keyed by (channel, messageNumber)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._first: dict[tuple[str, int], dict[str, Any]] = {}
        self._order: list[str] = []
        self.forward_errors = 0
        self.post_count = 0

    def record(self, envelope: dict[str, Any]) -> None:
        meta = envelope.get("metadata") or {}
        channel = meta.get("channel")
        mn = meta.get("messageNumber")
        if not isinstance(channel, str) or not isinstance(mn, int):
            return
        key = (channel, mn)
        with self._lock:
            self.post_count += 1
            if key in self._first:
                return
            self._first[key] = envelope
            if channel not in self._order:
                self._order.append(channel)

    def channels(self) -> list[str]:
        with self._lock:
            return list(self._order)

    def envelopes_for(self, channel: str) -> list[dict[str, Any]]:
        with self._lock:
            items = [
                env
                for (ch, mn), env in self._first.items()
                if ch == channel
            ]
        items.sort(key=lambda e: e["metadata"]["messageNumber"])
        return items


def reduce_channel(channel: str, envelopes: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Mirror apply: first-wins envelopes, contiguous from messageNumber 1 until a gap.

    Returns None when no RocketLaunched has been applied yet (GET should 404).
    """
    by_mn = {int(e["metadata"]["messageNumber"]): e for e in envelopes}
    state: dict[str, Any] | None = None
    mn = 1
    while mn in by_mn:
        env = by_mn[mn]
        msg_type = env["metadata"]["messageType"]
        msg = env.get("message") or {}
        if msg_type == "RocketLaunched":
            if state is None:
                state = {
                    "id": channel,
                    "type": msg["type"],
                    "speed": int(msg["launchSpeed"]),
                    "mission": msg["mission"],
                    "exploded": False,
                    "explosionReason": None,
                    "lastApplied": mn,
                }
            else:
                state["lastApplied"] = mn
        elif state is None:
            # Pre-launch contiguous message — apply fails closed; no projection row.
            return None
        elif msg_type == "RocketSpeedIncreased":
            state["speed"] = int(state["speed"]) + int(msg["by"])
            state["lastApplied"] = mn
        elif msg_type == "RocketSpeedDecreased":
            state["speed"] = int(state["speed"]) - int(msg["by"])
            state["lastApplied"] = mn
        elif msg_type == "RocketExploded":
            state["exploded"] = True
            state["explosionReason"] = msg.get("reason")
            state["lastApplied"] = mn
        elif msg_type == "RocketMissionChanged":
            state["mission"] = msg["newMission"]
            state["lastApplied"] = mn
        else:
            raise E2eError(f"channel {channel} field messageType: unknown {msg_type!r}")
        mn += 1
    return state


def pick_binary() -> Path:
    system = platform.system().lower()
    machine = platform.machine().lower()
    if system == "darwin":
        arch = "arm64" if machine in ("arm64", "aarch64") else "amd64"
        rel = f"darwin_{arch}"
    elif system == "linux":
        arch = "arm64" if machine in ("arm64", "aarch64") else "amd64"
        rel = f"linux_{arch}"
    else:
        arch = "amd64" if "64" in machine else "386"
        rel = f"windows_{arch}"
    name = "rockets.exe" if system.startswith("win") else "rockets"
    path = REPO_ROOT / "rockets" / rel / name
    if not path.is_file():
        raise E2eError(f"harness binary not found: {path}")
    return path


def http_json(method: str, url: str, body: bytes | None = None, timeout: float = 10.0) -> tuple[int, Any]:
    req = urllib.request.Request(url, data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            data = json.loads(raw.decode()) if raw else None
            return resp.status, data
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            data = json.loads(raw.decode()) if raw else None
        except json.JSONDecodeError:
            data = raw.decode(errors="replace")
        return e.code, data


def ensure_up(base: str, do_up: bool) -> None:
    health = f"{base.rstrip('/')}/actuator/health"
    try:
        code, data = http_json("GET", health, timeout=3.0)
        if code == 200 and isinstance(data, dict) and data.get("status") == "UP":
            print(f"e2e: app already UP at {base}")
            return
    except Exception:
        pass
    if not do_up:
        raise E2eError(f"e2e: app not UP at {health}; run `make up` or pass --up")
    print("e2e: bringing stack up via `make up` …")
    subprocess.run(["make", "up"], cwd=REPO_ROOT, check=True)
    deadline = time.time() + 180
    while time.time() < deadline:
        try:
            code, data = http_json("GET", health, timeout=3.0)
            if code == 200 and isinstance(data, dict) and data.get("status") == "UP":
                print("e2e: app UP")
                return
        except Exception:
            pass
        time.sleep(2)
    raise E2eError(f"e2e: timed out waiting for {health}")


def docker_bin() -> str:
    path = shutil.which("docker")
    if path:
        return path
    for candidate in ("/usr/local/bin/docker", "/opt/homebrew/bin/docker"):
        if Path(candidate).is_file():
            return candidate
    raise E2eError("e2e: docker binary not found on PATH")


def reset_projection_db() -> None:
    """Clear prior harness state so seed-stable channel IDs match this run's envelopes."""
    sql = "TRUNCATE messages, channel_work, rockets CASCADE;"
    print("e2e: truncating messages/channel_work/rockets …")
    proc = subprocess.run(
        [
            docker_bin(),
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "lunar",
            "-d",
            "lunar",
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            sql,
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise E2eError(
            "e2e: DB truncate failed (need compose postgres up):\n"
            f"{proc.stdout}\n{proc.stderr}"
        )


def start_proxy(upstream_messages: str, store: CaptureStore) -> tuple[ThreadingHTTPServer, str]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:  # quiet
            return

        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length) if length else b""
            try:
                envelope = json.loads(body.decode())
                store.record(envelope)
            except (UnicodeDecodeError, json.JSONDecodeError):
                pass
            req = urllib.request.Request(
                upstream_messages,
                data=body,
                method="POST",
                headers={"Content-Type": self.headers.get("Content-Type", "application/json")},
            )
            try:
                with urllib.request.urlopen(req, timeout=30.0) as resp:
                    payload = resp.read()
                    self.send_response(resp.status)
                    ctype = resp.headers.get("Content-Type")
                    if ctype:
                        self.send_header("Content-Type", ctype)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
            except urllib.error.HTTPError as e:
                payload = e.read()
                self.send_response(e.code)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except Exception as exc:  # noqa: BLE001
                store.forward_errors += 1
                msg = str(exc).encode()
                self.send_response(502)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(msg)))
                self.end_headers()
                self.wfile.write(msg)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    host, port = server.server_address
    proxy_url = f"http://{host}:{port}/messages"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, proxy_url


def first_field_mismatch(channel: str, actual: dict[str, Any], expected: dict[str, Any]) -> str | None:
    """Return 'channel <id> field <name>: expected … actual …' or None if equal."""
    checks: list[tuple[str, Any, Any]] = [
        ("id", str(expected["id"]), str(actual.get("id"))),
        ("type", expected["type"], actual.get("type")),
        ("speed", int(expected["speed"]), int(actual["speed"]) if actual.get("speed") is not None else None),
        ("mission", expected["mission"], actual.get("mission")),
        ("exploded", bool(expected["exploded"]), bool(actual.get("exploded"))),
    ]
    if expected["exploded"]:
        checks.append(
            ("explosionReason", expected["explosionReason"], actual.get("explosionReason"))
        )
    for field, exp, got in checks:
        if exp != got:
            return f"channel {channel} field {field}: expected {exp!r} actual {got!r}"
    return None


def fetch_list_ids(base: str) -> set[str]:
    code, body = http_json("GET", f"{base.rstrip('/')}/rockets")
    if code != 200 or not isinstance(body, list):
        raise E2eError(f"GET /rockets failed: status={code} body={body!r}")
    return {str(r.get("id")) for r in body if isinstance(r, dict)}


def verify_channel(
    base: str,
    channel: str,
    expected: dict[str, Any] | None,
    list_ids: set[str],
) -> dict[str, Any] | None:
    """Assert list membership + detail vs expected. expected None ⇒ must be absent / 404."""
    if expected is None:
        if channel in list_ids:
            raise E2eError(
                f"channel {channel} field presence: expected absent from GET /rockets "
                f"actual present"
            )
        code, body = http_json("GET", f"{base.rstrip('/')}/rockets/{channel}")
        if code != 404:
            raise E2eError(
                f"channel {channel} field status: expected 404 actual {code} body={body!r}"
            )
        return None

    if channel not in list_ids:
        raise E2eRetryable(
            f"channel {channel} field presence: expected in GET /rockets actual missing "
            f"(list_len={len(list_ids)})"
        )
    code, detail = http_json("GET", f"{base.rstrip('/')}/rockets/{channel}")
    if code == 404:
        raise E2eRetryable(
            f"channel {channel} field status: expected 200 actual 404"
        )
    if code != 200 or not isinstance(detail, dict):
        raise E2eError(
            f"channel {channel} field status: expected 200 actual {code} body={detail!r}"
        )
    mismatch = first_field_mismatch(channel, detail, expected)
    if mismatch:
        raise E2eError(mismatch)
    return detail


def wait_all_applied(
    base: str,
    expected_by_channel: dict[str, dict[str, Any] | None],
    timeout: float,
) -> dict[str, dict[str, Any] | None]:
    """Poll until every channel matches expected (incl. 404 when None) or timeout."""
    deadline = time.time() + timeout
    last_error = "no attempt yet"
    while time.time() < deadline:
        try:
            list_ids = fetch_list_ids(base)
            details: dict[str, dict[str, Any] | None] = {}
            for channel, expected in expected_by_channel.items():
                details[channel] = verify_channel(base, channel, expected, list_ids)
            return details
        except E2eRetryable as exc:
            last_error = str(exc)
            time.sleep(0.25)
            continue
        # Hard mismatch (wrong field / unexpected presence): fail immediately
    raise E2eError(f"{last_error} (timed out after {timeout:g}s)")


def run_harness(
    binary: Path,
    messages_url: str,
    max_messages: int,
    message_delay: str,
    concurrency: int,
    seed: int,
) -> None:
    cmd = [
        str(binary),
        "launch",
        messages_url,
        f"--max-messages={max_messages}",
        f"--message-delay={message_delay}",
        f"--concurrency-level={concurrency}",
        f"--seed={seed}",
        "--log-level=info",
    ]
    print("e2e: running", " ".join(cmd))
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    sys.stdout.write(proc.stdout)
    sys.stderr.write(proc.stderr)
    if proc.returncode != 0:
        raise E2eError(f"e2e: harness exited {proc.returncode}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE)
    parser.add_argument("--up", action="store_true", help="run `make up` if health is not UP")
    parser.add_argument(
        "--reset-db",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="TRUNCATE messages/channel_work/rockets before harness (default: true)",
    )
    parser.add_argument("--max-messages", type=int, default=DEFAULT_MAX_MESSAGES)
    parser.add_argument("--message-delay", default=DEFAULT_MESSAGE_DELAY)
    parser.add_argument("--concurrency-level", type=int, default=DEFAULT_CONCURRENCY)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--poll-timeout", type=float, default=DEFAULT_POLL_TIMEOUT)
    args = parser.parse_args(argv)

    base = args.base_url.rstrip("/")
    ensure_up(base, do_up=args.up or os.environ.get("E2E_UP", "") == "1")
    if args.reset_db:
        reset_projection_db()

    store = CaptureStore()
    upstream = f"{base}/messages"
    server, proxy_url = start_proxy(upstream, store)
    binary = pick_binary()
    try:
        run_harness(
            binary,
            proxy_url,
            args.max_messages,
            args.message_delay,
            args.concurrency_level,
            args.seed,
        )
    finally:
        server.shutdown()

    channels = store.channels()
    print(
        f"e2e: captured {store.post_count} POSTs, "
        f"{len(channels)} unique channels, forward_errors={store.forward_errors}"
    )
    if store.forward_errors:
        raise E2eError("proxy had forward errors")
    if not channels:
        raise E2eError("no channel IDs captured from harness POSTs")

    expected_by_channel: dict[str, dict[str, Any] | None] = {}
    for channel in channels:
        envelopes = store.envelopes_for(channel)
        expected_by_channel[channel] = reduce_channel(channel, envelopes)
        exp = expected_by_channel[channel]
        if exp is None:
            print(f"e2e: channel {channel}: expect absent (no contiguous launch yet)")
        else:
            print(
                f"e2e: channel {channel}: msgs={len(envelopes)} "
                f"lastApplied={exp['lastApplied']} speed={exp['speed']} "
                f"mission={exp['mission']!r} exploded={exp['exploded']}"
            )

    details = wait_all_applied(base, expected_by_channel, args.poll_timeout)
    present = sum(1 for v in expected_by_channel.values() if v is not None)
    absent = len(expected_by_channel) - present
    summary = {
        "channelsVerified": len(channels),
        "presentInProjection": present,
        "absentExpected404": absent,
        "channels": [
            {
                "id": ch,
                "expected": expected_by_channel[ch],
                "actual": details[ch],
            }
            for ch in channels
        ],
    }
    print(json.dumps(summary, indent=2, sort_keys=True))
    print(f"e2e: verified all {len(channels)} channels from harness capture")
    print_pass()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except E2eError as exc:
        print_fail(str(exc))
        raise SystemExit(1) from exc
