#!/usr/bin/env python3
"""Local admin CLI using standard OIDC Device Authorization Grant; no password grant or client secret."""
import argparse
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SESSION = pathlib.Path(__file__).resolve().parent.parent / "secrets" / "admin-session.json"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(NoRedirect())


class RequestError(Exception):
    def __init__(self, status, body):
        self.status, self.body = status, body
        super().__init__(f"HTTP {status}: {body.get('error', 'request failed')}")


def request(url, method="GET", body=None, bearer=None, form=False):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = (urllib.parse.urlencode(body) if form else json.dumps(body)).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    try:
        with HTTP.open(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            result = json.load(error)
        except (ValueError, TypeError):
            result = {"error": "request failed"}
        raise RequestError(error.code, result) from None


def save(tokens, issuer):
    SESSION.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    value = {"tokens": tokens, "issuer": issuer, "expires_at": int(time.time()) + tokens["expires_in"]}
    descriptor = os.open(SESSION, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output)


def login(issuer):
    endpoint = issuer.rstrip("/") + "/protocol/openid-connect"
    grant = request(endpoint + "/auth/device", "POST", {"client_id": "faw-license-admin", "scope": "openid profile email"}, form=True)
    print("Open:", grant.get("verification_uri_complete", grant["verification_uri"]))
    print("Verification code:", grant["user_code"])
    deadline = time.monotonic() + grant["expires_in"]
    interval = max(5, grant.get("interval", 5))
    while time.monotonic() < deadline:
        time.sleep(interval)
        try:
            tokens = request(endpoint + "/token", "POST", {"client_id": "faw-license-admin", "grant_type": "urn:ietf:params:oauth:grant-type:device_code", "device_code": grant["device_code"]}, form=True)
            save(tokens, issuer)
            print("Login saved locally. The account must have the license-admin realm role for administrator commands.")
            return
        except RequestError as error:
            if error.body.get("error") == "slow_down":
                interval += 5
            elif error.body.get("error") != "authorization_pending":
                raise
    raise RuntimeError("Login code expired; run login again")


def access_token(issuer):
    if not SESSION.exists():
        raise RuntimeError("Run login first")
    with SESSION.open() as source:
        saved = json.load(source)
    if saved["issuer"] != issuer:
        raise RuntimeError("Saved login belongs to another issuer; run login again")
    tokens = saved["tokens"]
    if saved["expires_at"] <= time.time() + 10:
        if "refresh_token" not in tokens:
            raise RuntimeError("Login expired; run login again")
        tokens = request(issuer.rstrip("/") + "/protocol/openid-connect/token", "POST", {"client_id": "faw-license-admin", "grant_type": "refresh_token", "refresh_token": tokens["refresh_token"]}, form=True)
        save(tokens, issuer)
    return tokens["access_token"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default=os.environ.get("LICENSE_API_URL", "http://localhost:8080"))
    parser.add_argument("--issuer", default=os.environ.get("OIDC_ISSUER", "http://localhost:8081/realms/faw-play"))
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("login")
    code = commands.add_parser("create-code", help="Create a phone QR activation code (no customer account needed)")
    code.add_argument("--devices", type=int, default=1)
    expiry = code.add_mutually_exclusive_group()
    expiry.add_argument("--valid-until", type=int, help="Absolute expiry in epoch seconds")
    expiry.add_argument("--days", type=int, default=None, help="Days from creation; default 365")
    expiry.add_argument("--permanent", action="store_true", help="Permanent purchase with renewable offline tickets")
    code.add_argument("--activations", type=int, help="Cumulative first-device activation cap; default device allowance")
    code.add_argument("--note", default="", help="Administrator-only note")
    commands.add_parser("licenses")
    commands.add_parser("audit")
    view = commands.add_parser("view"); view.add_argument("license_id")
    update = commands.add_parser("update"); update.add_argument("license_id"); update.add_argument("--devices", type=int, required=True); update_expiry = update.add_mutually_exclusive_group(required=True); update_expiry.add_argument("--valid-until", type=int); update_expiry.add_argument("--permanent", action="store_true"); update.add_argument("--activations", type=int); update.add_argument("--note")
    for action in ("revoke", "restore"):
        item = commands.add_parser(action); item.add_argument("license_id"); item.add_argument("device_id")
    args = parser.parse_args()
    for value in (args.api, args.issuer):
        parsed = urllib.parse.urlparse(value)
        if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1")):
            parser.error("Use HTTPS except for loopback local development")
    if args.command == "login":
        login(args.issuer); return
    path, method, body = "/v1/admin/" + args.command, "GET", None
    if args.command == "create-code":
        days = 365 if args.days is None else args.days
        if not 1 <= args.devices <= 100 or days < 1:
            parser.error("Device count must be 1..100 and days must be positive")
        until = args.valid_until if args.valid_until is not None else int(time.time()) + days * 86400
        path, method, body = "/v1/admin/activation-codes", "POST", {"deviceLimit": args.devices, "permanent": args.permanent, "activationLimit": args.activations if args.activations is not None else args.devices, "note": args.note}
        if not args.permanent: body["validUntil"] = until
    elif args.command in ("view", "update"):
        path = "/v1/admin/licenses/" + urllib.parse.quote(args.license_id, safe="")
        if args.command == "update":
            method, body = "PUT", {"deviceLimit": args.devices, "permanent": args.permanent}
            if not args.permanent: body["validUntil"] = args.valid_until
            if args.activations is not None: body["activationLimit"] = args.activations
            if args.note is not None: body["note"] = args.note
    elif args.command in ("revoke", "restore"):
        path = "/v1/admin/licenses/" + urllib.parse.quote(args.license_id, safe="") + "/devices/" + urllib.parse.quote(args.device_id, safe="") + "/" + args.command
        method = "POST"
    result = request(args.api.rstrip("/") + path, method, body, access_token(args.issuer))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RequestError, RuntimeError, OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
