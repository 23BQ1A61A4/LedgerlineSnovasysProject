const API = import.meta.env.VITE_API_URL ?? "http://localhost:8080";
export async function api(path, { method = "GET", body, idemKey } = {}) {
  const t = sessionStorage.getItem("token");
  const res = await fetch(API + path, { method, body: body ? JSON.stringify(body) : undefined,
    headers: { "Content-Type": "application/json", ...(t && { Authorization: "Bearer " + t }), ...(idemKey && { "Idempotency-Key": idemKey }) } });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && t) { sessionStorage.removeItem("token"); window.location.reload(); }
  if (!res.ok) throw Object.assign(new Error(data.message || "Request failed (" + res.status + ")"), { status: res.status });
  return data;
}
export const rupees = (m) => "₹ " + (Number(m) / 100).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
