# Training job image: runs as a Kubernetes Job with no network egress and a read-only root FS.
FROM python:3.12-slim
RUN useradd --uid 10001 --create-home trainer
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY features.py train.py ./
USER 10001
ENTRYPOINT ["python", "/app/train.py"]
