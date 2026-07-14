from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client.validation import normalized_namespace, validate_core_inputs, validate_pattern_inputs


def _namespace_arg(value):
    return None if value is None else value


class StrictValidationContractTests(unittest.TestCase):
    def test_strict_validation_contract(self) -> None:
        contract_path = Path(__file__).resolve().parents[2] / "contracts" / "strict-validation.contract.json"
        suite = json.loads(contract_path.read_text(encoding="utf-8"))

        for case in suite["cases"]:
            with self.subTest(case=case["id"]):
                operation = case["operation"]
                inputs = case["inputs"]
                expect = case["expect"]

                if operation == "validate_core":
                    result = self._capture(
                        lambda: validate_core_inputs(
                            inputs["strict_mode"],
                            inputs["op"],
                            inputs["key"],
                            _namespace_arg(inputs.get("namespace")),
                        )
                    )
                    self._assert_validation_result(result, expect)
                    continue

                if operation == "validate_pattern":
                    result = self._capture(
                        lambda: validate_pattern_inputs(
                            inputs["strict_mode"],
                            inputs["op"],
                            inputs["pattern"],
                            _namespace_arg(inputs.get("namespace")),
                        )
                    )
                    self._assert_validation_result(result, expect)
                    continue

                if operation == "normalize_namespace":
                    result = self._capture(
                        lambda: normalized_namespace(
                            inputs["strict_mode"],
                            _namespace_arg(inputs.get("namespace")),
                        )
                    )
                    if "error_contains" in expect:
                        self.assertIsInstance(result, Exception)
                        self.assertIn(expect["error_contains"], str(result))
                    else:
                        self.assertEqual(expect.get("normalized"), result)
                    continue

                self.fail(f"unsupported contract operation: {operation}")

    def _capture(self, fn):
        try:
            return fn()
        except Exception as exc:  # noqa: BLE001
            return exc

    def _assert_validation_result(self, result, expect) -> None:
        if expect.get("valid") is True:
            if isinstance(result, Exception):
                self.fail(f"expected valid result, got {result!r}")
            return
        self.assertIsInstance(result, Exception)
        self.assertIn(expect["error_contains"], str(result))


if __name__ == "__main__":
    unittest.main()
