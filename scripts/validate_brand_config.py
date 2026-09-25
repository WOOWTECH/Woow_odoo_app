#!/usr/bin/env python3
"""Fail-closed client-only Firebase validation. Never prints client values or API keys."""
import argparse
import json
from pathlib import Path


class BrandConfigError(ValueError):
    pass


def validate_client(config, brand, build_type):
    if brand not in ("woowtech", "apporo") or build_type not in ("debug", "release"):
        raise BrandConfigError("Unknown brand/build type")
    if "private_key" in config or config.get("type") == "service_account":
        raise BrandConfigError("Expected Firebase mobile client configuration, not credentials")
    project = "apporo-odoo-app" if brand == "apporo" else "woow-odoo-de2cb"
    package = "com.apporo.odoo" if brand == "apporo" else "io.woowtech.odoo"
    if build_type == "debug":
        package += ".debug"
    info = config.get("project_info", {})
    if info.get("project_id") != project or not str(info.get("project_number", "")).isdigit():
        raise BrandConfigError("Firebase project identity mismatch or missing sender")
    matches = [c for c in config.get("client", []) if
               c.get("client_info", {}).get("android_client_info", {}).get("package_name") == package]
    if len(matches) != 1:
        raise BrandConfigError("Expected exactly one matching Android Firebase client")
    client = matches[0]
    app_id = client.get("client_info", {}).get("mobilesdk_app_id", "")
    if not app_id.startswith(f"1:{info['project_number']}:android:") or not app_id.rsplit(":", 1)[-1]:
        raise BrandConfigError("Firebase mobile app identity mismatch")
    if not any(k.get("current_key") for k in client.get("api_key", [])):
        raise BrandConfigError("Missing Firebase client API configuration")


def validate_file(path, brand, build_type):
    if not path.is_file():
        raise BrandConfigError("Missing variant Firebase client configuration; no fallback allowed")
    try:
        config = json.loads(path.read_text())
        validate_client(config, brand, build_type)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
        if isinstance(exc, BrandConfigError):
            raise
        raise BrandConfigError("Invalid Firebase client configuration") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--brand", required=True, choices=("woowtech", "apporo"))
    parser.add_argument("--build-type", required=True, choices=("debug", "release"))
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    try:
        validate_file(args.config, args.brand, args.build_type)
    except BrandConfigError as exc:
        parser.exit(1, f"BLOCKED: {exc}\n")
    print("PASS: Firebase client identity contract (not live provisioning verification)")


if __name__ == "__main__":
    main()
