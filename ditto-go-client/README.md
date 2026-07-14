# ditto-go-client

Go client library for Ditto (`dittod`) with HTTP and TCP clients.

## Features

- HTTP client (port 7778): `Ping`, `Get`, `Set`, `Delete`, `Stats`, pattern ops
- TCP client (port 7777): `Ping`, `Get`, `Set`, `Delete`, pattern ops, `Watch`/`Unwatch`, `WaitWatchEvent`, optional auth token, optional TLS
- Namespace-aware operations on both protocols
- Optional one-shot reconnect retry on TCP via `AutoReconnect: true`
- HTTP errors prefer server payload codes (for example `RateLimited`, `CircuitOpen`, `NamespaceQuotaExceeded`) with status fallback
- No dependency on `ditto-mgmt`

## Quick usage

```go
httpClient := ditto.NewHTTPClient(ditto.HTTPClientOptions{Host: "localhost", Port: 7778})
stats, err := httpClient.Stats()

client := ditto.NewTCPClient(ditto.TCPClientOptions{
    Host:      "cache.example.internal",
    Port:      7777,
    TLS:       true,
    TLSCACert: "/etc/ssl/certs/ditto-ca.pem",
    AuthToken: os.Getenv("DITTO_TCP_TOKEN"),
})
_ = client.Connect()
_, _ = client.SetString("k", "v", 60)
```

## TLS behavior (HTTP client)

- TLS certificate verification is enabled by default when `TLS: true`.
- `InsecureSkipVerify: true` and `DevInsecureTLS: true` are insecure and are now ignored.
- Use a trusted certificate configuration instead of disabling verification.
- `RejectUnauthorized` remains for backward compatibility; certificate verification stays enabled.

## TLS behavior (TCP client)

- TLS is available on direct TCP via `TLS: true`.
- Trust can be extended with `TLSCACert` (PEM string or PEM file path).
- `TLSServerName` can override the verification name when the dial target and certificate name differ.
- No insecure TLS bypass is exposed for TCP.

## TCP timeout behavior

- `ConnectTimeout` only bounds establishing the TCP/TLS connection.
- `ReadTimeout` bounds an active request/response exchange or an active `WaitWatchEvent()` call.
- Idle TCP connections are not closed just because a previous request used a short timeout.

## Watch + reconnect example

```go
tcp := ditto.NewTCPClient(ditto.TCPClientOptions{
    Host:          "localhost",
    Port:          7777,
    AutoReconnect: true,
})
_ = tcp.Connect()
_ = tcp.Watch("k")
_, _ = tcp.SetString("k", "value")
ev, _ := tcp.WaitWatchEvent()
_ = ev
_ = tcp.Unwatch("k")
_ = tcp.Close()
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
Whitespace-only namespace values are treated as omitted (header not sent).

## Tests

```bash
go test ./...
```
