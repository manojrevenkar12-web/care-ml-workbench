import { addSpan, rebase, segments, snap } from "./spans";

describe("rebase", () => {
  const text = "RR 130/80, Puls 72. Schmerzen im Knie";
  const spans = [
    { start: 0, end: 9, label: "VITAL_SIGN" as const },
    { start: 11, end: 18, label: "VITAL_SIGN" as const },
    { start: 20, end: 37, label: "PAIN" as const },
  ];
  it("shifts spans after an edit and keeps spans before it", () => {
    const edited = text.replace("Puls 72.", "Puls 72 regelmäßig.");
    const r = rebase(text, edited, spans);
    expect(r).toHaveLength(3);
    expect(edited.slice(r[2].start, r[2].end)).toBe("Schmerzen im Knie");
    expect(r[0]).toEqual(spans[0]);
  });
  it("drops spans the edit touches", () => {
    const edited = text.replace("130/80", "135/85");
    expect(rebase(text, edited, spans).map((s) => s.label)).toEqual(["VITAL_SIGN", "PAIN"]);
  });
});

describe("snap", () => {
  const t = "Klagt über Schmerzen im Knie, Stärke 6.";
  it("extends a partial selection to whole words and trims punctuation", () => {
    const [a, b] = snap(t, t.indexOf("hmer"), t.indexOf("Knie") + 2)!;
    expect(t.slice(a, b)).toBe("Schmerzen im Knie");
  });
  it("returns null for whitespace", () => expect(snap(t, 5, 6)).toBeNull());
});

describe("addSpan and segments", () => {
  it("refuses overlaps", () => {
    const r = addSpan([{ start: 0, end: 5, label: "PAIN" }], 3, 8, "MOOD");
    expect(r.error).toBeTruthy();
  });
  it("cuts text at span and surrogate boundaries", () => {
    const segs = segments("Frau Becker hat Schmerzen", [{ start: 16, end: 25, label: "PAIN" }], [{ start: 5, end: 11, kind: "PERSON_NAME" }]);
    expect(segs.map((s) => s.text)).toEqual(["Frau ", "Becker", " hat ", "Schmerzen"]);
    expect(segs[1].surrogate?.kind).toBe("PERSON_NAME");
    expect(segs[3].span?.label).toBe("PAIN");
  });
});
