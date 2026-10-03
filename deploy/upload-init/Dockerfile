FROM minio/mc:RELEASE.2025-08-13T08-35-41Z AS client
FROM alpine:3.21
RUN apk add --no-cache ca-certificates jq
COPY --from=client /usr/bin/mc /usr/local/bin/mc
COPY initialize.sh /usr/local/bin/initialize-upload
RUN chmod 755 /usr/local/bin/initialize-upload
ENTRYPOINT ["/usr/local/bin/initialize-upload"]
