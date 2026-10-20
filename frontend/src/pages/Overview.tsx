import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ErrorNote, fix, Metric, PageHead, pct } from "../components/ui";
import { api } from "../lib/api";
import { LABEL_STYLE } from "../lib/spans";
import type { Label, Project } from "../lib/types";

export function Overview({ project }: { project: Project }) {
  const stats = useQuery({ queryKey: ["stats", project.id], queryFn: () => api.stats(project.id) });
  const s = stats.data;
  const counts = Object.entries(s?.label_counts ?? {}) as [Label, number][];
  const max = Math.max(1, ...counts.map(([, n]) => n));
  const can = project.permissions;

  return (
    <>
      <PageHead
        title={project.name}
        intro={project.description}
        actions={<>
          {can.reviewer && s && s.needs_review > 0 && <Link className="btn" to="../review">Review {s.needs_review} disagreements</Link>}
          {can.annotator && <Link className="btn primary" to="../annotate">Start annotating</Link>}
        </>}
      />
      <ErrorNote error={stats.error} />
      {s && (
        <div className="stack" style={{ gap: 24 }}>
          <div className="metrics">
            <Metric label="Transcripts" value={s.items.toLocaleString("en")} hint={`${pct(s.items ? s.done / s.items : 0, 0)} ready for training`} />
            <Metric label="Ready for training" value={s.done.toLocaleString("en")} />
            <Metric label="Waiting for annotation" value={s.tasks_open.toLocaleString("en")} hint={`${s.tasks_leased} being worked on now`} />
            <Metric label="Disagreements to review" value={s.needs_review.toLocaleString("en")} />
            <Metric label="Median time per transcript" value={s.median_seconds_per_item === null ? "—" : `${Math.round(s.median_seconds_per_item)} s`} />
          </div>

          <div className="grid-2">
            <section className="panel panel-pad stack">
              <div>
                <h2>Annotator agreement</h2>
                <p className="small muted" style={{ marginTop: 4 }}>
                  Measured on the {s.agreement.items} transcripts that two people annotated independently. Low agreement on a label usually means the guideline is unclear.
                </p>
              </div>
              <div className="metrics">
                <Metric label="Entity F1, exact" value={fix(s.agreement.span_f1, 2)} />
                <Metric label="Entity F1, overlap" value={fix(s.agreement.overlap_f1, 2)} />
                <Metric label="Cohen's κ per token" value={fix(s.agreement.token_kappa, 2)} />
                <Metric label="Text difference" value={pct(s.agreement.transcript_wer_between)} hint="word error rate between the two corrections" />
              </div>
            </section>

            <section className="panel panel-pad stack">
              <div>
                <h2>Labels in reviewed data</h2>
                <p className="small muted" style={{ marginTop: 4 }}>How often each kind of care information appears in finished transcripts.</p>
              </div>
              {counts.length === 0 ? <p className="muted">No reviewed transcripts yet.</p> : (
                <div className="hbars">
                  {counts.sort((a, b) => b[1] - a[1]).map(([l, n]) => (
                    <div key={l} style={{ display: "contents" }}>
                      <span>{LABEL_STYLE[l]?.name ?? l}</span>
                      <span className="bar"><span style={{ width: `${(n / max) * 100}%`, background: LABEL_STYLE[l]?.fg }} /></span>
                      <span className="num" style={{ textAlign: "right" }}>{n}</span>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </div>
        </div>
      )}
    </>
  );
}
