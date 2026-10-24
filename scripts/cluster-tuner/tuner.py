#!/usr/bin/env python3
"""Local-only ADB bridge for the Autunn Play instrument layout panel (standard library)."""

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import webbrowser

PACKAGE = "com.shihab.diplay"
COMPONENT = PACKAGE + "/com.shilapi.xcertplay.ClusterLayoutReceiver"
ACTION = PACKAGE + ".action.CLUSTER_LAYOUT"
CONTENTS = ("MAP_WITH_CUSTOM_CARD", "INSTRUMENTS", "TURN_CARD")


class TunerError(Exception):
    pass


def find_adb(explicit=None):
    candidates = [explicit, shutil.which("adb"),
                  str(Path.home() / "Library/Android/sdk/platform-tools/adb")]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates.append(str(Path(sdk) / "platform-tools/adb"))
    for candidate in candidates:
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return str(Path(candidate).resolve())
    raise TunerError("未找到 ADB。请安装 Android platform-tools，或使用 --adb 指定 adb 路径。")


def parse_reply(output):
    for line in output.splitlines():
        if "Broadcast completed:" not in line or "data=" not in line:
            continue
        text = line.split("data=", 1)[1].strip()
        if text.startswith('"') and text.endswith('"'):
            text = text[1:-1]
        try:
            result = json.loads(text)
        except json.JSONDecodeError:
            try:
                result = json.loads(json.loads('"' + text + '"'))
            except (json.JSONDecodeError, TypeError):
                continue
        if not isinstance(result, dict) or not isinstance(result.get("ok"), bool):
            continue
        if not result["ok"]:
            raise TunerError(result.get("error") or "App 拒绝了布局参数")
        if result.get("protocol") != 2:
            raise TunerError("调试接口版本不匹配，请使用配套的仪表调节 APK。")
        if (type(result.get("supported")) is not bool or result.get("width") != 1920
                or result.get("height") != 720):
            raise TunerError("App 返回的仪表参数不完整，请确认安装的是配套仪表调节 APK。")
        for target in ("tbt", "ete"):
            placement = result.get(target)
            if not isinstance(placement, dict):
                raise TunerError("App 返回的仪表参数不完整")
            for key, low, high in (("x", 0, 1919), ("y", 0, 719), ("scale", 50, 150),
                                   ("width", 80, 1920), ("height", 40, 720)):
                value = placement.get(key)
                if type(value) is not int or not low <= value <= high:
                    raise TunerError("App 返回的仪表参数无效")
        if "contentSelection" in result:
            if type(result["contentSelection"]) is not bool:
                raise TunerError("App 返回的内容切换能力无效")
            if result["contentSelection"]:
                if (result.get("content") not in CONTENTS
                        or type(result.get("sessionRunning")) is not bool
                        or "displayedUrl" not in result
                        or result["displayedUrl"] not in (
                            None, "maps:/car/instrumentcluster/map",
                            "maps:/car/instrumentcluster/instructioncard",
                            "maps:/car/instrumentcluster")):
                    raise TunerError("App 返回的内容切换状态无效")
        return result
    raise TunerError("未收到 App 的确认。请确认已安装配套仪表调节 APK 并打开过 Autunn Play；NAVI29 不支持此接口。")


