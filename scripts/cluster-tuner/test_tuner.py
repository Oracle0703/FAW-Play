import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from tuner import AdbBridge, COMPONENT, PanelServer, TunerError, parse_reply


REPLY = {"ok": True, "protocol": 2, "supported": True, "width": 1920, "height": 720,
         "tbt": {"x": 1154, "y": 160, "scale": 100, "width": 666, "height": 160},
         "ete": {"x": 1154, "y": 328, "scale": 100, "width": 666, "height": 80}}


class FakeAdb(AdbBridge):
    def __init__(self):
        super().__init__("unused-adb")
        self.calls = []

    def run(self, arguments):
        self.calls.append(arguments)
        if arguments == ["devices", "-l"]:
            return "List of devices attached\nCAR device model:HONG_QI\nPHONE unauthorized\n"
        return 'Broadcast completed: result=-1, data="' + json.dumps(REPLY) + '"\n'


class BridgeTest(unittest.TestCase):
    def test_accepts_only_a_confirmed_protocol_reply(self):
        self.assertEqual(REPLY, parse_reply('Broadcast completed: result=-1, data="' + json.dumps(REPLY) + '"'))
        for output in ["Broadcast completed: result=0", "Permission Denial",
                       'Broadcast completed: result=0, data="{}"']:
            with self.assertRaises(TunerError):
                parse_reply(output)
        with self.assertRaisesRegex(TunerError, "位置超界"):
            parse_reply('Broadcast completed: result=0, data="{"ok":false,"error":"位置超界"}"')
        for field in ("supported", "width", "height", "tbt", "ete"):
            incomplete = dict(REPLY)
            incomplete.pop(field)
            with self.assertRaises(TunerError):
                parse_reply('Broadcast completed: result=-1, data="' + json.dumps(incomplete) + '"')

    def test_uses_the_selected_device_and_explicit_receiver_with_validated_arguments(self):
        bridge = FakeAdb()
        bridge.request({"serial": "CAR", "op": "set", "target": "ete", "x": 100, "y": 540,
                        "scale": 95, "width": 750, "height": 100})
        cmd = bridge.calls[-1]
        self.assertEqual(["-s", "CAR", "shell", "am", "broadcast"], cmd[:5])
        self.assertIn(COMPONENT, cmd)
        self.assertEqual(["--ei", "x", "100", "--ei", "y", "540", "--ei", "scale", "95",
                          "--ei", "width", "750", "--ei", "height", "100"], cmd[-15:])

    def test_new_content_metadata_is_validated_while_old_replies_remain_compatible(self):
        reply = dict(REPLY, contentSelection=True, content="INSTRUMENTS",
                     displayedUrl="maps:/car/instrumentcluster", sessionRunning=True)
        output = lambda data: 'Broadcast completed: result=-1, data="' + json.dumps(data) + '"'
        self.assertEqual(reply, parse_reply(output(reply)))
        self.assertEqual(REPLY, parse_reply(output(REPLY)))
        self.assertIsNone(parse_reply(output(dict(reply, displayedUrl=None)))["displayedUrl"])
        for patch in ({"contentSelection": "true"}, {"content": "UNKNOWN"},
                      {"sessionRunning": 1}, {"displayedUrl": "maps:/other"}):
            with self.assertRaises(TunerError):
                parse_reply(output(dict(reply, **patch)))
        for key in ("content", "displayedUrl", "sessionRunning"):
            incomplete = dict(reply)
            incomplete.pop(key)
            with self.assertRaises(TunerError):
                parse_reply(output(incomplete))

    def test_rejects_untrusted_inputs_before_sending_a_broadcast(self):
        base = {"serial": "CAR", "op": "set", "target": "ete", "x": 100, "y": 540,
                "scale": 100, "width": 750, "height": 100}
        for patch in [{"x": True}, {"x": "100"}, {"y": 720}, {"scale": 151},
                      {"width": 79}, {"height": 721}, {"height": "100"}, {"width": None},
                      {"op": "shell"}, {"target": "other"}, {"serial": "PHONE"}, {"serial": "unknown"}]:
            bridge = FakeAdb()
            with self.assertRaises(TunerError):
                bridge.request(dict(base, **patch))
            self.assertFalse(any("broadcast" in cmd for cmd in bridge.calls))

    def test_native_content_uses_only_known_routes_and_keeps_layout_extras_out(self):
        for content in ("MAP_WITH_CUSTOM_CARD", "INSTRUMENTS", "TURN_CARD"):
            bridge = FakeAdb()
            bridge.request({"serial": "CAR", "op": "content", "content": content})
            self.assertEqual(["--es", "op", "content", "--es", "content", content], bridge.calls[-1][-6:])
            self.assertNotIn("--ei", bridge.calls[-1])
        for invalid in ("maps:/other", "INSTRUMENTS;reboot", None, True):
            bridge = FakeAdb()
            with self.assertRaises(TunerError):
                bridge.request({"serial": "CAR", "op": "content", "content": invalid})
            self.assertEqual([], bridge.calls)


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.bridge = FakeAdb()
        self.server = PanelServer(("127.0.0.1", 0), self.bridge)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, headers):
        body = json.dumps({"serial": "CAR", "op": "get"}).encode()
        return Request(self.server.origin + "/api/layout", data=body, headers=headers)

    def test_local_panel_requires_its_session_token_and_rejects_foreign_hosts(self):
        for headers in [{}, {"X-Autunn-Session": self.server.token, "Origin": "https://other.example"},
                        {"X-Autunn-Session": self.server.token, "Host": "other.example"}]:
            with self.assertRaises(HTTPError) as error:
                urlopen(self.request(headers), timeout=2)
            self.assertEqual(403, error.exception.code)
        self.assertEqual([], self.bridge.calls)
        with urlopen(self.request({"X-Autunn-Session": self.server.token}), timeout=2) as response:
            self.assertEqual(REPLY, json.load(response))


if __name__ == "__main__":
    unittest.main()
