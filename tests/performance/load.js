// k6 load test: mixed read-heavy workload against a running CloudScale stack.
//   k6 run -e VUS=50 -e DURATION=30s -e OUT=result.json tests/performance/load.js
// Workload per iteration: LOGIN_RATE (default 2%) logins (BCrypt); the rest split 60/20/10/5/5 between workspace read,
// project list, project read, project create and job submit. All VUs share one user, so the per-user API rate limit must be raised for benchmarks.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const JSON_HDR = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: { load: { executor: 'constant-vus', vus: Number(__ENV.VUS || 10), duration: __ENV.DURATION || '30s' } },
  thresholds: { http_req_failed: ['rate<0.01'] },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const email = `k6-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const password = 'correct-horse-battery';
  const reg = http.post(`${BASE}/auth/register`, JSON.stringify({ email, password }), { headers: JSON_HDR });
  if (reg.status !== 201) throw new Error(`register failed: ${reg.status} ${reg.body}`);
  const auth = { headers: { ...JSON_HDR, Authorization: `Bearer ${reg.json('accessToken')}` } };
  const ws = http.post(`${BASE}/workspaces`, JSON.stringify({ name: 'load test' }), auth).json('id');
  const projects = [];
  for (let i = 0; i < 20; i++) {
    projects.push(http.post(`${BASE}/workspaces/${ws}/projects`, JSON.stringify({ name: `project ${i}` }), auth).json('id'));
  }
  return { email, password, ws, projects, auth };
}

export default function (d) {
  const loginRate = Number(__ENV.LOGIN_RATE === undefined ? 0.02 : __ENV.LOGIN_RATE);
  let r = Math.random();
  let res;
  if (r >= 1 - loginRate) {
    res = http.post(`${BASE}/auth/login`, JSON.stringify({ email: d.email, password: d.password }), { headers: JSON_HDR });
    check(res, { 'status is 2xx': (x) => x.status >= 200 && x.status < 300 });
    return;
  }
  r = r / (1 - loginRate);
  if (r < 0.60) res = http.get(`${BASE}/workspaces/${d.ws}`, d.auth);
  else if (r < 0.80) res = http.get(`${BASE}/workspaces/${d.ws}/projects?size=20`, d.auth);
  else if (r < 0.90) res = http.get(`${BASE}/workspaces/${d.ws}/projects/${d.projects[Math.floor(Math.random() * d.projects.length)]}`, d.auth);
  else if (r < 0.95) res = http.post(`${BASE}/workspaces/${d.ws}/projects`, JSON.stringify({ name: 'created under load' }), d.auth);
  else res = http.post(`${BASE}/workspaces/${d.ws}/jobs`, JSON.stringify({ type: 'CSV_ANALYTICS', payload: { csv: 'a,b\n1,2\n3,4\n', columns: ['a', 'b'] } }), d.auth);
  check(res, { 'status is 2xx': (x) => x.status >= 200 && x.status < 300 });
}

export function handleSummary(data) {
  const m = data.metrics;
  const out = {
    vus: Number(__ENV.VUS || 10),
    duration: __ENV.DURATION || '30s',
    requests: m.http_reqs.values.count,
    requestsPerSecond: Number(m.http_reqs.values.rate.toFixed(1)),
    latencyMs: {
      avg: Number(m.http_req_duration.values.avg.toFixed(2)),
      p50: Number(m.http_req_duration.values['p(50)'].toFixed(2)),
      p95: Number(m.http_req_duration.values['p(95)'].toFixed(2)),
      p99: Number(m.http_req_duration.values['p(99)'].toFixed(2)),
      max: Number(m.http_req_duration.values.max.toFixed(2)),
    },
    errorRate: Number((m.http_req_failed.values.rate * 100).toFixed(3)),
  };
  return { [__ENV.OUT || 'result.json']: JSON.stringify(out, null, 2), stdout: JSON.stringify(out) + '\n' };
}