class AdbBridge:
    def __init__(self, adb):
        self.adb = adb
        self.lock = threading.Lock()

    def run(self, arguments):
        try:
            result = subprocess.run([self.adb] + arguments, capture_output=True, text=True,
                                    timeout=15, check=False)
        except subprocess.TimeoutExpired as exc:
            raise TunerError("ADB 超时，请检查 USB 和工程模式 ADB 开关。") from exc
        except OSError as exc:
            raise TunerError("无法启动 ADB，请检查工具路径。") from exc
        if result.returncode:
            raise TunerError("ADB 执行失败，请检查 USB 调试授权和设备连接。")
        return result.stdout

    def devices(self):
        devices = []
        for line in self.run(["devices", "-l"]).splitlines():
            fields = line.split()
            if len(fields) < 2 or fields[1] not in ("device", "unauthorized", "offline"):
                continue
            model = next((f.split(":", 1)[1].replace("_", " ") for f in fields[2:]
                          if f.startswith("model:")), fields[0])
            devices.append({"serial": fields[0], "state": fields[1], "name": model})
        return devices

    def request(self, payload):
        if not isinstance(payload, dict):
            raise TunerError("请求必须是一个 JSON 对象")
        serial = payload.get("serial")
        op = payload.get("op", "get")
        if op not in ("get", "set", "reset", "original", "content"):
            raise TunerError("未知操作")
        target = payload.get("target")
        extras = ["--es", "op", op]
        if op == "content":
            content = payload.get("content")
            if content not in CONTENTS:
                raise TunerError("请选择有效的仪表内容")
            extras += ["--es", "content", content]
        if op in ("set", "reset"):
            if target not in ("tbt", "ete"):
                raise TunerError("请选择转向提示或到达／里程信息栏")
            extras += ["--es", "target", target]
        if op == "set":
            for key, low, high in (("x", 0, 1919), ("y", 0, 719), ("scale", 50, 150),
                                   ("width", 80, 1920), ("height", 40, 720)):
                value = payload.get(key)
                if type(value) is not int or not low <= value <= high:
                    raise TunerError("%s 必须是 %s～%s 的整数" % (key, low, high))
                extras += ["--ei", key, str(value)]
        with self.lock:
            if not any(d["serial"] == serial and d["state"] == "device" for d in self.devices()):
                raise TunerError("所选设备未连接或未授权。请刷新设备并确认车机 USB 调试。")
            output = self.run(["-s", serial, "shell", "am", "broadcast", "--include-stopped-packages",
                               "-n", COMPONENT, "-a", ACTION] + extras)
            return parse_reply(output)


class PanelServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, bridge):
        self.bridge = bridge
        self.token = secrets.token_urlsafe(32)
        super().__init__(address, PanelHandler)
        self.origin = "http://127.0.0.1:%s" % self.server_port


class PanelHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        # Layout commands do not need a second log containing device serials.
        pass

    def send(self, status, value, mime="application/json; charset=utf-8"):
        body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.end_headers()
        self.wfile.write(body)

    def local_request(self):
        if self.headers.get("Host") != "127.0.0.1:%s" % self.server.server_port:
            self.send(403, {"error": "只接受本机访问"})
            return False
        origin = self.headers.get("Origin")
        if origin and origin != self.server.origin:
            self.send(403, {"error": "请求来源不匹配"})
            return False
        return True

    def do_GET(self):
        if not self.local_request():
            return
        if self.path == "/":
            html = Path(__file__).with_name("panel.html").read_text(encoding="utf-8")
            self.send(200, html.replace("__SESSION_TOKEN__", self.server.token).encode(),
                      "text/html; charset=utf-8")
        elif self.path == "/api/devices":
            try:
                self.send(200, {"devices": self.server.bridge.devices()})
            except TunerError as exc:
                self.send(400, {"error": str(exc)})
        else:
            self.send(404, {"error": "页面不存在"})

    def do_POST(self):
        if not self.local_request():
            return
        if not secrets.compare_digest(self.headers.get("X-Autunn-Session", ""), self.server.token):
            self.send(403, {"error": "会话已过期，请刷新页面"})
            return
        if self.path != "/api/layout":
            self.send(404, {"error": "操作不存在"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4096:
                raise TunerError("请求长度无效")
            payload = json.loads(self.rfile.read(length))
            self.send(200, self.server.bridge.request(payload))
        except (TunerError, ValueError, UnicodeDecodeError) as exc:
            self.send(400, {"error": str(exc)})


def main():
    parser = argparse.ArgumentParser(description="Autunn Play 仪表位置调节工具")
    parser.add_argument("--adb", help="adb 可执行文件路径")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    try:
        server = PanelServer(("127.0.0.1", args.port), AdbBridge(find_adb(args.adb)))
    except (OSError, TunerError) as exc:
        parser.exit(1, "无法启动：%s\n" % exc)
    print("Autunn Play 仪表调节面板：" + server.origin, flush=True)
    print("保持此窗口打开；按 Control+C 退出。", flush=True)
    if not args.no_browser:
        webbrowser.open(server.origin)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
