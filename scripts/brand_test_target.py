"""Pure offline brand target policy; no environment files, device or network access."""
from dataclasses import dataclass
from urllib.parse import urlsplit


@dataclass(frozen=True)
class BrandTestTarget:
    variant: str
    package: str
    brand: str
    firebase_project: str
    title: str
    scheme: str

    @property
    def apk_path(self):
        build_type = "debug" if self.variant.endswith("Debug") else "release"
        return f"app/build/outputs/apk/{self.brand}/{build_type}/app-{self.brand}-{build_type}.apk"


def target_for(variant):
    matrix = {
        "woowtechDebug": ("io.woowtech.odoo.debug", "woowtech", "woow-odoo-de2cb", "woowtech platform", "woowodoo"),
        "woowtechRelease": ("io.woowtech.odoo", "woowtech", "woow-odoo-de2cb", "woowtech platform", "woowodoo"),
        "apporoDebug": ("com.apporo.odoo.debug", "apporo", "apporo-odoo-app", "Apporo platform", "apporoodoo-dev"),
        "apporoRelease": ("com.apporo.odoo", "apporo", "apporo-odoo-app", "Apporo platform", "apporoodoo"),
    }
    if variant not in matrix:
        raise ValueError("APP_VARIANT must name one of the four explicit brand variants")
    return BrandTestTarget(variant, *matrix[variant])


LIVE_SCOPES = ("ui", "push")
APPORO_LIVE_ORIGIN = "https://demo111-odoo.woowtech.io"


def authorize_live(target, url, env, *, scope="push", account=""):
    """Opt-in is a safety interlock, not a substitute for the owner's authorization.

    ``scope`` is what the calling script exercises: ``"ui"`` for app/WebView flows,
    ``"push"`` (the default, most restrictive) for anything that sends or asserts FCM.
    Apporo debug may run ``"ui"`` flows only with its own explicit flag, only against
    demo111 (demo222 is read-only for Apporo) and only as the account the owner named in
    ``APPORO_LIVE_ACCOUNT``. Apporo push stays refused until its backend is deployed.
    """
    if scope not in LIVE_SCOPES:
        raise ValueError("Live scope must be 'ui' or 'push'")
    if env.get("APP_VARIANT") != target.variant:
        raise ValueError("Explicit APP_VARIANT is required for live tests")
    if not target.variant.endswith("Debug"):
        raise ValueError("Live scripts may clear data; release packages are protected")
    if target.brand == "apporo":
        if scope != "ui":
            raise ValueError("Apporo live push tests BLOCKED until the Apporo push backend is deployed")
        if env.get("ALLOW_APPORO_LIVE_UI") != target.package:
            raise ValueError("Explicit ALLOW_APPORO_LIVE_UI for this package is required for Apporo UI flows")
        designated = env.get("APPORO_LIVE_ACCOUNT", "")
        if not designated or account != designated:
            raise ValueError("Apporo UI flows must run as the account named in APPORO_LIVE_ACCOUNT")
        if url != APPORO_LIVE_ORIGIN:
            raise ValueError("Apporo UI flows may only target demo111; demo222 is read-only")
    if env.get("ALLOW_DEVICE_TEST_WRITES") != target.package:
        raise ValueError("Explicit device-write authorization for this package is required")
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in (
        "demo111-odoo.woowtech.io", "demo222-odoo.woowtech.io"
    ) or parsed.username or parsed.password or parsed.port or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ValueError("Only the two approved Odoo test origins may be targeted")
    if env.get("ALLOW_ODOO_TEST_WRITES") != url:
        raise ValueError("Separate explicit Odoo-write authorization for this origin is required")
