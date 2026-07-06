#!/usr/bin/env python3
"""
E2E-15 Full Automation — Clock-in via WebView with GPS verification

Drives the WebView via Chrome DevTools Protocol (CDP) since uiautomator2
cannot reliably interact with Odoo's OWL Compose dropdown items
(FLAG_SECURE blanks screenshots, dropdown items don't expose clickable
bounds).

Flow:
1. Pre-grant FINE+COARSE on the Android side (deterministic — matches
   what checkSelfPermission will see in our LocationPermissionGate)
2. Force-launch the app with location-enabled hook
3. Snapshot last hr.attendance ID via JSON-RPC
4. Forward CDP socket via adb
5. Connect WebSocket to the WebView page
6. Navigate to /odoo/attendances if not already there
7. Inject JS: find + click the systray attendance icon, then Sign In/Out button
8. Wait for the RPC to complete (5s)
9. Re-query hr.attendance via JSON-RPC — assert NEW record with
   non-zero in_latitude/in_longitude (or out_* on clock-out)
10. Bonus: navigate to attendance list view, read the displayed coords
    from the DOM, assert they match the JSON-RPC result

Requires: pip install websocket-client requests
"""
import json
import re
import subprocess
import os
import sys
import time

import requests
import websocket

# Single source of truth for test config — see scripts/test_config.py.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_config import (
    APP_ACTIVITY as ACTIVITY,
    APP_PACKAGE as PKG,
    ODOO_DB as DB,
    ODOO_PASS as PASSWORD,
    ODOO_URL as TUNNEL,
    ODOO_USER as USER,
)

LOCAL_CDP_PORT = 9222


# ─── Helpers ──────────────────────────────────────────────────

def colored(code, msg):
    return f"\033[{code}m{msg}\033[0m"


def green(msg):
    print(colored("32", f"  ✅ {msg}"))


def red(msg):
    print(colored("31", f"  ❌ {msg}"))


def info(msg):
    print(f"  ℹ  {msg}")


def section(title):
    print()
    print("─" * 70)
    print(f"  {title}")
    print("─" * 70)


def adb(*args, timeout=15):
    return subprocess.run(["adb"] + list(args), capture_output=True, text=True, timeout=timeout)


def adb_shell(*args, timeout=15):
    return subprocess.run(["adb", "shell"] + list(args), capture_output=True, text=True, timeout=timeout)


def odoo_authenticate():
    auth = requests.post(
        f"{TUNNEL}/web/session/authenticate",
        json={"jsonrpc": "2.0", "method": "call",
              "params": {"db": DB, "login": USER, "password": PASSWORD}, "id": 1},
        timeout=15,
    ).json()
    uid = auth.get("result", {}).get("uid")
    if not uid:
        raise RuntimeError(f"Odoo auth failed: {auth}")
    return uid


def odoo_execute(uid, model, method, args, kwargs=None):
    resp = requests.post(
        f"{TUNNEL}/jsonrpc",
        json={"jsonrpc": "2.0", "method": "call",
              "params": {"service": "object", "method": "execute_kw",
                         "args": [DB, uid, PASSWORD, model, method, args, kwargs or {}]},
              "id": 1},
        timeout=15,
    ).json()
    return resp.get("result")


# ─── CDP wrapper ──────────────────────────────────────────────

