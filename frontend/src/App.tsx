import { useQuery } from "@tanstack/react-query";
import { Navigate, NavLink, Route, Routes, useNavigate, useParams } from "react-router-dom";
import { ErrorNote } from "./components/ui";
import { api } from "./lib/api";
import { getToken, setToken } from "./lib/auth";
import { useMe, useProject } from "./lib/session";
import { Annotate } from "./pages/Annotate";
import { Audit } from "./pages/Audit";
import { Datasets } from "./pages/Datasets";
import { Evaluate } from "./pages/Evaluate";
import { Explore } from "./pages/Explore";
import { Callback, Login } from "./pages/Login";
import { Overview } from "./pages/Overview";
import { Playground } from "./pages/Playground";
import { Training } from "./pages/Training";

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/callback" element={<Callback />} />
      <Route path="/p/:project/*" element={<RequireAuth><Shell /></RequireAuth>} />
      <Route path="*" element={<RequireAuth><Home /></RequireAuth>} />
    </Routes>
  );
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  return getToken() ? <>{children}</> : <Navigate to="/login" replace />;
}

function Home() {
  const me = useMe();
  if (me.isPending) return null;
  if (me.error) return <main><ErrorNote error={me.error} /></main>;
  const first = me.data.projects[0];
  if (!first) {
    return (
      <div className="login"><div className="panel">
        <h1>No projects yet</h1>
        <p className="muted" style={{ marginTop: 8 }}>You're signed in as {me.data.user}, but nobody has given you access to a project. Ask your team admin to add you.</p>
        <button className="btn" style={{ marginTop: 18 }} onClick={() => { setToken(null); location.assign("/login"); }}>Sign out</button>
      </div></div>
    );
  }
  return <Navigate to={`/p/${first.id}/${first.permissions.annotator ? "annotate" : "overview"}`} replace />;
}

function Shell() {
  const me = useMe();
  const project = useProject();
  const { project: pid } = useParams();
  const nav = useNavigate();
  const stats = useQuery({ queryKey: ["stats", pid], queryFn: () => api.stats(pid!), enabled: !!project, refetchInterval: 30_000 });

  if (me.isPending) return null;
  if (me.error) return <main><ErrorNote error={me.error} /></main>;
  if (!project) return <Navigate to="/" replace />;
  const can = project.permissions;
  const link = (to: string, label: string, count?: number) => (
    <NavLink to={`/p/${project.id}/${to}`} className={({ isActive }) => (isActive ? "active" : "")}>
      <span>{label}</span>{count ? <span className="count num">{count}</span> : null}
    </NavLink>
  );

  return (
    <div className="shell">
      <aside className="rail">
        <div className="brand"><span className="brand-mark" aria-hidden>
          <svg width="14" height="14" viewBox="0 0 14 14"><path d="M2 7h3l1.5-4 2 8L10 7h2" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </span>Care ML Workbench</div>
        <label className="field">
          <span>Project</span>
          <select className="project-select" value={project.id} onChange={(e) => nav(`/p/${e.target.value}/overview`)}>
            {me.data.projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </label>
        <nav className="nav" aria-label="Workbench">
          {link("overview", "Overview")}
          <div className="nav-group">Labelling</div>
          {can.annotator && link("annotate", "Annotate", stats.data?.tasks_open)}
          {can.reviewer && link("review", "Review", stats.data?.needs_review)}
          {link("explore", "Explore data")}
          <div className="nav-group">Models</div>
          {link("datasets", "Datasets")}
          {link("training", "Training")}
          {link("evaluate", "Evaluation")}
          {link("playground", "Playground")}
          {can.admin && <><div className="nav-group">Governance</div>{link("audit", "Audit log")}</>}
        </nav>
        <div className="rail-foot">
          <span className="region small">Data stays in the EU</span>
          <span>Signed in as <b>{me.data.user.replace("user:", "")}</b></span>
          <button className="btn quiet" onClick={() => { setToken(null); location.assign("/login"); }}>Sign out</button>
        </div>
      </aside>
      <main>
        <Routes>
          <Route path="overview" element={<Overview project={project} />} />
          <Route path="annotate" element={<Annotate project={project} kind="ANNOTATE" key="a" />} />
          <Route path="review" element={<Annotate project={project} kind="REVIEW" key="r" />} />
          <Route path="explore" element={<Explore project={project} />} />
          <Route path="datasets" element={<Datasets project={project} />} />
          <Route path="training" element={<Training project={project} />} />
          <Route path="evaluate" element={<Evaluate project={project} />} />
          <Route path="playground" element={<Playground project={project} />} />
          <Route path="audit" element={<Audit project={project} />} />
          <Route path="*" element={<Navigate to="overview" replace />} />
        </Routes>
      </main>
    </div>
  );
}
