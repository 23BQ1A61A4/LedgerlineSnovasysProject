import { useEffect, useRef, useState } from "react";
import { api, rupees } from "./api.js";

// Live updates: runs fn now, then every `ms`, only while the tab is visible.
function usePoll(fn, ms = 3000) {
  const ref = useRef(fn); ref.current = fn;
  useEffect(() => {
    const tick = () => { if (!document.hidden) ref.current(); };
    tick(); const id = setInterval(tick, ms);
    document.addEventListener("visibilitychange", tick);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", tick); };
  }, [ms]);
}
const Live = ({ at }) => <span className="live">● live{at && " · updated " + at.toLocaleTimeString()}</span>;

function Login({ onLogin }) {
  const [u, setU] = useState(""), [p, setP] = useState(""), [err, setErr] = useState(""), [busy, setBusy] = useState(false);
  const go = async () => { setBusy(true); setErr("");
    try { const r = await api("/api/auth/login", { method: "POST", body: { username: u, password: p } }); sessionStorage.setItem("token", r.token); onLogin(r); }
    catch (e) { setErr(e.message); } finally { setBusy(false); } };
  return (<div className="card narrow"><h2>Sign in</h2>
    <label>Username<input value={u} onChange={(e) => setU(e.target.value)} /></label>
    <label>Password<input type="password" value={p} onChange={(e) => setP(e.target.value)} onKeyDown={(e) => e.key === "Enter" && go()} /></label>
    {err && <p className="err">{err}</p>}<button disabled={busy} onClick={go}>Sign in</button></div>);
}

function MoveForm({ role, accounts, onDone }) {
  const [kind, setKind] = useState(role === "ADMIN" ? "deposit" : "transfer");
  const [from, setFrom] = useState(""), [to, setTo] = useState(""), [amt, setAmt] = useState("");
  const [key, setKey] = useState(() => crypto.randomUUID()), [msg, setMsg] = useState(null), [busy, setBusy] = useState(false);
  const edit = (set) => (e) => { set(e.target.value); setKey(crypto.randomUUID()); }; // changed request => new key
  const submit = async () => { setBusy(true); setMsg(null);
    const amountMinor = Math.round(parseFloat(amt) * 100);
    const body = kind === "transfer" ? { fromAccountId: from, toAccountId: to.trim(), amountMinor } : { accountId: from, amountMinor };
    try { const r = await api("/api/transactions/" + kind, { method: "POST", body, idemKey: key });
      setMsg({ ok: true, text: r.replayed ? "Already processed - nothing charged twice." : "Done." }); setKey(crypto.randomUUID()); setAmt(""); onDone(); }
    catch (e) { setMsg({ ok: false, text: e.message }); } finally { setBusy(false); } };
  const kinds = role === "ADMIN" ? ["deposit"] : ["transfer", "withdraw", "deposit"];
  return (<div className="card"><h3>Move money</h3>
    <div className="row">{kinds.map((k) => <button key={k} className={k === kind ? "tab on" : "tab"} onClick={() => { setKind(k); setKey(crypto.randomUUID()); }}>{k}</button>)}</div>
    <label>{kind === "deposit" ? "Into account" : "From account"}
      <select value={from} onChange={edit(setFrom)}><option value="">Choose...</option>{accounts.map((a) => <option key={a.id} value={a.id}>{a.name} ({rupees(a.balanceMinor)})</option>)}</select></label>
    {kind === "transfer" && <label>To account ID<input value={to} onChange={edit(setTo)} placeholder="Paste the receiver's account ID" /></label>}
    <label>Amount in rupees<input type="number" min="0.01" step="0.01" value={amt} onChange={edit(setAmt)} /></label>
    {msg && <p className={msg.ok ? "ok" : "err"}>{msg.text}</p>}
    <button disabled={busy || !from || !amt} onClick={submit}>{busy ? "Working..." : "Confirm " + kind}</button></div>);
}

function Accounts({ role }) {
  const [accs, setAccs] = useState([]), [sel, setSel] = useState(null), [st, setSt] = useState([]), [err, setErr] = useState("");
  const [updated, setUpdated] = useState(null);
  const load = () => api("/api/accounts").then((d) => { setAccs(d); setErr(""); setUpdated(new Date()); }).catch((e) => setErr(e.message));
  const loadStatement = (id) => api("/api/accounts/" + id + "/statement").then(setSt).catch((e) => setErr(e.message));
  usePoll(() => { load(); if (sel) loadStatement(sel.id); }, 3000);
  const open = (a) => { setSel(a); loadStatement(a.id); };
  return (<div className="grid"><div>
    <div className="card"><h3>Accounts <Live at={updated} /></h3>{err && <p className="err">{err}</p>}
      {accs.length === 0 && <p className="muted">No accounts yet.</p>}
      {accs.map((a) => <div key={a.id} className={"acct" + (sel?.id === a.id ? " sel" : "")} onClick={() => open(a)}>
        <div><b>{a.name}</b><div className="muted small">{a.id}</div></div><span className="num">{rupees(a.balanceMinor)}</span></div>)}</div>
    {sel && <div className="card"><h3>Statement: {sel.name}</h3><table><thead><tr><th>Type</th><th className="r">Amount</th><th>When</th></tr></thead>
      <tbody>{st.map((e) => <tr key={e.entry_id}><td>{e.type}</td><td className={"r num " + (e.amount_minor < 0 ? "neg" : "pos")}>{rupees(e.amount_minor)}</td><td className="small">{new Date(e.created_at).toLocaleString()}</td></tr>)}</tbody></table></div>}
    </div>{role !== "AUDITOR" && <MoveForm role={role} accounts={accs} onDone={() => { load(); sel && loadStatement(sel.id); }} />}</div>);
}