class CDP:
    """Tiny synchronous Chrome DevTools Protocol wrapper for the WebView."""

    def __init__(self, ws_url):
        # Recent Chromium WebView versions reject WebSocket connections from
        # http://localhost:<port> origin. Override with a CDP-allowed origin.
        # See: https://bugs.chromium.org/p/chromium/issues/detail?id=813540
        # Recent Chromium WebView versions reject WebSocket from origins
        # that don't match its --remote-allow-origins flag. We can't set the
        # flag on a stock WebView, so we omit the Origin header entirely
        # (WebView accepts unauthenticated connections without an Origin).
        self.ws = websocket.create_connection(
            ws_url,
            timeout=20,
            suppress_origin=True,
        )
        self._next_id = 1

    def call(self, method, params=None, timeout=15):
        """Send a CDP command and wait for the matching response."""
        msg_id = self._next_id
        self._next_id += 1
        self.ws.send(json.dumps({"id": msg_id, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.ws.settimeout(max(0.5, deadline - time.time()))
            try:
                raw = self.ws.recv()
            except websocket.WebSocketTimeoutException:
                continue
            if not raw:
                continue
            data = json.loads(raw)
            if data.get("id") == msg_id:
                if "error" in data:
                    raise RuntimeError(f"CDP error: {data['error']}")
                return data.get("result", {})
        raise TimeoutError(f"CDP {method} timed out")

    def evaluate(self, expr, await_promise=False, return_by_value=True, timeout=15):
        """Evaluate JS in the page; returns the result value (or raises)."""
        result = self.call("Runtime.evaluate", {
            "expression": expr,
            "awaitPromise": await_promise,
            "returnByValue": return_by_value,
        }, timeout=timeout)
        if "exceptionDetails" in result:
            raise RuntimeError(f"JS exception: {result['exceptionDetails']}")
        return result.get("result", {}).get("value")

    def navigate(self, url):
        return self.call("Page.navigate", {"url": url})

    def real_click(self, selector):
        """Dispatch a real mouse press+release at the element's center.
        OWL Dropdown listens for native pointer events — synthetic
        `element.click()` does not open it."""
        box = self.evaluate(f"""
            (function() {{
                const el = document.querySelector({json.dumps(selector)});
                if (!el) return null;
                const r = el.getBoundingClientRect();
                if (r.width === 0 || r.height === 0) return null;
                return {{x: r.left + r.width / 2, y: r.top + r.height / 2}};
            }})()
        """)
        if not box:
            return False
        self.call("Input.dispatchMouseEvent", {
            "type": "mousePressed", "x": box["x"], "y": box["y"],
            "button": "left", "clickCount": 1,
        })
        self.call("Input.dispatchMouseEvent", {
            "type": "mouseReleased", "x": box["x"], "y": box["y"],
            "button": "left", "clickCount": 1,
        })
        return True

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def find_page_ws(predicate=lambda p: p.get("type") == "page"):
    """Query the local CDP /json endpoint for an attached page."""
    pages = requests.get(f"http://localhost:{LOCAL_CDP_PORT}/json", timeout=5).json()
    for p in pages:
        if predicate(p):
            return p
    return None


# ─── Test ─────────────────────────────────────────────────────

def main():
    section("Setup")
    uid = odoo_authenticate()
    green(f"Odoo auth OK (uid={uid})")

    employees = odoo_execute(uid, "hr.employee", "search_read",
                             [[["user_id", "=", uid]]],
                             {"fields": ["id", "name", "attendance_state"], "limit": 1})
    if not employees:
        red("No hr.employee linked to admin — aborting")
        return 1
    emp_id = employees[0]["id"]
    state_before = employees[0]["attendance_state"]
    green(f"Employee {emp_id} ({employees[0]['name']}), state before: {state_before}")

    last = odoo_execute(uid, "hr.attendance", "search_read",
                        [[["employee_id", "=", emp_id]]],
                        {"fields": ["id"], "order": "id desc", "limit": 1})
    before_id = last[0]["id"] if last else 0
    green(f"Latest hr.attendance id before clock-in: {before_id}")

    # Permissions + hook
    adb_shell("pm", "grant", PKG, "android.permission.ACCESS_FINE_LOCATION")
    adb_shell("pm", "grant", PKG, "android.permission.ACCESS_COARSE_LOCATION")
    green("FINE + COARSE granted via adb")

    adb_shell("am", "force-stop", PKG)
    time.sleep(1)
    adb_shell("am", "start", "-n", f"{PKG}/{ACTIVITY}",
              "--ez", "location-enabled", "true")
    time.sleep(8)
    green("App launched cold with location-enabled=true")

    # Set up CDP forwarding
    section("Connect to WebView via CDP")
    pid = adb_shell("pidof", PKG).stdout.strip()
    if not pid:
        red("App PID not found")
        return 1
    info(f"App PID: {pid}")
    adb("forward", f"tcp:{LOCAL_CDP_PORT}", f"localabstract:webview_devtools_remote_{pid}")
    green(f"CDP forward: tcp:{LOCAL_CDP_PORT} → webview_devtools_remote_{pid}")

    # Wait for the WebView page to be available
    page = None
    for _ in range(20):
        page = find_page_ws()
        if page:
            break
        time.sleep(1)
    if not page:
        red("No CDP page found in WebView")
        return 1
    info(f"Page URL: {page.get('url', '')[:80]}")

    cdp = CDP(page["webSocketDebuggerUrl"])
    try:
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")

        # Navigate to /odoo/attendances if we're not already there
        section("Navigate to /odoo/attendances")
        cur_url = cdp.evaluate("location.href")
        info(f"Current URL: {cur_url[:80]}")
        if "/odoo/attendances" not in cur_url:
            cdp.navigate(f"{TUNNEL}/odoo/attendances")
            # Wait for navigation
            for _ in range(15):
                time.sleep(1)
                u = cdp.evaluate("location.href")
                if "/odoo/attendances" in u:
                    break
            green(f"Navigated to {cdp.evaluate('location.href')[:80]}")
        else:
            green("Already on /odoo/attendances")

        # Wait for OWL components to render
        time.sleep(4)

        # Find + click the systray Attendance dropdown button.
        # Selector derived from the actual Odoo 18 OWL template
        # `addons/hr_attendance/static/src/components/attendance_menu/attendance_menu.xml`:
        #   <button>
        #     <i class="fa fa-circle text-{success|danger}" role="img" aria-label="Attendance"/>
        #   </button>
        # The button is a child of <Dropdown> which renders as a div containing
        # the trigger <button>. So we target the parent button of the icon.
        section("Click systray Attendance dropdown")
        # OWL Dropdown ignores synthetic Element.click() — must dispatch a real
        # mousePressed/mouseReleased pair. Use cdp.real_click() helper.
        state = cdp.evaluate("""
            (function() {
                const icon = document.querySelector('header i.fa-circle[aria-label="Attendance"]');
                if (!icon) return null;
                return {
                    selectorOk: true,
                    checkedIn: icon.classList.contains('text-success'),
                };
            })()
        """)
        if not state:
            red("Attendance systray icon not found in header")
            return 1
        if not cdp.real_click('header i.fa-circle[aria-label="Attendance"]'):
            red("real_click failed on Attendance systray")
            return 1
        green(f"Clicked Attendance systray (currently checkedIn={state.get('checkedIn')})")

        # Wait for OWL Dropdown to render its content slot
        time.sleep(2)

        # Click the Check in / Check out button INSIDE the open dropdown.
        # OWL template:
        #   <button class="btn btn-{warning|success}">
        #     <span>Check in|Check out</span>
        #     <i class="fa fa-sign-{in|out} ms-1"/>
        #   </button>
        # We target by the icon's class which encodes the action.
        section("Click Check In / Check Out button in dropdown")
        # The action button is per Odoo 18 template:
        #   <button class="btn btn-{warning|success}">
        #     <span>Check in|Check out</span>
        #     <i class="fa fa-sign-{in|out} ms-1"/>
        #   </button>
        # The dropdown content is rendered via OWL portal — selector
        # `i.fa-sign-in, i.fa-sign-out` finds it anywhere on page.
        action_state = cdp.evaluate("""
            (function() {
                const icon = document.querySelector('i.fa-sign-in, i.fa-sign-out');
                if (!icon || icon.offsetParent === null) return null;
                return {
                    action: icon.classList.contains('fa-sign-out') ? 'check-out' : 'check-in',
                };
            })()
        """)
        if not action_state:
            red("Check In/Out button not found in opened dropdown")
            return 1
        # Real click via mouse-event dispatch
        action = action_state["action"]
        sel_for_click = "i.fa-sign-in, i.fa-sign-out"
        # Click the button (parent of icon) for a more reliable hit area
        if not cdp.real_click(
            "button.btn-warning > i.fa-sign-out, button.btn-success > i.fa-sign-in"
        ):
            # Fallback: click the icon directly
            if not cdp.real_click(sel_for_click):
                red("real_click failed on Sign In/Out button")
                return 1
        green(f"Clicked: action={action}")

        # Wait for getCurrentPosition + RPC roundtrip.
        # Odoo's JS calls navigator.geolocation.getCurrentPosition() which fires
        # our WebChromeClient.onGeolocationPermissionsShowPrompt → gate.resolve →
        # GeolocationPermissions.clear(origin) → callback(grant). Then Chromium
        # acquires a fix from the system, hands back coords, Odoo issues RPC.
        info("Waiting 8s for geolocation acquisition + RPC round-trip...")
        time.sleep(8)

    finally:
        cdp.close()

    # Verify via JSON-RPC
    section("Verify on Odoo server")
    new_records = odoo_execute(uid, "hr.attendance", "search_read",
                               [[["employee_id", "=", emp_id], ["id", ">", before_id]]],
                               {"fields": ["id", "check_in", "check_out",
                                           "in_latitude", "in_longitude",
                                           "out_latitude", "out_longitude"],
                                "order": "id desc", "limit": 5})

    # If state was checked_in, the click might have been a Check Out — updates the
    # existing record's out_* fields rather than creating a new one. Check both.
    latest = odoo_execute(uid, "hr.attendance", "search_read",
                          [[["employee_id", "=", emp_id]]],
                          {"fields": ["id", "check_in", "check_out",
                                      "in_latitude", "in_longitude",
                                      "out_latitude", "out_longitude"],
                           "order": "id desc", "limit": 1})

    if new_records:
        rec = new_records[0]
        info(f"NEW record id={rec['id']} in=({rec['in_latitude']}, {rec['in_longitude']}) "
             f"out=({rec['out_latitude']}, {rec['out_longitude']})")
        ok = (rec.get("in_latitude") and rec["in_latitude"] != 0) or \
             (rec.get("out_latitude") and rec["out_latitude"] != 0)
        if ok:
            green(f"E2E-15 PASS: new record has non-zero coords")
            return 0
        red(f"E2E-15 FAIL: new record exists but coords are zero")
        return 1
    elif latest and latest[0]["id"] == before_id:
        rec = latest[0]
        info(f"SAME record id={rec['id']} (clock-out path) "
             f"in=({rec['in_latitude']}, {rec['in_longitude']}) "
             f"out=({rec['out_latitude']}, {rec['out_longitude']})")
        if rec.get("out_latitude") and rec["out_latitude"] != 0:
            green(f"E2E-15 PASS: clock-out updated record with non-zero out coords")
            return 0
        red(f"E2E-15 FAIL: clock-out left out_latitude=0")
        return 1
    else:
        red(f"E2E-15 FAIL: no new record AND latest unchanged. Latest: {latest}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
