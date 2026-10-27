# Build from the repository root with the hash-verified asr_wheels named context.
# Wheels are installed offline; Linux synthetic-sample acceptance is recorded separately.
FROM python:3.11-slim-bookworm@sha256:f06d01d4ba3f8ad5af45a3ec87df19c1d317d8e8961edc0fabda61811af6f7cf
WORKDIR /app
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends libgomp1 libseccomp2 \
    && rm -rf /var/lib/apt/lists/*
RUN --mount=type=bind,from=asr_wheels,target=/wheels \
    pip install --no-cache-dir --no-index --find-links=/wheels --only-binary=:all: --require-hashes -r /wheels/requirements.lock && pip check
COPY services/asr/src/ /app/
COPY services/ocr/src/process_limits.py services/ocr/src/network_guard.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 HF_HOME=/tmp/huggingface \
    OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2
USER 10001:10001
EXPOSE 8091
CMD ["python", "asr_server.py"]
