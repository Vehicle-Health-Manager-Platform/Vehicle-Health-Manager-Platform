FROM alpine:3.21
RUN apk add --no-cache ca-certificates curl jq \
    && curl --fail --location --retry 3 --connect-timeout 10 --max-time 120 \
      https://github.com/minio/mc/releases/download/RELEASE.2025-08-13T08-35-41Z/mc.linux-amd64.RELEASE.2025-08-13T08-35-41Z \
      --output /usr/local/bin/mc \
    && echo '01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891  /usr/local/bin/mc' | sha256sum -c - \
    && chmod 755 /usr/local/bin/mc
COPY initialize.sh /usr/local/bin/initialize-upload
RUN chmod 755 /usr/local/bin/initialize-upload
ENTRYPOINT ["/usr/local/bin/initialize-upload"]
