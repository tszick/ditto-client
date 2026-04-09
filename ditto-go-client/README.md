# ditto-go-client

Go client library for Ditto (`dittod`) with HTTP and TCP clients.

## Features

- HTTP client (port 7778): `Ping`, `Get`, `Set`, `Delete`, `Stats`, pattern ops
- TCP client (port 7777): `Ping`, `Get`, `Set`, `Delete`, pattern ops, `Watch`/`Unwatch`, optional auth token
- Namespace-aware operations on both protocols
- No dependency on `ditto-mgmt`

## Quick usage

```go
httpClient := ditto.NewHTTPClient(ditto.HTTPClientOptions{Host: "localhost", Port: 7778})
stats, err := httpClient.Stats()

client := ditto.NewTCPClient(ditto.TCPClientOptions{Host: "localhost", Port: 7777})
_ = client.Connect()
_, _ = client.SetString("k", "v", 60)
```

## Namespace usage

```go
httpClient := ditto.NewHTTPClient(ditto.HTTPClientOptions{Host: "localhost", Port: 7778})
_, _ = httpClient.SetInNamespace("k", []byte("v"), "tenant-acme", 60)
_, _ = httpClient.Get("k", "tenant-acme")

tcp := ditto.NewTCPClient(ditto.TCPClientOptions{Host: "localhost", Port: 7777})
_ = tcp.Connect()
_, _ = tcp.SetInNamespace("k", []byte("v"), "tenant-acme", 60)
_, _ = tcp.Get("k", "tenant-acme")
```

For `Get/Delete/DeleteByPattern/SetTtlByPattern`, the namespace is passed as an optional variadic argument.
