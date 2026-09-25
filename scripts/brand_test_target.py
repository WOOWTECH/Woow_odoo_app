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


def authorize_live(target, url, env):
    """Opt-in is a safety interlock, not a substitute for the owner's authorization."""
    if env.get("APP_VARIANT") != target.variant:
        raise ValueError("Explicit APP_VARIANT is required for live tests")
    if not target.variant.endswith("Debug"):
        raise ValueError("Live scripts may clear data; release packages are protected")
    if target.brand == "apporo":
        raise ValueError("Apporo live push tests BLOCKED until phase-3 brand protocol is implemented")
    if env.get("ALLOW_DEVICE_TEST_WRITES") != target.package:
        raise ValueError("Explicit device-write authorization for this package is required")
    parsed = urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in (
        "demo111-odoo.woowtech.io", "demo222-odoo.woowtech.io"
    ) or parsed.username or parsed.password or parsed.port or parsed.path not in ("", "/") or parsed.query or parsed.fragment:
        raise ValueError("Only the two approved Odoo test origins may be targeted")
    if env.get("ALLOW_ODOO_TEST_WRITES") != url:
        raise ValueError("Separate explicit Odoo-write authorization for this origin is required")
