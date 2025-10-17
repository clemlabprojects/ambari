# ---- Build stage ----
FROM golang:1.25 AS builder
WORKDIR /src

# 1) deps: copy go.mod + go.sum first
COPY go.mod go.sum ./
RUN go mod download

# 2) sources
COPY . .

# 3) write the missing sums based on imports
RUN go mod tidy

# 4) build
ENV CGO_ENABLED=0
RUN go build -trimpath -ldflags="-s -w" -o /out/kerberos-keytab-mutating-webhook ./...

# ---- Run stage (distroless, non-root) ----
FROM gcr.io/distroless/static:nonroot
WORKDIR /
COPY --from=builder /out/kerberos-keytab-mutating-webhook /kerberos-keytab-mutating-webhook
USER nonroot:nonroot
EXPOSE 8443
ENV LISTEN_ADDRESS=":8443"
VOLUME ["/tls"]
ENTRYPOINT ["/kerberos-keytab-mutating-webhook"]

