"""Training job for the care-entity tagger.

Input: a snapshot export (JSONL, one de-identified example per line, with its split).
Output (in --out): model.json (served by the Kotlin API), metrics.json, and the metrics again in
/dev/termination-log when running as a Kubernetes Job (the API reads them from the pod status).

The model is multinomial logistic regression over hashed token features with BIO labels: small,
fast to train on CPU, and exactly reproducible by the Kotlin inference code.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
from scipy import sparse
from sklearn.linear_model import LogisticRegression

from features import VERSION, bio, features, fnv1a, spans, tokens

LABELS = ["VITAL_SIGN", "MEDICATION", "DOSAGE", "WOUND", "PAIN", "MOBILITY", "NUTRITION", "EXCRETION", "MOOD", "SLEEP"]
TAGS = ["O"] + [f"{p}-{l}" for l in LABELS for p in ("B", "I")]


def load(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def matrix(examples: list[dict], buckets: int) -> tuple[sparse.csr_matrix, np.ndarray]:
    rows, cols, ys = [], [], []
    r = 0
    for ex in examples:
        toks = tokens(ex["text"])
        tags = bio(ex["text"], ex["entities"])
        for i in range(len(toks)):
            for f in features(toks, i):
                rows.append(r)
                cols.append(fnv1a(f, buckets))
            ys.append(TAGS.index(tags[i]))
            r += 1
    data = np.ones(len(rows), dtype=np.float32)
    X = sparse.csr_matrix((data, (rows, cols)), shape=(r, buckets))  # duplicates sum, like Kotlin
    return X, np.array(ys)


class Model:
    def __init__(self, W: np.ndarray, b: np.ndarray, labels: list[str], buckets: int):
        self.W, self.b, self.labels, self.buckets = W, b, labels, buckets

    def tags(self, text: str) -> list[str]:
        toks = tokens(text)
        out = []
        for i in range(len(toks)):
            idx = [fnv1a(f, self.buckets) for f in features(toks, i)]
            scores = self.b.astype(np.float64) + self.W[:, idx].astype(np.float64).sum(axis=1)
            out.append(self.labels[int(np.argmax(scores))])
        return out

    def predict(self, text: str) -> list[dict]:
        return spans(tokens(text), self.tags(text))


def score(model: Model, examples: list[dict]) -> dict:
    tp = fp = fn = 0
    for ex in examples:
        gold = {(e["start"], e["end"], e["label"]) for e in ex["entities"]}
        pred = {(e["start"], e["end"], e["label"]) for e in model.predict(ex["text"])}
        tp += len(gold & pred)
        fp += len(pred - gold)
        fn += len(gold - pred)
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return {"precision": p, "recall": r, "f1": 2 * p * r / (p + r) if p + r else 0.0, "entities": tp + fn}


def train(examples: list[dict], buckets: int, c: float, epochs: int) -> Model:
    X, y = matrix(examples, buckets)
    clf = LogisticRegression(C=c, max_iter=max(100, epochs * 50), random_state=0)
    clf.fit(X, y)
    W = np.zeros((len(TAGS), buckets), dtype=np.float32)
    b = np.full(len(TAGS), -1e9, dtype=np.float32)  # tags never seen in training are never predicted
    for k, cls in enumerate(clf.classes_):
        W[cls] = clf.coef_[k] if len(clf.classes_) > 2 else (clf.coef_[0] if k == 1 else -clf.coef_[0])
        b[cls] = clf.intercept_[k] if len(clf.classes_) > 2 else (clf.intercept_[0] if k == 1 else -clf.intercept_[0])
    return Model(W, b, TAGS, buckets)


def export(model: Model, name: str, dataset_sha: str) -> dict:
    return {
        "name": name,
        "feature_version": VERSION,
        "labels": model.labels,
        "buckets": model.buckets,
        "weights": base64.b64encode(model.W.astype("<f4").tobytes()).decode(),
        "intercept": base64.b64encode(model.b.astype("<f4").tobytes()).decode(),
        "dataset_sha256": dataset_sha,
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--job-id", default="local")
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--c", type=float, default=4.0)
    ap.add_argument("--buckets", type=int, default=16384)
    a = ap.parse_args(argv)
    t0 = time.time()
    data_path = Path(a.dataset)
    examples = load(data_path)
    by = {s: [e for e in examples if e["split"] == s] for s in ("train", "dev", "test")}
    if len(by["train"]) < 10:
        print(f"only {len(by['train'])} training examples; need at least 10", file=sys.stderr)
        return 2
    print(f"job {a.job_id}: {len(by['train'])} train / {len(by['dev'])} dev / {len(by['test'])} test examples", flush=True)
    model = train(by["train"], a.buckets, a.c, a.epochs)
    dev, test = score(model, by["dev"]), score(model, by["test"])
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    sha = hashlib.sha256(data_path.read_bytes()).hexdigest()
    (out / "model.json").write_text(json.dumps(export(model, a.job_id, sha)))
    metrics = {
        "dev_f1": round(dev["f1"], 4),
        "test_f1": round(test["f1"], 4),
        "test_precision": round(test["precision"], 4),
        "test_recall": round(test["recall"], 4),
        "train_examples": len(by["train"]),
        "test_entities": test["entities"],
        "seconds": round(time.time() - t0, 2),
    }
    (out / "metrics.json").write_text(json.dumps(metrics))
    term = Path("/dev/termination-log")
    if os.environ.get("KUBERNETES_SERVICE_HOST") and term.exists():
        term.write_text(json.dumps(metrics))
    print(json.dumps(metrics), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
