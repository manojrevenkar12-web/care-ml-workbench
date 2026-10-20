"""Write parity.json: a small model trained here plus the tags it assigns to a set of texts.
The Kotlin test `ParityTest` loads the same model and must reproduce every tag exactly."""

import json
import sys
from pathlib import Path

from train import TAGS, export, load, train

CASES = [
    "Vitalzeichen: RR 135/85, Puls 72. Frühstück vollständig gegessen.",
    "Klagt über Schmerzen im Knie, Stärke 6 von 10. Metamizol 500 mg bei Bedarf verabreicht.",
    "Frau Becker in [ZIMMER]: Dekubitus am Steiß Grad 2, Verband gewechselt. Wirkt unruhig, nachts mehrfach wach.",
    "Inkontinenzmaterial gewechselt. Trinkmenge 1200 ml. Temperatur von 37,8 Grad.",
    "äh also Blutzucker lag bei 180 mg/dl und Transfer in den Rollstuhl mit Hilfe.",
    "Ümläute, Sonderzeichen & Zahlen: 3/4, 1.5, [TELEFON] — und nichts weiter",
]

if __name__ == "__main__":
    examples = [e for e in load(Path(sys.argv[1])) if e["split"] == "train"]
    model = train(examples, buckets=1024, c=4.0, epochs=4)
    out = {"model": export(model, "parity", "fixture"), "cases": [{"text": t, "tags": model.tags(t)} for t in CASES]}
    assert out["model"]["labels"] == TAGS
    Path(__file__).with_name("parity.json").write_text(json.dumps(out))
    print("wrote parity.json with", sum(len(c["tags"]) for c in out["cases"]), "tagged tokens")
