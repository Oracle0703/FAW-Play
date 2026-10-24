"""Verify the admin CLI never forwards a bearer token through an HTTP redirect."""
import http.server
import threading
import unittest
from unittest.mock import patch
import admin


class RedirectTests(unittest.TestCase):
    def test_permanent_code_omits_expiry_and_sends_explicit_activation_allowance(self):
        with patch("sys.argv", ["admin.py", "create-code", "--permanent", "--activations", "3", "--note", "local test"]), \
                patch.object(admin, "access_token", return_value="local-test-token"), \
                patch.object(admin, "request", return_value={}) as request, patch("builtins.print"):
            admin.main()
            self.assertEqual({"deviceLimit": 1, "permanent": True, "activationLimit": 3, "note": "local test"}, request.call_args.args[2])

    def test_code_creation_supports_days_and_preserves_absolute_expiry(self):
        for arguments, expected in ((["--days", "30"], 1800000000 + 30 * 86400),
                                    (["--valid-until", "1900000000"], 1900000000),
                                    ([], 1800000000 + 365 * 86400)):
            with patch("sys.argv", ["admin.py", "create-code", "--devices", "2", *arguments]), \
                    patch.object(admin, "access_token", return_value="local-test-token"), \
                    patch.object(admin, "request", return_value={"status": "test"}) as request, \
                    patch.object(admin.time, "time", return_value=1800000000), patch("builtins.print"):
                admin.main()
                self.assertEqual({"deviceLimit": 2, "validUntil": expected, "permanent": False, "activationLimit": 2, "note": ""}, request.call_args.args[2])

    def test_bearer_requests_do_not_follow_redirects(self):
        paths = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                paths.append(self.path)
                self.send_response(302)
                self.send_header("Location", "/token-sink")
                self.end_headers()

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with self.assertRaises(admin.RequestError) as caught:
                admin.request(f"http://127.0.0.1:{server.server_port}/redirect", bearer="non-secret-test-token")
            self.assertEqual(302, caught.exception.status)
            self.assertEqual(["/redirect"], paths)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
