import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Empty, ErrorNote, fix, PageHead, when } from "../components/ui";
import { api } from "../lib/api";
import type { Project } from "../lib/types";

export function Training({ project }: { project: Project }) {
  const qc = useQueryClient();
  const [search] = useSearchParams();
  const snaps = useQuery({ queryKey: ["snapshots", project.id], queryFn: () => api.snapshots(project.id) });
  const jobs = useQuery({
    queryKey: ["jobs", project.id],
    queryFn: () => api.jobs(project.id),
    refetchInterval: (q) => (q.state.data?.some((j) => j.status === "RUNNING" || j.status === "QUEUED") ? 2000 : false),
  });
  const [snapshot, setSnapshot] = useState(search.get("snapshot") ?? "");
  const [params, setParams] = useState({ epochs: "", c: "1.0", buckets: "4096" });
  const chosen = snapshot || snaps.data?.[0]?.id || "";
  const start = useMutation({
    mutationFn: () => api.startJob(project.id, chosen, Object.fromEntries(Object.entries(params).filter(([, v]) => v !== ""))),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["jobs", project.id] });
      qc.invalidateQueries({ queryKey: ["models", project.id] });
    },
  });
  const cancel = useMutation({ mutationFn: api.cancelJob, onSuccess: () => qc.invalidateQueries({ queryKey: ["jobs", project.id] }) });
  const version = (id: string) => snaps.data?.find((s) => s.id === id)?.version;

  return (
    <>
      <PageHead title="Training" intro="Train the care-entity tagger on a dataset version. Runs execute as isolated Kubernetes jobs without network access; the result is registered as a new model version." />
      <div className="split">
        <section className="panel" style={{ overflowX: "auto" }} aria-label="Training runs">
          {jobs.data?.length === 0 ? <Empty title="No training runs yet">Start one from a dataset version.</Empty> : (
            <table className="table">
              <thead><tr><th>Run</th><th>Dataset</th><th>Status</th><th className="r">Dev F1</th><th className="r">Test F1</th><th>Started</th><th /></tr></thead>
              <tbody>
                {jobs.data?.map((j) => (
                  <tr key={j.id}>
                    <td><span className="mono">{j.id}</span><div className="small muted">{j.created_by.replace("user:", "")}</div></td>
                    <td>v{version(j.snapshot_id) ?? "?"}</td>
                    <td><span className={`status ${j.status}`}>{j.status.toLowerCase()}</span>{j.message && <div className="small muted" style={{ maxWidth: 240 }}>{j.message}</div>}</td>
                    <td className="r">{fix(j.metrics.dev_f1)}</td>
                    <td className="r">{fix(j.metrics.test_f1)}</td>
                    <td>{when(j.started_at ?? j.created_at)}</td>
                    <td>{j.status === "RUNNING" && project.permissions.reviewer && <button className="btn quiet danger" onClick={() => cancel.mutate(j.id)}>Cancel</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {project.permissions.reviewer ? (
          <form className="panel panel-pad stack" onSubmit={(e) => { e.preventDefault(); start.mutate(); }} aria-label="Start a training run">
            <h2>Start a run</h2>
            <label className="field"><span>Dataset version</span>
              <select value={chosen} onChange={(e) => setSnapshot(e.target.value)}>
                {snaps.data?.map((s) => <option key={s.id} value={s.id}>Version {s.version}, {s.counts.train} training examples</option>)}
              </select>
            </label>
            <div className="row">
              <label className="field" style={{ flex: 1 }}><span>Regularisation C</span><input inputMode="decimal" value={params.c} onChange={(e) => setParams({ ...params, c: e.target.value })} /></label>
              <label className="field" style={{ flex: 1 }}><span>Feature buckets</span><input inputMode="numeric" value={params.buckets} onChange={(e) => setParams({ ...params, buckets: e.target.value })} /></label>
              <label className="field" style={{ flex: 1 }}><span>Max iterations</span><input inputMode="numeric" placeholder="default" value={params.epochs} onChange={(e) => setParams({ ...params, epochs: e.target.value })} /></label>
            </div>
            <ErrorNote error={start.error ?? cancel.error} />
            <button className="btn primary" disabled={!chosen || start.isPending}>Start training</button>
            {!snaps.data?.length && <p className="small muted">Create a dataset version first.</p>}
          </form>
        ) : (
          <div className="panel"><Empty title="Reviewers start training">You can follow runs and compare the resulting models.</Empty></div>
        )}
      </div>
    </>
  );
}
