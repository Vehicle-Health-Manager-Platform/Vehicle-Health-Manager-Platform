FROM alpine:3.21

# Docker Hub no longer serves the pinned community image. Build the same
# release from its official GitHub asset and verify the published digest.
RUN apk add --no-cache ca-certificates curl \
    && curl --fail --location --retry 3 \
      https://github.com/minio/minio/releases/download/RELEASE.2025-07-23T15-54-02Z/minio.linux-amd64.RELEASE.2025-07-23T15-54-02Z \
      --output /usr/local/bin/minio \
    && echo 'eef6581f6509f43ece007a6f2eb4c5e3ce41498c8956e919a7ac7b4b170fa431  /usr/local/bin/minio' | sha256sum -c - \
    && chmod +x /usr/local/bin/minio

EXPOSE 9000 9001
ENTRYPOINT ["/usr/local/bin/minio"]
