"""Brand offline contracts (phase-3 approved push-only deltas). No Gradle, dotenv, device, subprocess services or network."""
import ast
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '73ad528'
sys.path.insert(0, str(ROOT / "scripts"))
from brand_test_target import authorize_live, target_for
from generate_apporo_assets import read_rgba
from validate_brand_config import BrandConfigError, validate_client, validate_file

K = ROOT / "app/src/main/java/io/woowtech/odoo"
BRAND = (K / "brand/AppBrand.kt").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
SCRIPTS = ("verify-on-device.py", "e2e-production-test.py", "e2e_15_clockin_full.py",
           "e2e_hprime_android.py", "e2e_hprime_android_chaos.py", "e2e-verification-report.py")


def strings(brand, locale):
    path = ROOT / f"app/src/{brand}/res/{locale}/strings.xml"
    return {node.attrib["name"]: node.text for node in ET.parse(path).getroot()}


def baseline(path):
    return subprocess.check_output(["git", "show", f"{BASELINE}:{path}"], cwd=ROOT)


def fixture(brand, build_type):
    # In-memory, deliberately invalid-for-service test data. Never written to a config path.
    target = target_for(brand + build_type.title())
    return {"project_info": {"project_id": target.firebase_project, "project_number": "123"},
            "client": [{"client_info": {"android_client_info": {"package_name": target.package},
                                       "mobilesdk_app_id": "1:123:android:test-only"},
                        "api_key": [{"current_key": "unit-test-only-not-a-real-api-key"}]}]}


# W1-10 (owner-approved 2026-09-26): "Remember me" off must not persist a password; the active
# account stays deep-link routable without one. Each pair is (current text, baseline text) and must
# occur exactly once; everything else in these files keeps its byte-level baseline gate.
W1_10_MAIN_ACTIVITY = (
    ('            // "Remember me" off leaves no stored password; the active account still has its session.\n'
     '            val activeAccountId = accounts.firstOrNull { it.isActive }?.id\n', ''),
    ('isLoggedIn = { accountRepository.canRouteDeepLink(it, activeAccountId) },',
     'isLoggedIn = { accountRepository.isLoggedIn(it) },'),
)
W1_10_ACCOUNT_WOOW = (
    ('    /**\n'
     '     * Deep-link routing predicate. The ACTIVE account is usable through its live session even when\n'
     '     * "Remember me" was off (no stored password); any other account must be switched to, which needs\n'
     '     * a stored password.\n'
     '     */\n'
     '    fun canRouteDeepLink(accountId: String, activeAccountId: String?): Boolean =\n'
     '        accountId == activeAccountId || isLoggedIn(accountId)\n'
     '\n'
     '    /**\n'
     '     * @param rememberPassword the login screen\'s "Remember me". When false the password is NOT\n'
     '     * persisted and any password previously remembered for this same account is removed, so no\n'
     '     * reusable secret outlives the session (silent re-auth / switching back will ask to sign in).\n'
     '     */\n', ''),
    ('        password: String,\n        rememberPassword: Boolean = true,\n    ): AuthResult {',
     '        password: String\n    ): AuthResult {'),
    ('            // Save password securely only when the user asked us to remember it (W1-10).\n'
     '            if (rememberPassword) encryptedPrefs.savePassword(account.id, password)\n'
     '            else encryptedPrefs.removePassword(account.id)\n',
     '            // Save password securely\n'
     '            encryptedPrefs.savePassword(account.id, password)\n'),
)

# Server-error status (owner-approved 2026-09-26, iOS b9ebd0d parity): a non-200 sign-in response on
# the WOOW path keeps its HTTP status for the localized `error_server_http` login text instead of
# being parsed as JSON. Same (current, baseline) contract as W1-10; the rest stays byte-level.
SERVER_HTTP_STATUS_WOOW_API = (
    ('        } catch (e: SignInHttpStatusException) {\n'
     '            signInHttpStatus(e.code)\n', ''),
    ('        if (response.code != 200) {\n'
     '            response.close()\n'
     '            throw SignInHttpStatusException(response.code)\n'
     '        }\n', ''),
    ('    /** Status only; LoginScreen renders the localized `error_server_http` text (iOS parity). */\n'
     '    private fun signInHttpStatus(code: Int) =\n'
     '        AuthResult.Error("HTTP $code", AuthResult.ErrorType.SERVER_ERROR, httpStatus = code)\n'
     '\n'
     '    private class SignInHttpStatusException(val code: Int) : Exception("HTTP $code")\n'
     '\n', ''),
)
SERVER_HTTP_STATUS_STRINGS = {'error_server_http'}

# LIVE-0927 Android r2 (owner-requested 2026-09-27, iOS 32462a3 parity): new three-locale keys.
# Same additive contract as above — every baseline key keeps its exact value.
LIVE_0927_R2_STRINGS = {'app_lock_disable_pin_subtitle'}


def reverse_apply(test, text, deltas):
    for current, original in deltas:
        test.assertEqual(1, text.count(current), current)
        text = text.replace(current, original)
    return text


