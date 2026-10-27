export type Evidence = { id: string; text: string; data: Record<string, unknown> };
export type Alternative = {
  element: string; description: string; covered: number; degraded: number; overlap: number; margin: number; reason: string;
};
export type Action = { id: string; kind: "ADVISORY" | "ROLLBACK_DEPLOYMENT"; title: string; detail: string; deploymentId: string | null };
export type Signature = {
  kind: string; lossPct: number; baselineLossPct: number; latencyRiseMs: number; jitterRiseMs: number;
  blackoutShare: number; tunnels: number;
};
export type Incident = {
  id: string; state: "OPEN" | "RESOLVED"; rootCause: string; rootCauseKind: string; description: string; headline: string;
  confidence: "HIGH" | "MEDIUM" | "LOW"; margin: number; degradedNow: number; coveredNow: number; p: number;
  peakDegraded: number; sitesAffected: number; sites: number[]; onsetAt: number; openedAt: number; lastSeenAt: number;
  resolvedAt: number | null; signature: Signature; evidence: Evidence[]; alternatives: Alternative[]; actions: Action[];
  history: { at: number; rootCause: string }[]; timeline: { at: number; degraded: number }[];
};
export type Grid = {
  windowEnd: number; regions: string[]; colors: string[];
  cells: { region: string; color: string; observed: number; degraded: number }[];
  hubs: { hub: string; color: string; observed: number; degraded: number }[];
};
export type ActionRecord = {
  id: string; incidentId: string; actionId: string; kind: string; title: string; deploymentId: string | null;
  state: string; requestedBy: string; justification: string; requestedAt: number; decidedBy: string | null;
  decidedAt: number | null; outcome: string | null;
};
export type Status = {
  engine: {
    windowsProcessed: number; gapWindows: number; lastWindowEnd: number | null; tunnelsKnown: number; observed: number;
    degraded: number; unexplained: number; openIncidents: number; meanProcessMs: number; maxProcessMs: number;
    changeGroups: number; devicesKnown: number; backgroundNoisePct: number;
  };
  kafka: Record<string, unknown>;
  edgefleet: Record<string, unknown>;
};

async function get<T>(path: string): Promise<T> {
  const r = await fetch(path);
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
  return r.json() as Promise<T>;
}

export const api = {
  status: () => get<Status>("/api/status"),
  grid: () => get<Grid>("/api/grid"),
  incidents: () => get<Incident[]>("/api/incidents?all=true"),
  actions: () => get<ActionRecord[]>("/api/actions"),
  approve: async (incident: string, action: string, approver: string): Promise<ActionRecord> => {
    const r = await fetch(`/api/incidents/${incident}/actions/${action}/approve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ approver }),
    });
    const body = await r.json();
    if (!r.ok) throw new Error(body.error ?? r.statusText);
    return body as ActionRecord;
  },
};