function Admin() {
  const [u, setU] = useState({ username: "", password: "", role: "ACCOUNT_HOLDER" }), [a, setA] = useState({ ownerId: "", name: "" }), [msg, setMsg] = useState(null);
  const call = async (path, body) => { try { const r = await api(path, { method: "POST", body }); setMsg({ ok: true, text: "Created. ID: " + r.id }); } catch (e) { setMsg({ ok: false, text: e.message }); } };
  return (<div className="grid"><div className="card"><h3>Create user</h3>
    <label>Username<input value={u.username} onChange={(e) => setU({ ...u, username: e.target.value })} /></label>
    <label>Password (10+ characters)<input type="password" value={u.password} onChange={(e) => setU({ ...u, password: e.target.value })} /></label>
    <label>Role<select value={u.role} onChange={(e) => setU({ ...u, role: e.target.value })}><option>ACCOUNT_HOLDER</option><option>AUDITOR</option><option>ADMIN</option></select></label>
    <button onClick={() => call("/api/admin/users", u)}>Create user</button></div>
    <div className="card"><h3>Open account</h3>
    <label>Owner user ID<input value={a.ownerId} onChange={(e) => setA({ ...a, ownerId: e.target.value.trim() })} /></label>
    <label>Account name<input value={a.name} onChange={(e) => setA({ ...a, name: e.target.value })} /></label>
    <button onClick={() => call("/api/admin/accounts", a)}>Open account</button>{msg && <p className={msg.ok ? "ok" : "err"}>{msg.text}</p>}</div></div>);
}

function Auditor() {
  const [rows, setRows] = useState([]), [res, setRes] = useState(null), [err, setErr] = useState("");
  const [updated, setUpdated] = useState(null);
  const load = () => api("/api/audit?limit=100").then((d) => { setRows(d); setErr(""); setUpdated(new Date()); }).catch((e) => setErr(e.message));
  usePoll(load, 2000);
  const check = (p) => api("/api/audit/" + p).then(setRes).catch((e) => setErr(e.message));
  return (<div className="card"><h3>Audit trail <Live at={updated} /></h3><div className="row"><button onClick={() => check("verify")}>Verify hash chain</button><button onClick={() => check("reconcile")}>Reconcile ledger</button><button className="ghost" onClick={load}>Refresh</button></div>
    {res && <pre className={res.ok ? "ok" : "err"}>{JSON.stringify(res, null, 2)}</pre>}{err && <p className="err">{err}</p>}
    <div className="scroll"><table><thead><tr><th>#</th><th>Time</th><th>Actor</th><th>Action</th><th>Outcome</th><th>Detail</th></tr></thead>
      <tbody>{rows.map((r) => <tr key={r.id}><td>{r.id}</td><td className="small">{new Date(r.at).toLocaleString()}</td><td>{r.actorName}</td><td>{r.action}</td><td className={r.outcome === "SUCCESS" ? "pos" : "neg"}>{r.outcome}</td><td className="small">{r.detail}</td></tr>)}</tbody></table></div></div>);
}

export default function App() {
  const [me, setMe] = useState(null), [view, setView] = useState("accounts");
  const logout = () => { sessionStorage.removeItem("token"); setMe(null); };
  if (!me) return <main><h1>LedgerLine</h1><Login onLogin={(r) => { setMe(r); setView(r.role === "ADMIN" ? "admin" : r.role === "AUDITOR" ? "audit" : "accounts"); }} /></main>;
  return (<main><header><h1>LedgerLine</h1><nav>
    <button className="tab" onClick={() => setView("accounts")}>Accounts</button>
    {me.role === "ADMIN" && <button className="tab" onClick={() => setView("admin")}>Admin</button>}
    {me.role === "AUDITOR" && <button className="tab" onClick={() => setView("audit")}>Audit</button>}
    <span className="muted small">{me.username} ({me.role})</span><button className="ghost" onClick={logout}>Sign out</button></nav></header>
    <p className="muted small">Screens are hidden by role for convenience only. The server enforces every permission.</p>
    {view === "accounts" && <Accounts role={me.role} />}{view === "admin" && me.role === "ADMIN" && <Admin />}{view === "audit" && me.role === "AUDITOR" && <Auditor />}</main>);
}