class BrandIdentityContracts(unittest.TestCase):
    def test_four_independent_variant_identities(self):
        expected = [("woowtechDebug", "io.woowtech.odoo.debug", "woowodoo"),
                    ("woowtechRelease", "io.woowtech.odoo", "woowodoo"),
                    ("apporoDebug", "com.apporo.odoo.debug", "apporoodoo-dev"),
                    ("apporoRelease", "com.apporo.odoo", "apporoodoo")]
        for variant, package, scheme in expected:
            with self.subTest(variant=variant):
                target = target_for(variant)
                self.assertEqual(package, target.package)
                self.assertEqual(scheme, target.scheme)
                self.assertIn(target.brand, target.apk_path)
        self.assertIn('namespace = "io.woowtech.odoo"', GRADLE)
        self.assertIn('applicationId = "com.apporo.odoo"', GRADLE)
        self.assertIn('applicationIdSuffix = ".debug"', GRADLE)
        self.assertIn('versionName = "1.0"', GRADLE)
        self.assertIn('versionCode = 2\n', GRADLE)
        self.assertNotIn('versionCode = 1\n', GRADLE)  # vc1 candidate voided by W1 code changes
        self.assertIn('versionName = "1.4.2"', GRADLE)
        self.assertIn('versionCode = 23', GRADLE)

    def test_scheme_is_variant_placeholder_not_new_external_router(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:scheme="${brandScheme}"', manifest)
        self.assertNotIn('android:scheme="woowodoo"', manifest)
        self.assertIn('variant.manifestPlaceholders.put("brandScheme", "apporoodoo-dev")', GRADLE)
        self.assertIn('${applicationId}.fileprovider', manifest)

    def test_provider_has_no_compose_or_context_dependency_and_unknown_fails(self):
        self.assertNotIn('import androidx.compose', BRAND)
        self.assertNotIn('import android.', BRAND)
        self.assertIn('forCode(BuildConfig.APP_BRAND, BuildConfig.DEBUG)', BRAND)
        self.assertIn('else -> error("Unknown app brand")', BRAND)
        with self.assertRaises(ValueError):
            target_for('unknown')

    def test_shared_consumers_must_use_provider(self):
        required = {'domain/model/AppSettings.kt': 'AppBrand.current.primaryHex',
                    'data/local/EncryptedPrefs.kt': 'AppBrand.current.primaryHex',
                    'ui/config/SettingsScreen.kt': 'AppBrand.current.website',
                    'ui/theme/Color.kt': 'AppBrand.current.primaryArgb',
                    'ui/theme/Theme.kt': 'AppBrand.current.secondary'}
        for name, seam in required.items():
            with self.subTest(file=name):
                self.assertIn(seam, (K / name).read_text())

    def test_shared_consumers_must_not_hardcode_brand(self):
        for name in ('domain/model/AppSettings.kt', 'data/local/EncryptedPrefs.kt',
                     'ui/config/SettingsScreen.kt', 'ui/theme/Theme.kt', 'ui/theme/Color.kt'):
            text = (K / name).read_text()
            for forbidden in ('#6183FC', '0xFF6183FC', 'aiot.woowtech.io', 'designsmart.com.tw', '#8B6B24'):
                with self.subTest(file=name, value=forbidden):
                    self.assertNotIn(forbidden, text)

    def test_webview_console_log_tag_is_provider_backed_not_woow_literal(self):
        # W1-12: the Apporo APK must not emit "[WoowTech]" into the Odoo page console.
        text = (K / 'ui/main/MainScreen.kt').read_text()
        self.assertNotIn("[WoowTech]", text)
        self.assertEqual(6, text.count("console.log('[${AppBrand.current.webLogTag}] "))
        self.assertIn('"woowodoo",\n                "WoowTech"\n', BRAND)
        self.assertIn('"apporoodoo", "Apporo"\n', BRAND)

    def test_preferences_only_default_changes(self):
        for file in ('data/local/EncryptedPrefs.kt', 'domain/model/AppSettings.kt'):
            path = f'app/src/main/java/io/woowtech/odoo/{file}'
            expected = baseline(path).decode().replace('"#6183FC"', 'AppBrand.current.primaryHex')
            actual = (ROOT / path).read_text().replace('import io.woowtech.odoo.brand.AppBrand\n', '')
            self.assertEqual(expected.replace('\n\n\n', '\n\n'), actual.replace('\n\n\n', '\n\n'))

    def test_stage_three_and_security_seams_are_byte_identical(self):
        # Phase 3 approved exception: only the five explicit push/session seams below may change.
        # All unrelated security/entry-point code retains its byte-level baseline gate.
        files = ['ui/MainActivity.kt', 'ui/login/ServerUrlInput.kt', 'data/push/DeepLinkValidator.kt',
                 'data/push/DeepLinkRouter.kt', 'WoowOdooApp.kt', 'data/push/WoowFcmService.kt']
        for name in files:
            with self.subTest(file=name):
                path = f'app/src/main/java/io/woowtech/odoo/{name}'
                current = (ROOT / path).read_bytes()
                if name == 'ui/MainActivity.kt':
                    current = reverse_apply(self, current.decode(), W1_10_MAIN_ACTIVITY).encode()
                self.assertEqual(baseline(path), current)
        account = (K / 'data/repository/AccountRepository.kt').read_text()
        apporo_account = account.split('    // Apporo selection/session commit boundary.', 1)[1].split('    fun getSessionId(', 1)[0]
        self.assertIn('if (attempt != selectionAttempt)', apporo_account)
        self.assertIn('validApporoSession(result.sessionId)', apporo_account)
        self.assertIn('odooClient.publishApporoSession', apporo_account)
        self.assertEqual(2, apporo_account.count('odooClient.authenticateApporoIsolated('))
        self.assertNotIn('odooClient.authenticate(', apporo_account)
        self.assertIn('currentCoroutineContext().ensureActive()', apporo_account)
        commit = apporo_account.split('    private suspend fun commitApporoSelection(', 1)[1]
        self.assertIn('withContext(NonCancellable)', commit)
        self.assertNotIn('fcmTokenRepository', commit)
        self.assertNotIn('authenticate', commit)
        self.assertIn('accountDao.insertAccount(original)', commit)
        self.assertIn('accountDao.activateAccount(previous.id)', commit)
        account = account.replace('    // Apporo selection/session commit boundary.' + apporo_account, '')
        start = account.index('class AccountRepository(')
        end = account.index('    val allAccounts:', start)
        account = account[:start] + ('class AccountRepository @Inject constructor(\n'
            '    private val accountDao: AccountDao,\n'
            '    private val encryptedPrefs: EncryptedPrefs,\n'
            '    private val odooClient: OdooJsonRpcClient\n) {\n') + account[end:]
        for line in ('import io.woowtech.odoo.brand.AppBrand\n',
                     'import kotlinx.coroutines.sync.Mutex\n', 'import kotlinx.coroutines.sync.withLock\n',
                     'import kotlinx.coroutines.NonCancellable\n', 'import kotlinx.coroutines.currentCoroutineContext\n',
                     'import kotlinx.coroutines.ensureActive\n', 'import kotlinx.coroutines.withContext\n',
                     '        if (brand.isApporo) return authenticateApporo(serverUrl, database, username, password, rememberPassword)\n',
                     '        if (brand.isApporo) return switchApporoAccount(accountId)\n'):
            self.assertEqual(1, account.count(line))
            account = account.replace(line, '')
        for line in ('            fcmTokenRepository?.onManualLogin(account.id, result.sessionId)\n',
                     '            fcmTokenRepository?.onManualLogin(accountId, result.sessionId)\n',
                     '        fcmTokenRepository?.forgetAccount(id)\n',
                     '        fcmTokenRepository?.forgetAccount(accountId)\n'):
            self.assertEqual(1, account.count(line))
            account = account.replace(line, '')
        account = reverse_apply(self, account, W1_10_ACCOUNT_WOOW)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/data/repository/AccountRepository.kt').decode(), account)
        # Fifth approved seam: Apporo response-derived SID; byte-preserve the entire WOOW auth path.
        api = (K / 'data/api/OdooJsonRpcClient.kt').read_text()
        isolated = api.split('    /** Apporo login reads only THIS response', 1)[1].split('    private fun executeRequest(', 1)[0]
        self.assertIn('Cookie.parseAll(request.url, response.headers)', isolated)
        self.assertIn("if (sid.isBlank() || sid.any { it <= ' ' || it == ';' || it >= '\\u007f' }) return AuthResult.Error", isolated)
        self.assertIn('internal suspend fun authenticateApporoIsolated(', isolated)
        self.assertNotIn('getSessionId(', isolated)
        self.assertIn('if (response.code != 200) return signInHttpStatus(response.code)', isolated)
        api = api.replace('    /** Apporo login reads only THIS response' + isolated, '')
        start = api.index('class OdooJsonRpcClient internal constructor(')
        end = api.index('    private val gson = Gson()', start)
        api = api[:start] + 'class OdooJsonRpcClient @Inject constructor() {\n\n' + api[end:]
        # Accepted P1 fix: only shared authenticate publishes; isolated manual entry above does not.
        shared_apporo = '''        if (brand.isApporo) {
            val result = authenticateApporo(serverUrl, database, username, password)
            // Shared WebView reauth retains its publish-on-success contract.
            if (result is AuthResult.Success) publishApporoSession(serverUrl, result.sessionId)
            return@withContext result
        }
'''
        self.assertEqual(1, api.count(shared_apporo))
        api = api.replace(shared_apporo, '')
        # Approved test-only shared client injection; production null still uses original builder.
        test_builder = '    private val client: OkHttpClient = (sharedAuthClient?.newBuilder() ?: OkHttpClient.Builder())'
        self.assertEqual(1, api.count(test_builder))
        api = api.replace(test_builder, '    private val client: OkHttpClient = OkHttpClient.Builder()')
        for line in ('import okhttp3.HttpUrl.Companion.toHttpUrlOrNull\n',
                     'import io.woowtech.odoo.brand.AppBrand\n'):
            self.assertEqual(1, api.count(line))
            api = api.replace(line, '')
        api = reverse_apply(self, api, SERVER_HTTP_STATUS_WOOW_API)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/data/api/OdooJsonRpcClient.kt').decode(), api)
        module = (K / 'di/AppModule.kt').read_text()
        for line in ('        apporoPushTransport: io.woowtech.odoo.data.repository.ApporoPushTransport,\n',
                     '            apporoTransport = apporoPushTransport,\n'):
            self.assertEqual(1, module.count(line))
            module = module.replace(line, '')
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/di/AppModule.kt').decode(), module)
        interface = (K / 'data/repository/FcmTokenRepository.kt').read_text()
        added = interface.split('interface FcmTokenRepository {\n', 1)[1].split('    /**\n     * Registers', 1)[0]
        self.assertIn('val registrationStatuses:', added)
        self.assertIn('suspend fun onManualLogin(accountId: String, sessionId: String? = null)', added)
        self.assertIn('suspend fun forgetAccount(accountId: String)', added)
        self.assertEqual(baseline('app/src/main/java/io/woowtech/odoo/data/repository/FcmTokenRepository.kt').decode(),
                         interface.replace(added, '\n', 1))
        impl = (K / 'data/repository/FcmTokenRepositoryImpl.kt').read_text()
        self.assertIn('if (!brand.isApporo) return postToOdoo(account.fullServerUrl, path, params, account)', impl)
        self.assertEqual(4, impl.count('writeToOdoo('))  # definition + register + unregister + rotation
        self.assertEqual(2, len(re.findall(r'(?<![\w])postToOdoo\(', impl)))  # definition + WOOW only
        old_impl = baseline('app/src/main/java/io/woowtech/odoo/data/repository/FcmTokenRepositoryImpl.kt').decode()
        for method in ('reconcileToken', 'reconcileOnAccountAvailable'):
            pattern = r'    override suspend fun ' + method + r'\(.*?(?=\n    /\*\*)'
            self.assertEqual(re.search(pattern, old_impl, re.S).group(), re.search(pattern, impl, re.S).group())
        self.assertIn('brand = AppBrand.current,', impl)
        self.assertIn('ApporoPushContract.requireRegistration(body)', impl)
        self.assertIn('ApporoPushContract.requireUnregistration(body)', impl)
        # Executable behavioral counterparts exist, but this static gate does NOT count as JVM PASS.
        jvm = (ROOT / 'app/src/test/kotlin/io/woowtech/odoo/data/repository/ApporoPushTransportTest.kt').read_text()
        for scenario in ('Given legacy WOOW when register and unregister',
                         'Given unsupported cap for register and unregister then zero writes',
                         'Given write expires when healed then new session must pass cap'):
            self.assertIn(scenario, jvm)

    def test_woow_resources_and_firebase_unchanged(self):
        files = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', BASELINE,
                                         'app/src/main/res'], cwd=ROOT).decode().splitlines()
        self.assertTrue(files)
        for name in files:
            with self.subTest(file=name):
                if name in {f'app/src/main/res/{locale}/strings.xml'
                            for locale in ('values', 'values-zh-rTW', 'values-zh-rCN')}:
                    old = {node.attrib['name']: node for node in ET.fromstring(baseline(name))}
                    new = {node.attrib['name']: node for node in ET.parse(ROOT / name).getroot()}
                    allowed = {'push_registration_title', 'push_registration_not_checked',
                               'push_registration_checking', 'push_registration_acknowledged',
                               'push_registration_not_configured', 'push_registration_contract_rejected',
                               'push_registration_retry', 'push_registration_sign_in',
                               'push_registration_unregistered', 'push_registration_disclaimer',
                               'login_server_url_required', 'login_database_required',
                               'login_username_required', 'login_password_required'} | SERVER_HTTP_STATUS_STRINGS \
                              | LIVE_0927_R2_STRINGS
                    self.assertEqual(set(old) | allowed, set(new))
                    self.assertTrue(allowed.isdisjoint(old))
                    def semantic(node):
                        return (node.tag, sorted(node.attrib.items()), node.text,
                                [(semantic(child), child.tail) for child in node])
                    for key, node in old.items():
                        self.assertEqual(semantic(node), semantic(new[key]), key)
                    english = strings('main', 'values')
                    for key in allowed:
                        self.assertEqual({'name': key}, new[key].attrib)
                        self.assertTrue(new[key].text)
                        if '/values/' not in name:
                            self.assertNotEqual(english[key], new[key].text)
                            self.assertRegex(new[key].text, r'[\u4e00-\u9fff]')
                else:
                    self.assertEqual(hashlib.sha256(baseline(name)).digest(),
                                     hashlib.sha256((ROOT / name).read_bytes()).digest())
        # Git compares the client internally; Python never reads or prints its contents.
        client = 'app/google-services.json'
        self.assertTrue((ROOT / client).is_file())
        for args in (['cat-file', '-e', f'{BASELINE}:{client}'],
                     ['ls-files', '--error-unmatch', '--', client],
                     ['diff', '--quiet', '--no-ext-diff', '--no-textconv', BASELINE, '--', client]):
            result = subprocess.run(['git', '--no-optional-locks', *args], cwd=ROOT,
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.assertEqual(0, result.returncode, 'Firebase baseline/presence/unchanged gate failed')

    def test_release_dependency_locks_preserve_all_versions(self):
        old = baseline('app/gradle.lockfile').decode()
        self.assertEqual(old.replace('=releaseRuntimeClasspath', '=apporoReleaseRuntimeClasspath,woowtechReleaseRuntimeClasspath'),
                         (ROOT / 'app/gradle.lockfile').read_text())


class BrandResourceContracts(unittest.TestCase):
    def test_three_locales_have_same_complete_overlay_keys(self):
        default = strings('apporo', 'values')
        for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
            self.assertEqual(set(default), set(strings('apporo', locale)))

    def test_both_brands_names_legal_copy_and_six_links(self):
        for brand in ('main', 'apporo'):
            for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
                with self.subTest(brand=brand, locale=locale):
                    values = strings(brand, locale)
                    is_apporo = brand == 'apporo'
                    self.assertEqual('Apporo platform' if is_apporo else 'woowtech platform', values['app_name'])
                    self.assertEqual('Apporo platform / APPORO UNION INC.' if is_apporo else '© 2026 WoowTech', values['copyright'])
                    host = 'www.apporo.ai' if is_apporo else 'aiot.woowtech.io'
                    suffix = '-en' if locale == 'values' else ''
                    for key, page in [('url_support', 'support'), ('url_privacy_policy', 'privacy'),
                                      ('url_account_deletion', 'account-deletion')]:
                        self.assertEqual(f'https://{host}/odoo-{page}{suffix}', values[key])
                    if is_apporo:
                        self.assertEqual('Apporo platform', values['notification_channel_messages'])
                        self.assertFalse(any('woow' in v.lower() or '渥屋' in v or '©' in v for v in values.values()))

    def test_woowtech_zh_tw_name_lives_only_in_woowtech_flavor_overlay(self):
        # engineering b1b052f: WOOW zh-TW launcher/title name is 渥屋平台; it must not leak into
        # main/ (shared by both brands) nor into any Apporo locale.
        overlay = ROOT / 'app/src/woowtech'
        files = sorted(str(p.relative_to(overlay)) for p in overlay.rglob('*') if p.is_file())
        self.assertEqual(['res/values-zh-rTW/strings.xml'], files)
        self.assertEqual({'app_name': '渥屋平台'}, strings('woowtech', 'values-zh-rTW'))
        for locale in ('values', 'values-zh-rTW', 'values-zh-rCN'):
            with self.subTest(locale=locale):
                self.assertEqual('woowtech platform', strings('main', locale)['app_name'])
                self.assertEqual('Apporo platform', strings('apporo', locale)['app_name'])
        engineering = subprocess.run(['git', 'merge-base', '--is-ancestor', 'b1b052f', 'HEAD'], cwd=ROOT,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.assertEqual(0, engineering.returncode, 'engineering b1b052f must be merged into this line')

    def test_settings_links_are_resource_backed_and_mail_is_provider_backed(self):
        text = (K / 'ui/config/SettingsScreen.kt').read_text()
        for resource in ('supportUrlResource', 'privacyUrlResource', 'deletionUrlResource'):
            self.assertIn(f'context.getString(AppBrand.current.{resource})', text)
        self.assertIn('mailto:${AppBrand.current.supportEmail}', text)
        for resource in ('url_support', 'url_privacy_policy', 'url_account_deletion'):
            self.assertIn('R.string.' + resource, BRAND)

    def test_logo_and_channel_consumers_must_use_resources(self):
        login = (K / 'ui/login/LoginScreen.kt').read_text()
        self.assertIn('painterResource(id = R.drawable.woow_logo)', login)
        self.assertIn('stringResource(R.string.content_description_logo)', login)
        app = (K / 'WoowOdooApp.kt').read_text()
        self.assertIn('getString(R.string.notification_channel_messages)', app)
        self.assertIn('getString(R.string.notification_channel_messages_desc)', app)

    def test_every_legacy_asset_qualifier_has_an_apporo_overlay(self):
        base = ROOT / 'app/src/main/res'
        overlay = ROOT / 'app/src/apporo/res'
        for path in base.rglob('*'):
            if path.is_file() and path.parent.name.startswith(('drawable', 'mipmap')):
                with self.subTest(path=str(path.relative_to(base))):
                    self.assertTrue((overlay / path.relative_to(base)).is_file())

    def test_generated_asset_hashes_sizes_opacity_and_white_corners(self):
        manifest = json.loads((ROOT / 'docs/plans/2026-09-24-apporo-assets.json').read_text())
        self.assertEqual(27, len(manifest['outputs']))
        for record in manifest['outputs']:
            with self.subTest(path=record['path']):
                path = ROOT / record['path']
                self.assertEqual(record['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                w, h, pixels = read_rgba(path)
                self.assertEqual(record['size'], [w, h])
                self.assertTrue(all(a == 255 for a in pixels[3::4]))
                for i in (0, w - 1, (h - 1) * w, w * h - 1):
                    self.assertEqual(b'\xff\xff\xff\xff', pixels[i * 4:i * 4 + 4])
                self.assertLess(min(pixels), 255)

    def test_adaptive_mark_fits_safe_circle_without_cropping(self):
        w, h, pixels = read_rgba(ROOT / 'app/src/apporo/res/drawable-xxxhdpi/ic_launcher_foreground.png')
        radius = max(math.hypot(i % w + .5 - w / 2, i // w + .5 - h / 2)
                     for i in range(w * h) if min(pixels[i * 4:i * 4 + 3]) < 250)
        self.assertLessEqual(radius / 4, 33)

    def test_apporo_xml_theme_splash_and_adaptive_background_are_not_woow_blue(self):
        root = ROOT / 'app/src/apporo/res'
        colors = {n.attrib['name']: n.text for n in ET.parse(root / 'values/colors.xml').getroot()}
        self.assertEqual({'woowtech_blue': '#8B6B24', 'woowtech_blue_dark': '#4C3B14',
                          'woowtech_blue_light': '#EEE9DE', 'splash_background': '#FFFFFF'}, colors)
        for name in ('ic_launcher', 'ic_launcher_round'):
            text = (root / f'mipmap-anydpi-v26/{name}.xml').read_text()
            self.assertIn('@drawable/ic_launcher_background', text)
            self.assertIn('@drawable/ic_launcher_foreground', text)
        self.assertIn('#FFFFFF', (root / 'drawable/ic_launcher_background.xml').read_text())
        splash = (root / 'values-v31/themes.xml').read_text()
        self.assertIn('android:windowSplashScreenBackground', splash)
        self.assertIn('@drawable/ic_launcher_foreground', splash)
        for path in root.rglob('*.xml'):
            ET.parse(path)
            self.assertNotIn('#6183FC', path.read_text())

    def test_apporo_color_derivation_and_independent_wcag(self):
        def mix(target, fraction):
            return tuple(math.floor(c * (1 - fraction) + target * fraction + .5) for c in (139, 107, 36))
        def luminance(rgb):
            linear = [v / 255 / 12.92 if v / 255 <= .04045 else ((v / 255 + .055) / 1.055) ** 2.4 for v in rgb]
            return sum(c * w for c, w in zip(linear, (.2126, .7152, .0722)))
        for target, fraction, expected in [(255, .85, 'EEE9DE'), (0, .45, '4C3B14'), (0, .74, '241C09')]:
            self.assertEqual(expected, ''.join(f'{v:02X}' for v in mix(target, fraction)))
            self.assertIn(f'mix(0x{target:02X}{target:02X}{target:02X}, {fraction})', BRAND)
        for a, b in [('8B6B24', 'FFFFFF'), ('EEE9DE', '241C09'), ('4C3B14', 'EEE9DE')]:
            la, lb = [luminance(tuple(bytes.fromhex(c))) for c in (a, b)]
            self.assertGreaterEqual((max(la, lb) + .05) / (min(la, lb) + .05), 4.5)

    def test_fixed_theme_keeps_primary_and_container_foregrounds_paired(self):
        text = (K / 'ui/theme/Theme.kt').read_text().split('fun WoowFixedBrandTheme', 1)[1]
        for seam in ('primary = WoowTechBlue', 'onPrimary = Color.White', 'onPrimaryContainer = if',
                     'PrimaryContainerLight', 'PrimaryContainerDark', 'OnPrimaryContainerLight', 'OnPrimaryContainerDark'):
            self.assertIn(seam, text)
        self.assertNotIn('ThemeManager.primaryColor', text)


class BrandFirebaseContracts(unittest.TestCase):
    def test_apporo_uses_owner_confirmed_project_not_rejected_candidate(self):
        for kind in ('debug', 'release'):
            self.assertEqual(target_for('apporo' + kind.title()).firebase_project, 'apporo-odoo-app')
            for forbidden in ('apporo-odoo', 'apporo-aiot-app', 'woow-odoo-de2cb'):
                config = fixture('apporo', kind)
                config['project_info']['project_id'] = forbidden
                with self.subTest(kind=kind, project=forbidden), self.assertRaises(BrandConfigError):
                    validate_client(config, 'apporo', kind)

    def test_all_four_client_identities_pass_policy_with_memory_only_fixtures(self):
        for brand in ('woowtech', 'apporo'):
            for kind in ('debug', 'release'):
                validate_client(fixture(brand, kind), brand, kind)

    def test_cross_brand_project_rejected_both_directions(self):
        for brand, other in [('woowtech', 'apporo'), ('apporo', 'woowtech')]:
            config = fixture(brand, 'debug')
            config['project_info']['project_id'] = target_for(other + 'Debug').firebase_project
            with self.assertRaises(BrandConfigError):
                validate_client(config, brand, 'debug')

    def test_wrong_package_or_dev_prod_rejected(self):
        for brand in ('woowtech', 'apporo'):
            with self.assertRaises(BrandConfigError):
                validate_client(fixture(brand, 'release'), brand, 'debug')

    def test_missing_files_rejected_without_root_fallback(self):
        for variant in ('woowtechDebug', 'woowtechRelease', 'apporoDebug', 'apporoRelease'):
            brand = target_for(variant).brand
            with self.assertRaises(BrandConfigError):
                validate_file(ROOT / f'nonexistent-brand-test/{variant}/google-services.json', brand, 'debug')

    def test_invalid_sender_appid_duplicate_or_missing_api_rejected(self):
        for mutation in ('sender', 'appid', 'duplicate', 'api'):
            config = fixture('apporo', 'debug')
            if mutation == 'sender': config['project_info']['project_number'] = ''
            if mutation == 'appid': config['client'][0]['client_info']['mobilesdk_app_id'] = '1:999:android:wrong'
            if mutation == 'duplicate': config['client'].append(copy.deepcopy(config['client'][0]))
            if mutation == 'api': config['client'][0]['api_key'] = []
            with self.subTest(mutation=mutation), self.assertRaises(BrandConfigError):
                validate_client(config, 'apporo', 'debug')

    def test_service_accounts_and_unknown_brand_are_rejected(self):
        with self.assertRaises(BrandConfigError):
            validate_client({'type': 'service_account'}, 'apporo', 'debug')
        with self.assertRaises(BrandConfigError):
            validate_client({}, 'unknown', 'debug')

    def test_gradle_must_select_explicit_file_and_wire_validation(self):
        self.assertIn('brand == "woowtech" && !variantConfig.exists()', GRADLE)
        self.assertIn('googleServicesJsonFiles.set(listOf(firebaseConfig))', GRADLE)
        self.assertIn('scripts/validate_brand_config.py', GRADLE)
        self.assertEqual(2, GRADLE.count('dependsOn(validateBrand)'))
        self.assertIn('if (brand == "apporo" && release)', GRADLE)
        self.assertIn('check(hasSigning("apporo"))', GRADLE)
        self.assertIn('apporoStore != woowStore', GRADLE)
        self.assertIn('apporo-keystore.properties', GRADLE)
        self.assertNotIn('if (file("google-services.json").exists())', GRADLE)


class BrandCheckoutContracts(unittest.TestCase):
    def test_ci_checkout_fetches_full_history_before_contracts(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        # Scope the assertion to the checkout step, not another action's inputs.
        steps = text.split('    steps:\n', 1)[1].split('      - ')
        checkout = [step for step in steps if step.startswith('uses: actions/checkout@v4\n')]
        self.assertEqual(1, len(checkout))
        self.assertRegex(checkout[0], r'(?m)^        with:\n(?:          #[^\n]*\n)*          fetch-depth: 0\s*$')
        self.assertLess(text.index('uses: actions/checkout@v4'), text.index('name: Offline brand contracts'))

    def test_required_baseline_commit_and_source_objects_exist(self):
        for obj in (f'{BASELINE}^{{commit}}', f'{BASELINE}:app/src/main/res',
                    f'{BASELINE}:app/gradle.lockfile',
                    f'{BASELINE}:app/src/main/java/io/woowtech/odoo/data/local/EncryptedPrefs.kt'):
            with self.subTest(object=obj):
                result = subprocess.run(['git', 'cat-file', '-e', obj], cwd=ROOT,
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self.assertEqual(0, result.returncode, 'Required baseline object missing; do not skip contracts')

    def test_local_clean_checkout_full_history_restores_baseline_show_and_tree(self):
        # Synthetic history only: no product files, Firebase configuration or network.
        with tempfile.TemporaryDirectory(prefix='brand-checkout-') as directory:
            root = Path(directory)
            env = {'PATH': os.environ['PATH'], 'HOME': directory, 'LANG': 'C',
                   'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
                   'GIT_ALLOW_PROTOCOL': 'file', 'GIT_TERMINAL_PROMPT': '0',
                   'GIT_AUTHOR_NAME': 'Fixture', 'GIT_AUTHOR_EMAIL': 'fixture@example.invalid',
                   'GIT_COMMITTER_NAME': 'Fixture', 'GIT_COMMITTER_EMAIL': 'fixture@example.invalid'}

            def git(cwd, *args, check=True):
                return subprocess.run(['git', '-c', 'core.hooksPath=' + os.devnull,
                                       '-c', 'commit.gpgsign=false', *args], cwd=cwd, env=env,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)

            source = root / 'source'
            git(root, 'init', '--template=', str(source))
            (source / 'protected.txt').write_text('synthetic baseline\n')
            git(source, 'add', 'protected.txt')
            git(source, 'commit', '-m', 'Synthetic baseline')
            base = git(source, 'rev-parse', 'HEAD').stdout.decode().strip()
            (source / 'candidate.txt').write_text('synthetic candidate\n')
            git(source, 'add', 'candidate.txt')
            git(source, 'commit', '-m', 'Synthetic candidate')
            for name, depth in (('shallow', ['--depth', '1']), ('full', [])):
                with self.subTest(checkout=name):
                    target = root / name
                    # file:// honors --depth; a plain local path clone would ignore it.
                    git(root, 'clone', *depth, source.as_uri(), str(target))
                    self.assertEqual(b'', git(target, 'status', '--porcelain').stdout)
                    self.assertEqual(b'true\n' if depth else b'false\n',
                                     git(target, 'rev-parse', '--is-shallow-repository').stdout)
                    show = git(target, 'show', f'{base}:protected.txt', check=False)
                    tree = git(target, 'ls-tree', '-r', '--name-only', base, check=False)
                    if depth:
                        self.assertNotEqual(0, show.returncode)
                        self.assertNotEqual(0, tree.returncode)
                    else:
                        self.assertEqual(0, show.returncode)
                        self.assertEqual(b'synthetic baseline\n', show.stdout)
                        self.assertEqual(0, tree.returncode)
                        self.assertEqual(b'protected.txt\n', tree.stdout)


class BrandToolingContracts(unittest.TestCase):
    def test_unapproved_writes_fail_for_both_brands(self):
        for variant in ('woowtechDebug', 'apporoDebug', 'woowtechRelease', 'apporoRelease'):
            with self.assertRaises(ValueError):
                authorize_live(target_for(variant), 'https://demo222-odoo.woowtech.io', {})

    def test_explicit_woow_authorization_is_package_and_origin_bound(self):
        target = target_for('woowtechDebug')
        url = 'https://demo222-odoo.woowtech.io'
        env = {'APP_VARIANT': target.variant, 'ALLOW_DEVICE_TEST_WRITES': target.package, 'ALLOW_ODOO_TEST_WRITES': url}
        authorize_live(target, url, env)
        for key in env:
            bad = env.copy(); bad[key] = 'other'
            with self.subTest(key=key), self.assertRaises(ValueError):
                authorize_live(target, url, bad)
        for bad_url in ('https://evil.example', url + '/web', 'http://demo222-odoo.woowtech.io'):
            bad = env.copy(); bad['ALLOW_ODOO_TEST_WRITES'] = bad_url
            with self.assertRaises(ValueError):
                authorize_live(target, bad_url, bad)

    APPORO_ACCOUNT = 'designated.reviewer@example.invalid'

    def apporo_ui_env(self, target, url):
        return {'APP_VARIANT': target.variant, 'ALLOW_DEVICE_TEST_WRITES': target.package,
                'ALLOW_ODOO_TEST_WRITES': url, 'ALLOW_APPORO_LIVE_UI': target.package,
                'APPORO_LIVE_ACCOUNT': self.APPORO_ACCOUNT}

    def test_apporo_debug_ui_allowed_only_with_explicit_flag_and_designated_account(self):
        target = target_for('apporoDebug')
        url = 'https://demo111-odoo.woowtech.io'
        env = self.apporo_ui_env(target, url)
        authorize_live(target, url, env, scope='ui', account=self.APPORO_ACCOUNT)
        for key in env:
            with self.subTest(missing=key), self.assertRaises(ValueError):
                bad = env.copy(); del bad[key]
                authorize_live(target, url, bad, scope='ui', account=self.APPORO_ACCOUNT)
            with self.subTest(wrong=key), self.assertRaises(ValueError):
                bad = env.copy(); bad[key] = 'other'
                authorize_live(target, url, bad, scope='ui', account=self.APPORO_ACCOUNT)
        for account in ('', 'someone.else@example.invalid'):
            with self.subTest(account=account), self.assertRaisesRegex(ValueError, 'APPORO_LIVE_ACCOUNT'):
                authorize_live(target, url, env, scope='ui', account=account)
        with self.assertRaisesRegex(ValueError, 'APPORO_LIVE_ACCOUNT'):
            authorize_live(target, url, {**env, 'APPORO_LIVE_ACCOUNT': ''}, scope='ui', account='')

    def test_apporo_ui_never_targets_demo222_or_release(self):
        url = 'https://demo222-odoo.woowtech.io'
        target = target_for('apporoDebug')
        with self.assertRaisesRegex(ValueError, 'demo222 is read-only'):
            authorize_live(target, url, self.apporo_ui_env(target, url), scope='ui', account=self.APPORO_ACCOUNT)
        release = target_for('apporoRelease')
        demo111 = 'https://demo111-odoo.woowtech.io'
        with self.assertRaisesRegex(ValueError, 'release packages are protected'):
            authorize_live(release, demo111, self.apporo_ui_env(release, demo111), scope='ui',
                           account=self.APPORO_ACCOUNT)

    def test_apporo_push_scope_remains_blocked_even_when_fully_flagged(self):
        target = target_for('apporoDebug')
        url = 'https://demo111-odoo.woowtech.io'
        env = self.apporo_ui_env(target, url)
        for kwargs in ({}, {'scope': 'push'}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(ValueError, 'push tests BLOCKED'):
                authorize_live(target, url, env, account=self.APPORO_ACCOUNT, **kwargs)
        with self.assertRaisesRegex(ValueError, "'ui' or 'push'"):
            authorize_live(target, url, env, scope='all', account=self.APPORO_ACCOUNT)

    def test_woow_scopes_unchanged_and_apporo_flag_does_not_unlock_woow(self):
        target = target_for('woowtechDebug')
        url = 'https://demo222-odoo.woowtech.io'
        env = {'APP_VARIANT': target.variant, 'ALLOW_DEVICE_TEST_WRITES': target.package, 'ALLOW_ODOO_TEST_WRITES': url}
        for scope in ('ui', 'push'):
            authorize_live(target, url, env, scope=scope)
        with self.assertRaises(ValueError):
            authorize_live(target, url, {'APP_VARIANT': target.variant, 'ALLOW_APPORO_LIVE_UI': target.package,
                                         'ALLOW_ODOO_TEST_WRITES': url}, scope='ui')

    def test_only_ui_only_scripts_request_ui_scope_and_push_checks_skip_for_apporo(self):
        ui_only = {'verify-on-device.py', 'e2e_15_clockin_full.py'}
        for name in SCRIPTS:
            with self.subTest(script=name):
                text = (ROOT / 'scripts' / name).read_text()
                self.assertEqual(name in ui_only, 'require_live_test_authorization(scope="ui")' in text)
        verify = (ROOT / 'scripts/verify-on-device.py').read_text()
        self.assertIn('if not APPORO_PUSH_BLOCKED and os.path.exists(SA_FILE):', verify)
        self.assertIn('APPORO_PUSH_BLOCKED = APP_TARGET.brand == "apporo"', verify)
        config = (ROOT / 'scripts/test_config.py').read_text()
        self.assertIn('def require_live_test_authorization(scope="push"):', config)
        self.assertIn('scope=scope, account=ODOO_USER', config)

    def test_all_six_live_scripts_gate_before_device_or_network_statements(self):
        for name in SCRIPTS:
            with self.subTest(script=name):
                tree = ast.parse((ROOT / 'scripts' / name).read_text())
                gate_line = next(n.lineno for n in tree.body if isinstance(n, ast.Expr) and
                                 isinstance(n.value, ast.Call) and isinstance(n.value.func, ast.Name) and
                                 n.value.func.id == 'require_live_test_authorization')
                for node in tree.body:
                    if node.lineno >= gate_line or isinstance(node, (ast.Import, ast.ImportFrom)):
                        continue
                    # Only sys.path insertion/string docstrings are allowed ahead of the gate.
                    calls = [n for n in ast.walk(node) if isinstance(n, ast.Call)]
                    for call in calls:
                        self.assertNotRegex(ast.unparse(call.func), r'^(requests|subprocess|u2|d)\.')
                for n in ast.walk(tree):
                    if isinstance(n, ast.Constant) and isinstance(n.value, str):
                        self.assertNotEqual('io.woowtech.odoo.debug', n.value)

    def test_config_paths_and_namespace_are_explicit_and_secrets_not_dumped(self):
        text = (ROOT / 'scripts/test_config.py').read_text()
        self.assertIn('APP_ACTIVITY = "io.woowtech.odoo.ui.MainActivity"', text)
        self.assertIn('APP_PACKAGE != APP_TARGET.package', text)
        self.assertIn('FIREBASE_PROJECT_ID != APP_TARGET.firebase_project', text)
        self.assertIn('/ APP_VARIANT)', text)
        self.assertNotIn('globals()[k]', text)
        self.assertIn('if APP_TARGET.brand == "woowtech" else ""', text)

    def test_ci_builds_and_tests_both_flavors_without_publishing(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        for variant in ('WoowtechDebug', 'ApporoDebug'):
            self.assertIn('task: ' + variant, text)
        self.assertIn(':app:test${{ matrix.task }}UnitTest', text)
        self.assertIn('contents: read', text)
        for forbidden in ('action-gh-release', 'contents: write', 'publish', 'upload-google-play'):
            self.assertNotIn(forbidden, text.replace('no publishing', ''))

    def test_all_python_and_xml_sources_parse_without_execution(self):
        for path in [*(ROOT / 'scripts').glob('*.py'), *(ROOT / 'scripts/tests').glob('*.py')]:
            ast.parse(path.read_text(), filename=str(path))
        for path in (ROOT / 'app/src').rglob('*.xml'):
            ET.parse(path)


if __name__ == '__main__':
    unittest.main()
