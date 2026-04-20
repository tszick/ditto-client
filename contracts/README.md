# Cross-SDK Contract Specs

This directory contains language-agnostic contract specs used to keep SDK behavior aligned.

Current phase:
- spec structure validation in CI,
- runtime adapter execution available across all maintained SDKs:
  - Node: `ditto-nodejs-client/tests/contract-runtime.test.mjs`
  - Go: `ditto-go-client/contract_runner_test.go`
  - Python: `ditto-python-client/tests/test_contract_runtime.py`
  - Java: `ditto-java-client/src/test/java/io/ditto/client/DittoContractRuntimeSmokeTest.java`

Files:
- `*.contract.json`: contract suites (versioned JSON specs),
- `validate_contracts.py`: structural validator run in CI.

Validation:

```bash
cd ditto-client
python contracts/validate_contracts.py
```

Runtime adapter (Go):

```bash
cd ditto-client/ditto-go-client
go test ./... -run TestCoreOpsContractHTTPRunner -v
```
