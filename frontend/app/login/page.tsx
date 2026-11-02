"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { LoginResponse } from "@/lib/types";

/**
 * Sign-in with three server-decided outcomes:
 *  - AUTHENTICATED                → session cookie issued, go to the dashboard
 *  - MFA_REQUIRED                 → second factor challenge
 *  - MFA_ENROLMENT_REQUIRED       → privileged users must enrol TOTP before proceeding
 * The UI never decides whether MFA is needed; the server does.
 */
export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [workspaceSlug, setWorkspaceSlug] = useState("");
  const [code, setCode] = useState("");
  const [challenge, setChallenge] = useState<LoginResponse | null>(null);
  const [enrolment, setEnrolment] = useState<{ provisioningUri: string; secret: string } | null>(null);
  const [enrolmentToken, setEnrolmentToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const complete = (response: LoginResponse) => {
    if (response.status === "AUTHENTICATED") {
      router.replace("/");
      return;
    }
    if (response.status === "MFA_REQUIRED") {
      setChallenge(response);
      return;
    }
    if (response.status === "MFA_ENROLMENT_REQUIRED") {
      setChallenge(response);
      void startEnrolment(response.enrolmentToken ?? "");
    }
  };

  const startEnrolment = async (token: string) => {
    try {
      const result = await api.startMfaEnrolment(token);
      setEnrolmentToken(token);
      setEnrolment(result);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Unable to start MFA enrolment.");
    }
  };

  const submitCredentials = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      complete(await api.login(email.trim(), password, workspaceSlug.trim()));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Sign-in failed.");
    } finally {
      setBusy(false);
    }
  };

  const submitCode = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (enrolmentToken) {
        complete(await api.confirmMfaEnrolment(enrolmentToken, code.trim()));
      } else if (challenge?.challengeToken) {
        complete(await api.verifyMfa(challenge.challengeToken, code.trim()));
      }
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Verification failed.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ minHeight: "100vh", display: "grid", placeItems: "center", padding: 24 }}>
      <div className="card" style={{ width: "100%", maxWidth: 420, padding: 28 }}>
        <div className="row" style={{ gap: 10 }}>
          <span className="sidebar__brand-mark" aria-hidden="true">IS</span>
          <div>
            <h1 style={{ fontSize: 17 }}>Infeara Sales OS</h1>
            <p className="small muted">Revenue workspace</p>
          </div>
        </div>

        {error && (
          <div className="banner banner--danger" role="alert" style={{ marginTop: 18 }}>{error}</div>
        )}

        {!challenge && (
          <form onSubmit={submitCredentials} className="stack" style={{ marginTop: 22 }}>
            <div className="field">
              <label className="field__label" htmlFor="email">Work email</label>
              <input id="email" type="email" autoComplete="username" required value={email}
                onChange={(event) => setEmail(event.target.value)} />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="password">Password</label>
              <input id="password" type="password" autoComplete="current-password" required
                value={password} onChange={(event) => setPassword(event.target.value)} />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="workspace">Workspace slug</label>
              <input id="workspace" value={workspaceSlug} placeholder="optional — infeara"
                onChange={(event) => setWorkspaceSlug(event.target.value)} />
              <span className="field__hint">Only needed when your email exists in several workspaces.</span>
            </div>
            <button type="submit" className="btn btn--primary" disabled={busy} style={{ marginTop: 6 }}>
              {busy ? "Signing in…" : "Sign in"}
            </button>
            <p className="small muted">
              Accounts lock for 15 minutes after 5 failed attempts. Privileged roles require TOTP MFA.
            </p>
          </form>
        )}

        {challenge && (
          <form onSubmit={submitCode} className="stack" style={{ marginTop: 22 }}>
            <div className={`banner ${enrolment ? "banner--info" : ""}`}>
              {enrolment
                ? "Your role requires multi-factor authentication. Add this account to your authenticator app, then enter the 6-digit code."
                : "Enter the 6-digit code from your authenticator app."}
            </div>
            {enrolment && (
              <div className="field">
                <span className="field__label">Manual setup key</span>
                <code className="mono" style={{ wordBreak: "break-all" }}>{enrolment.secret}</code>
                <span className="field__hint">{enrolment.provisioningUri}</span>
              </div>
            )}
            <div className="field">
              <label className="field__label" htmlFor="code">Verification code</label>
              <input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} required
                value={code} onChange={(event) => setCode(event.target.value)} />
            </div>
            <button type="submit" className="btn btn--primary" disabled={busy}>
              {busy ? "Verifying…" : "Verify and continue"}
            </button>
            <button type="button" className="btn btn--ghost btn--sm"
              onClick={() => { setChallenge(null); setEnrolment(null); setCode(""); }}>
              Use a different account
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
