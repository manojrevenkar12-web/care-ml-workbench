import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Empty, ErrorNote, PageHead, when } from "../components/ui";
import { api } from "../lib/api";
import { LABEL_STYLE } from "../lib/spans";
import type { Label, Project } from "../lib/types";

export function Datasets({ project }: { project: Project }) {
  const qc = useQueryClient();
  const snaps = useQuery({ queryKey: ["snapshots", project.id], queryFn: () => api.snapshots(project.id) });
  const create = useMutation({
    mutationFn: () => api.createSnapshot(project.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["snapshots", project.id] }),
  });
  return (
    <>
      <PageHead
        title="Datasets"
        intro="A dataset is a frozen, versioned copy of the finished transcripts. Residents are split into train, dev and test by pseudonym, so no resident appears in two splits."
        actions={project.permissions.reviewer && <button className="btn primary" disabled={create.isPending} onClick={() => create.mutate()}>Create dataset version</button>}
      />
      <ErrorNote error={snaps.error ?? create.error} />
      {snaps.data?.length === 0 && <div className="panel"><Empty title="No dataset versions yet">{project.permissions.reviewer ? "Create the first version once some transcripts are finished." : "A reviewer creates dataset versions from finished transcripts."}</Empty></div>}
      <div className="stack">
        {snaps.data?.map((s) => {
          const total = s.counts.train + s.counts.dev + s.counts.test;
          const labels = Object.entries(s.label_counts).sort((a, b) => b[1] - a[1]);
          return (
            <section key={s.id} className="panel panel-pad stack" aria-label={`Version ${s.version}`}>
              <div className="spread">
                <div>
                  <h2>Version {s.version}</h2>
                  <p className="small muted">Created {when(s.created_at)} by {s.created_by.replace("user:", "")}</p>
                </div>
                <Link className="btn" to={`../training?snapshot=${s.id}`}>Train on this version</Link>
              </div>
              <div className="row" style={{ gap: 28 }}>
                {(["train", "dev", "test"] as const).map((k) => (
                  <div key={k}><div className="small muted">{k === "dev" ? "Dev" : k === "train" ? "Train" : "Test"}</div><div className="num" style={{ fontSize: 20, fontWeight: 600 }}>{s.counts[k]}</div></div>
                ))}
                <div style={{ flex: 1, minWidth: 200 }}>
                  <div className="bar" style={{ display: "flex", height: 10 }} aria-hidden>
                    <span style={{ width: `${(s.counts.train / Math.max(1, total)) * 100}%` }} />
                    <span style={{ width: `${(s.counts.dev / Math.max(1, total)) * 100}%`, background: "#6aa9a4" }} />
                    <span style={{ width: `${(s.counts.test / Math.max(1, total)) * 100}%`, background: "#b5d6d2" }} />
                  </div>
                </div>
              </div>
              <div>
                {labels.map(([l, n]) => {
                  const st = LABEL_STYLE[l as Label];
                  return <span key={l} className="tag" style={{ background: st?.bg, color: st?.fg }}>{st?.name ?? l} {n}</span>;
                })}
              </div>
              {Object.keys(s.excluded).length > 0 && (
                <p className="small muted">
                  Left out: {Object.entries(s.excluded).map(([k, n]) => `${n} ${k}`).join("; ")}.
                </p>
              )}
              <p className="small muted">Manifest SHA-256 <code>{s.manifest_sha256}</code></p>
            </section>
          );
        })}
      </div>
    </>
  );
}
