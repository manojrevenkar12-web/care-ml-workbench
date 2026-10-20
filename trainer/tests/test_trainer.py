import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from features import bio, fnv1a, spans, tokens  # noqa: E402
from train import main  # noqa: E402


def test_tokens_and_hash_match_the_kotlin_definition():
    assert [t[0] for t in tokens("RR 130/80 , Puls 72 [ZIMMER]")] == ["RR", "130/80", ",", "Puls", "72", "[ZIMMER]"]
    assert fnv1a("a", 2**32) == 0xE40C292C


def test_bio_round_trips_to_the_same_spans():
    text = "RR 130/80, Puls 72. Schmerzen im Knie"
    ents = [{"start": 0, "end": 9, "label": "VITAL_SIGN"}, {"start": 11, "end": 18, "label": "VITAL_SIGN"}, {"start": 20, "end": 37, "label": "PAIN"}]
    assert spans(tokens(text), bio(text, ents)) == ents


def _example(i, split):
    text = f"Vitalzeichen: RR {120 + i % 30}/80, Puls {60 + i % 20}. Klagt über Schmerzen im Knie."
    return {
        "item_id": f"i{i}", "split": split, "facility_id": "f", "subject": "s", "text": text, "asr": {},
        "entities": [
            {"start": 14, "end": 23, "label": "VITAL_SIGN"},
            {"start": 25, "end": 25 + len(f"Puls {60 + i % 20}"), "label": "VITAL_SIGN"},
            {"start": text.index("Schmerzen"), "end": text.index("Schmerzen") + len("Schmerzen im Knie"), "label": "PAIN"},
        ],
    }


def test_training_job_writes_model_and_metrics(tmp_path):
    data = tmp_path / "d.jsonl"
    data.write_text("\n".join(json.dumps(_example(i, "train" if i < 40 else "test")) for i in range(50)))
    assert main(["--dataset", str(data), "--out", str(tmp_path / "out"), "--buckets", "2048"]) == 0
    metrics = json.loads((tmp_path / "out" / "metrics.json").read_text())
    model = json.loads((tmp_path / "out" / "model.json").read_text())
    assert metrics["test_f1"] > 0.9
    assert model["buckets"] == 2048 and model["feature_version"] == 1


def test_refuses_too_little_data(tmp_path):
    data = tmp_path / "d.jsonl"
    data.write_text(json.dumps(_example(1, "train")))
    assert main(["--dataset", str(data), "--out", str(tmp_path / "o")]) == 2
