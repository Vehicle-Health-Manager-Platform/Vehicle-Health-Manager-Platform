# Build from repository root; prepare and verify embedding_wheels first.
# The guard image supplies only Debian libgomp/libseccomp; no ASR Python packages.
ARG GUARD_IMAGE=autocare-asr-runtime:acceptance
FROM ${GUARD_IMAGE} AS guards
FROM python:3.11-slim-bookworm@sha256:f06d01d4ba3f8ad5af45a3ec87df19c1d317d8e8961edc0fabda61811af6f7cf
WORKDIR /app
COPY --from=guards /usr/lib/x86_64-linux-gnu/libgomp.so.1 /usr/lib/x86_64-linux-gnu/libgomp.so.1
COPY --from=guards /usr/lib/x86_64-linux-gnu/libseccomp.so.2 /usr/lib/x86_64-linux-gnu/libseccomp.so.2
RUN --mount=type=bind,from=embedding_wheels,target=/wheels \
    pip install --no-cache-dir --no-index --find-links=/wheels --only-binary=:all: --require-hashes -r /wheels/requirements.lock && pip check
COPY services/embedding/src/ /app/
COPY services/embedding/models.lock.json services/embedding/runtime-wheels-linux.json /app/
COPY services/ocr/src/process_limits.py services/ocr/src/network_guard.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 HF_HOME=/tmp/huggingface \
    OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 MKL_NUM_THREADS=2 TOKENIZERS_PARALLELISM=false \
    EMBEDDING_SERVICE_ENABLED=false EMBEDDING_MODEL_ENABLED=false
USER 10001:10001
EXPOSE 8093
CMD ["python", "embedding_server.py"]
