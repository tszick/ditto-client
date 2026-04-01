# ditto-go-client

Go client library for Ditto (`dittod`) with HTTP and TCP clients.

## Features

- HTTP client (port 7778): `Ping`, `Get`, `Set`, `Delete`, `Stats`, pattern ops
- TCP client (port 7777): `Ping`, `Get`, `Set`, `Delete`, pattern ops, optional auth token
- No dependency on `ditto-mgmt`

## Quick usage

```go
httpClient := ditto.NewHTTPClient(ditto.HTTPClientOptions{Host: "localhost", Port: 7778})
stats, err := httpClient.Stats()

client := ditto.NewTCPClient(ditto.TCPClientOptions{Host: "localhost", Port: 7777})
_ = client.Connect()
_, _ = client.SetString("k", "v", 60)
```