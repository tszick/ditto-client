# Cross-SDK Contract Specs

This directory contains language-agnostic contract specs used to keep SDK behavior aligned.

Current phase:
- spec structure validation in CI,
- initial runtime adapter execution available in Go SDK tests (`ditto-go-client/contract_runner_test.go`).

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
