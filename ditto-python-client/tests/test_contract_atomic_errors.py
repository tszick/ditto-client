from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from ditto_client.client_internal import atomic_http_unsupported_error, normalize_atomic_tcp_error
from ditto_client.types import DittoError


class AtomicErrorsContractTests(unittest.TestCase):
    def test_atomic_errors_contract(self) -> None:
        contract_path = Path(__file__).resolve().parents[2] / "contracts" / "atomic-errors.contract.json"
        suite = json.loads(contract_path.read_text(encoding="utf-8"))

        for case in suite["cases"]:
            with self.subTest(case=case["id"]):
                operation = case["operation"]
                inputs = case["inputs"]
                expect = case["expect"]

                if operation == "normalize_http_atomic_error":
                    err = atomic_http_unsupported_error(inputs["body"], inputs["operation_name"])
                elif operation == "normalize_tcp_atomic_error":
                    source = (
                        DittoError(inputs["error_code"], inputs["error_message"])
                        if inputs["error_kind"] == "ditto"
                        else RuntimeError(inputs["error_message"])
                    )
                    err = normalize_atomic_tcp_error(source, inputs["operation_name"])
                else:
                    self.fail(f"unsupported contract operation: {operation}")
                    return

                self.assertIsInstance(err, DittoError)
                self.assertEqual(expect["code"], err.code)
                self.assertIn(expect["message_contains"], str(err))


if __name__ == "__main__":
    unittest.main()
