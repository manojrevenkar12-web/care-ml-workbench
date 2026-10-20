import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ErrorNote } from "../components/ui";
import { devLogin, oidc, oidcCallback, oidcLogin } from "../lib/auth";

// Demo identities seeded by the API in development (see DemoSeed.kt).
const DEMO_USERS = [
  { id: "anna", role: "Annotator, Nord" },
  { id: "ben", role: "Annotator, Nord" },
  { id: "rita", role: "Reviewer, Nord" },
  { id: "olivia", role: "Admin, Pflege Nord" },
  { id: "dora", role: "Member with PHI access, Nord" },
  { id: "sam", role: "Admin, Pflege Süd" },
];

export function Login() {
  const nav = useNavigate();
  const [error, setError] = useState<unknown>(null);
  const go = async (user: string) => {
    try { await devLogin(user); nav("/", { replace: true }); } catch (e) { setError(e); }
  };
  return (
    <div className="login">
      <div className="panel">
        <h1>Sign in to Care ML Workbench</h1>
        {oidc.issuer ? (
          <>
            <p className="muted" style={{ marginTop: 8 }}>Use your company account. Access to each project is managed by your team admin.</p>
            <button className="btn primary" style={{ marginTop: 20 }} onClick={() => oidcLogin().catch(setError)}>Continue with single sign-on</button>
          </>
        ) : (
          <>
            <p className="muted" style={{ marginTop: 8 }}>Development server: choose a demo person. All records are synthetic.</p>
            <div className="users">
              {DEMO_USERS.map((u) => (
                <button key={u.id} className="user-btn" onClick={() => go(u.id)}>
                  <b>{u.id}</b><span className="muted">{u.role}</span>
                </button>
              ))}
            </div>
          </>
        )}
        {error ? <div style={{ marginTop: 16 }}><ErrorNote error={error} /></div> : null}
      </div>
    </div>
  );
}

export function Callback() {
  const nav = useNavigate();
  const [error, setError] = useState<unknown>(null);
  useEffect(() => { oidcCallback(location.search).then(() => nav("/", { replace: true }), setError); }, [nav]);
  return <div className="login"><div className="panel">{error ? <ErrorNote error={error} /> : <p className="muted">Signing you in…</p>}</div></div>;
}
