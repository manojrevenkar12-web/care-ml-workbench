import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Transcript } from "../components/Transcript";
import { Empty, ErrorNote, PageHead, pct, statusClass, when } from "../components/ui";
import { api } from "../lib/api";
import { LABEL_STYLE } from "../lib/spans";
import type { ExploreRow, Label, Project } from "../lib/types";

const STATUSES = ["unlabelled", "in progress", "needs review", "done"];
const PAGE = 25;

export function Explore({ project }: { project: Project }) {
  const [f, setF] = useState({ status: "", facility: "", label: "", q: "" });
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<ExploreRow | null>(null);
  const items = useQuery({
    queryKey: ["items", project.id, f, offset],
    queryFn: () => api.items(project.id, { ...f, limit: PAGE, offset }),
    placeholderData: (prev) => prev,
  });
  const facilities = useQuery({
    queryKey: ["facilities", project.id],
    queryFn: async () => [...new Set((await api.items(project.id, { limit: 1000 })).rows.map((r) => r.item.facility_id))].sort(),
    staleTime: 300_000,
  });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => { setOffset(0); setF({ ...f, [k]: e.target.value }); };
  const total = items.data?.total ?? 0;

  return (
    <>
      <PageHead title="Explore data" intro="Find transcripts by status, facility, label or text. Search runs on the de-identified text only." />
      <div className="row" style={{ marginBottom: 16 }}>
        <label className="field"><span>Search text</span><input type="search" value={f.q} onChange={set("q")} placeholder="e.g. Dekubitus" /></label>
        <label className="field"><span>Status</span>
          <select value={f.status} onChange={set("status")}><option value="">Any</option>{STATUSES.map((s) => <option key={s}>{s}</option>)}</select>
        </label>
        <label className="field"><span>Facility</span>
          <select value={f.facility} onChange={set("facility")}><option value="">Any</option>{facilities.data?.map((x) => <option key={x}>{x}</option>)}</select>
        </label>
        <label className="field"><span>Label</span>
          <select value={f.label} onChange={set("label")}><option value="">Any</option>{(Object.keys(LABEL_STYLE) as Label[]).map((l) => <option key={l} value={l}>{LABEL_STYLE[l].name}</option>)}</select>
        </label>
      </div>
      <ErrorNote error={items.error} />
      <div className="split wide-left">
        <div className="panel" style={{ overflowX: "auto" }}>
          {items.data && items.data.rows.length === 0 ? <Empty title="No transcripts match">Try removing a filter.</Empty> : (
            <table className="table">
              <thead><tr><th>Resident</th><th>Facility</th><th>Recorded</th><th>Status</th><th className="r" title="Word error rate of the speech model against the reviewed transcript">WER</th><th>Labels</th></tr></thead>
              <tbody>
                {items.data?.rows.map((r) => (
                  <tr key={r.item.id} className={`clickable${selected?.item.id === r.item.id ? " selected" : ""}`} tabIndex={0}
                    onClick={() => setSelected(r)} onKeyDown={(e) => e.key === "Enter" && setSelected(r)}>
                    <td className="mono">{r.item.subject}</td>
                    <td className="nowrap">{r.item.facility_id}</td>
                    <td className="nowrap">{when(r.item.recorded_at)}</td>
                    <td><span className={`status ${statusClass(r.status)}`}>{r.status}</span></td>
                    <td className="r">{pct(r.wer)}</td>
                    <td className="tags">{r.labels.map((l) => <span key={l} className="tag" style={{ background: LABEL_STYLE[l].bg, color: LABEL_STYLE[l].fg }}>{LABEL_STYLE[l].name}</span>)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="spread small muted" style={{ padding: "10px 12px", borderTop: "1px solid var(--line)" }}>
            <span className="num">{total ? `${offset + 1}–${Math.min(offset + PAGE, total)} of ${total}` : ""}</span>
            <span className="row">
              <button className="btn quiet" disabled={offset === 0} onClick={() => setOffset(offset - PAGE)}>Previous</button>
              <button className="btn quiet" disabled={offset + PAGE >= total} onClick={() => setOffset(offset + PAGE)}>Next</button>
            </span>
          </div>
        </div>
        {selected ? <Detail key={selected.item.id} row={selected} project={project} /> : (
          <div className="panel"><Empty title="Select a transcript">Its text, labels and model outputs appear here.</Empty></div>
        )}
      </div>
    </>
  );
}

function Detail({ row, project }: { row: ExploreRow; project: Project }) {
  const { item } = row;
  const [reason, setReason] = useState("");
  const reveal = useMutation({ mutationFn: () => api.reveal(item.id, reason) });
  const ref = row.reference;
  return (
    <section className="panel panel-pad stack" style={{ position: "sticky", top: 20 }} aria-label="Transcript detail">
      <div className="spread">
        <h2>Resident {item.subject}</h2>
        <span className={`status ${statusClass(row.status)}`}>{row.status}</span>
      </div>
      <div>
        <p className="small muted" style={{ marginBottom: 6 }}>{ref ? `Training version, by ${ref.annotator.replace("user:", "")}` : `Speech model output (${item.asr_model})`}</p>
        <Transcript compact text={ref?.transcript ?? item.asr_text} spans={ref?.entities ?? []} surrogates={ref ? [] : item.surrogates} />
      </div>
      {ref && (
        <details>
          <summary className="small">Speech model outputs</summary>
          <div className="stack" style={{ marginTop: 10, gap: 10 }}>
            {[[item.asr_model, item.asr_text], ...Object.entries(item.predictions)].map(([m, t]) => (
              <div key={m}><p className="small muted">{m}</p><Transcript compact text={t} spans={[]} /></div>
            ))}
          </div>
        </details>
      )}
      {project.permissions.phi_viewer && (
        <div className="stack" style={{ gap: 10, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
          <h3>View the original record</h3>
          {reveal.data ? (
            <div className="note phi">
              <OriginalRecord raw={reveal.data.original} />
              <p className="small" style={{ marginTop: 8 }}>Logged as audit event #{reveal.data.audit_seq}. Close this panel when you're done.</p>
            </div>
          ) : (
            <form className="stack" style={{ gap: 10 }} onSubmit={(e) => { e.preventDefault(); reveal.mutate(); }}>
              <p className="small muted">The original contains real identifiers. Every view is logged with your reason and visible to your tenant admin.</p>
              <label className="field"><span>Reason</span>
                <textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Checking a de-identification miss reported in review" />
              </label>
              <ErrorNote error={reveal.error} />
              <button className="btn" disabled={reason.trim().length < 15 || reveal.isPending}>View original</button>
            </form>
          )}
        </div>
      )}
    </section>
  );
}

interface Original {
  resident?: { first_name?: string; last_name?: string; date_of_birth?: string; insurance_number?: string; room?: string; phone?: string };
  facility_id?: string; recorded_at?: string; primary_model?: string; asr?: Record<string, string>;
}

/** The decrypted source record, laid out for a person rather than as JSON. */
function OriginalRecord({ raw }: { raw: string }) {
  let o: Original;
  try { o = JSON.parse(raw); } catch { return <p style={{ fontFamily: "var(--serif)", fontSize: 16, lineHeight: 1.6 }}>{raw}</p>; }
  const r = o.resident ?? {};
  const rows: [string, string | undefined][] = [
    ["Name", [r.first_name, r.last_name].filter(Boolean).join(" ")], ["Date of birth", r.date_of_birth],
    ["Insurance number", r.insurance_number], ["Room", r.room], ["Phone", r.phone], ["Recorded", o.recorded_at],
  ];
  const text = o.asr?.[o.primary_model ?? ""] ?? Object.values(o.asr ?? {})[0];
  return (
    <div className="stack" style={{ gap: 10 }}>
      <dl className="record">
        {rows.filter(([, v]) => v).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
      </dl>
      {text && <p style={{ fontFamily: "var(--serif)", fontSize: 16, lineHeight: 1.6 }}>{text}</p>}
    </div>
  );
}
