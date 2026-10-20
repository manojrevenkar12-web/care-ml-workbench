import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Transcript } from "../components/Transcript";
import { Empty, ErrorNote, fix, Metric, PageHead, pct } from "../components/ui";
import { api } from "../lib/api";
import { LABEL_STYLE } from "../lib/spans";
import type { Label, Project } from "../lib/types";

export function Evaluate({ project }: { project: Project }) {
  const snaps = useQuery({ queryKey: ["snapshots", project.id], queryFn: () => api.snapshots(project.id) });
  const models = useQuery({ queryKey: ["models", project.id], queryFn: () => api.models(project.id) });
  const [snap, setSnap] = useState("");
  const [model, setModel] = useState("");
  const s = snap || snaps.data?.[0]?.id || "";
  const m = model || models.data?.[models.data.length - 1]?.name || "";

  const ev = useQuery({ queryKey: ["eval", s, m], queryFn: () => api.evaluate(s, m), enabled: !!s && !!m });
  const cmp = useQuery({ queryKey: ["compare", s], queryFn: () => api.compare(s, "asr-v1", "asr-v2"), enabled: !!s });

  if (snaps.data?.length === 0) return <><PageHead title="Evaluation" /><div className="panel"><Empty title="Nothing to evaluate yet">Evaluation runs on a dataset version. Create one first.</Empty></div></>;

  return (
    <>
      <PageHead title="Evaluation" intro="Scores are computed on the held-out test split of a dataset version; no resident in it was seen during training." />
      <div className="row" style={{ marginBottom: 20 }}>
        <label className="field"><span>Dataset version</span>
          <select value={s} onChange={(e) => setSnap(e.target.value)}>{snaps.data?.map((x) => <option key={x.id} value={x.id}>Version {x.version}</option>)}</select>
        </label>
        <label className="field"><span>Entity model</span>
          <select value={m} onChange={(e) => setModel(e.target.value)}>
            {models.data?.length === 0 && <option value="">No trained models yet</option>}
            {models.data?.map((x) => <option key={x.name}>{x.name}</option>)}
          </select>
        </label>
      </div>

      <div className="stack" style={{ gap: 24 }}>
        <section className="panel panel-pad stack" aria-label="Speech models">
          <div>
            <h2>Speech recognition: asr-v2 against asr-v1</h2>
            <p className="small muted" style={{ marginTop: 4 }}>Word error rate against the corrected transcripts. The interval is a 95% paired bootstrap over transcripts; if it excludes zero, the difference is unlikely to be noise.</p>
          </div>
          <ErrorNote error={cmp.error} />
          {cmp.data && (
            <>
              <div className="metrics">
                <Metric label="asr-v1 WER" value={pct(cmp.data.wer_a)} />
                <Metric label="asr-v2 WER" value={pct(cmp.data.wer_b)} />
                <Metric label="Change" value={`${cmp.data.difference > 0 ? "+" : ""}${(cmp.data.difference * 100).toFixed(1)} pts`}
                  hint={`95% interval ${(cmp.data.ci95[0] * 100).toFixed(1)} to ${(cmp.data.ci95[1] * 100).toFixed(1)} pts`} />
                <Metric label="Transcripts compared" value={cmp.data.items} />
              </div>
              <SliceChart bySlice={cmp.data.by_slice} />
            </>
          )}
        </section>

        <section className="panel panel-pad stack" aria-label="Entity model">
          <div>
            <h2>Care-entity tagger{m ? `: ${m}` : ""}</h2>
            <p className="small muted" style={{ marginTop: 4 }}>Exact-match precision and recall per label. A prediction counts only if both the boundaries and the label match.</p>
          </div>
          <ErrorNote error={ev.error} />
          {!m && <p className="muted">Train a model to see its scores here.</p>}
          {ev.data && (
            <>
              <div className="metrics">
                <Metric label="F1" value={fix(ev.data.micro.f1)} />
                <Metric label="Precision" value={fix(ev.data.micro.precision)} />
                <Metric label="Recall" value={fix(ev.data.micro.recall)} />
                <Metric label="Test transcripts" value={ev.data.examples} hint={`${ev.data.micro.support} entities`} />
              </div>
              <table className="table">
                <thead><tr><th>Label</th><th className="r">Precision</th><th className="r">Recall</th><th className="r">F1</th><th className="r">Entities</th><th style={{ width: "30%" }} /></tr></thead>
                <tbody>
                  {Object.entries(ev.data.per_label).sort((a, b) => a[1].f1 - b[1].f1).map(([l, p]) => {
                    const st = LABEL_STYLE[l as Label];
                    return (
                      <tr key={l}>
                        <td><span className="tag" style={{ background: st?.bg, color: st?.fg }}>{st?.name ?? l}</span></td>
                        <td className="r">{fix(p.precision)}</td><td className="r">{fix(p.recall)}</td><td className="r"><b>{fix(p.f1)}</b></td><td className="r">{p.support}</td>
                        <td><div className="bar"><span style={{ width: `${p.f1 * 100}%`, background: st?.fg }} /></div></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              {ev.data.errors.length > 0 && (
                <details>
                  <summary>Where the model is wrong ({ev.data.errors.length} examples)</summary>
                  <div className="stack" style={{ marginTop: 14 }}>
                    {ev.data.errors.slice(0, 12).map((e, i) => (
                      <div key={i} className="compare">
                        <div><p className="small muted">Correct</p><Transcript compact text={e.text} spans={e.gold} /></div>
                        <div><p className="small muted">Model</p><Transcript compact text={e.text} spans={e.predicted} /></div>
                      </div>
                    ))}
                  </div>
                </details>
              )}
            </>
          )}
        </section>
      </div>
    </>
  );
}

function SliceChart({ bySlice }: { bySlice: Record<string, { wer_a: number; wer_b: number }> }) {
  const rows = Object.entries(bySlice);
  const max = Math.max(0.01, ...rows.flatMap(([, v]) => [v.wer_a, v.wer_b]));
  return (
    <div>
      <div className="spread small muted" style={{ marginBottom: 8 }}>
        <span>By facility</span>
        <span className="row" style={{ gap: 14 }}>
          <span><span className="swatch" style={{ display: "inline-block", width: 10, height: 10, background: "#b5c2c9", marginRight: 6 }} />asr-v1</span>
          <span><span className="swatch" style={{ display: "inline-block", width: 10, height: 10, background: "var(--accent)", marginRight: 6 }} />asr-v2</span>
        </span>
      </div>
      <div className="hbars" style={{ gridTemplateColumns: "140px 1fr 110px" }}>
        {rows.map(([k, v]) => (
          <div key={k} style={{ display: "contents" }}>
            <span>{k}</span>
            <span style={{ display: "flex", flexDirection: "column", gap: 3 }}>
              <span className="bar" style={{ height: 8 }}><span style={{ width: `${(v.wer_a / max) * 100}%`, background: "#b5c2c9" }} /></span>
              <span className="bar" style={{ height: 8 }}><span style={{ width: `${(v.wer_b / max) * 100}%` }} /></span>
            </span>
            <span className="num small" style={{ textAlign: "right" }}>{pct(v.wer_a)} → {pct(v.wer_b)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
