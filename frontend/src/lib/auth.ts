// Sign-in. Production: OpenID Connect authorization-code flow with PKCE against the company IdP
// (VITE_OIDC_ISSUER / VITE_OIDC_CLIENT_ID at build time). Development: the API's /dev/token
// endpoint, which the API only exposes outside production.
// The access token is kept in sessionStorage (cleared when the tab closes), never localStorage.

const KEY = "cmw.token";
const VERIFIER = "cmw.pkce";
let memoryToken: string | null = null;

const store = {
  get: (k: string) => { try { return sessionStorage.getItem(k); } catch { return null; } },
  set: (k: string, v: string) => { try { sessionStorage.setItem(k, v); } catch { /* private mode: memory only */ } },
  del: (k: string) => { try { sessionStorage.removeItem(k); } catch { /* ignore */ } },
};

export const oidc = {
  issuer: import.meta.env.VITE_OIDC_ISSUER as string | undefined,
  clientId: import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined,
};

export function getToken(): string | null {
  return memoryToken ?? store.get(KEY);
}

export function setToken(t: string | null) {
  memoryToken = t;
  if (t) store.set(KEY, t); else store.del(KEY);
}

export async function devLogin(user: string): Promise<void> {
  const r = await fetch("/dev/token", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ user }) });
  if (!r.ok) throw new Error("development sign-in is not enabled on this server");
  setToken((await r.json()).token);
}

function b64url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function discovery() {
  const r = await fetch(`${oidc.issuer}/.well-known/openid-configuration`);
  return (await r.json()) as { authorization_endpoint: string; token_endpoint: string };
}

export async function oidcLogin() {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = b64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier))));
  const state = b64url(crypto.getRandomValues(new Uint8Array(16)));
  store.set(VERIFIER, JSON.stringify({ verifier, state }));
  const d = await discovery();
  const p = new URLSearchParams({
    response_type: "code", client_id: oidc.clientId!, redirect_uri: `${location.origin}/callback`,
    scope: "openid profile", code_challenge: challenge, code_challenge_method: "S256", state,
  });
  location.assign(`${d.authorization_endpoint}?${p}`);
}

export async function oidcCallback(search: string) {
  const q = new URLSearchParams(search);
  const saved = JSON.parse(store.get(VERIFIER) ?? "{}");
  if (!q.get("code") || q.get("state") !== saved.state) throw new Error("sign-in failed: state mismatch");
  const d = await discovery();
  const r = await fetch(d.token_endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code: q.get("code")!, redirect_uri: `${location.origin}/callback`, client_id: oidc.clientId!, code_verifier: saved.verifier }),
  });
  if (!r.ok) throw new Error("sign-in failed: token exchange");
  store.del(VERIFIER);
  setToken((await r.json()).access_token);
}
