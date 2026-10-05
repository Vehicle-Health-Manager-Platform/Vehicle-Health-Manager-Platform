"""Offline contract tests for the read-only auth readiness probe."""

import unittest

from scripts.check_auth_readiness import main, run_checks, validate_base_url


class ValidateBaseUrlTest(unittest.TestCase):
    def test_accepts_https_origin(self):
        self.assertEqual(validate_base_url("https://api.example.test/"), "https://api.example.test")
        self.assertEqual(validate_base_url("https://api.example.test:8443"), "https://api.example.test:8443")

    def test_rejects_unsafe_or_non_origin_urls(self):
        for value in (
            "http://api.example.test", "https://user:pass@api.example.test",
            "https://api.example.test/path", "https://api.example.test/?x=1",
            "https://api.example.test/#x", "https://api.example.test:bad",
            "https:///", "https://api.example.test//",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_base_url(value)


class RunChecksTest(unittest.TestCase):
    def test_expected_contract_passes_and_uses_only_two_get_urls(self):
        called = []

        def fetch(url):
            called.append(url)
            return (200, b'{"status":"UP"}') if url.endswith("/health") else (401, b'{"code":40100}')

        results = run_checks("https://api.example.test", fetch)
        self.assertEqual([item[1] for item in results], [True, True])
        self.assertEqual(called, [
            "https://api.example.test/actuator/health",
            "https://api.example.test/api/vehicle/list",
        ])

    def test_bad_json_or_contract_fails(self):
        for response in ((200, b"bad"), (200, b'{"status":"DOWN"}'), (302, b"")):
            with self.subTest(response=response):
                results = run_checks("https://api.example.test", lambda _: response)
                self.assertFalse(results[0][1])
        for response in ((200, b'{"code":0}'), (401, b'{"code":40300}'), (401, b"bad")):
            with self.subTest(response=response):
                results = run_checks("https://api.example.test", lambda _: response)
                self.assertFalse(results[1][1])

    def test_network_error_is_sanitized(self):
        def fetch(_):
            raise OSError("secret-bearing-host-details")

        results = run_checks("https://api.example.test", fetch)
        self.assertEqual([item[1] for item in results], [False, False])
        self.assertTrue(all("secret-bearing" not in item[2] for item in results))

    def test_invalid_cli_url_fails_without_network(self):
        self.assertEqual(main(["--base-url", "http://example.invalid"]), 2)


if __name__ == "__main__":
    unittest.main()
