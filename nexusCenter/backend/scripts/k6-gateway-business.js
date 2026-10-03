import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const API_KEY = __ENV.API_KEY;
const RUN_ID = __ENV.RUN_ID || `loadtest-${Date.now()}`;

if (!API_KEY) {
  throw new Error('API_KEY is required');
}

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    checks: ['rate>0.99'],
  },
  tags: { test: 'gateway-business-load', run_id: RUN_ID },
};

function request(path, body, kind) {
  const requestId = `${RUN_ID}-${__VU}-${__ITER}`;
  const response = http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    headers: {
      Authorization: `Bearer ${API_KEY}`,
      'Content-Type': 'application/json',
      'X-Request-Id': requestId,
    },
    tags: { capability: kind },
    timeout: '30s',
    responseType: 'text',
  });
  const ok = check(response, {
    [`${kind} HTTP 2xx`]: (r) => r.status >= 200 && r.status < 300,
    [`${kind} non-empty body`]: (r) => String(r.body || '').length > 0,
  });
  return { response, ok, requestId };
}

export default function () {
  const selector = (__VU + __ITER) % 3;
  if (selector === 0) {
    request('/v1/chat/completions', {
      model: 'gpt-5.6-sol',
      messages: [{ role: 'user', content: 'load test' }],
      stream: false,
    }, 'chat');
  } else if (selector === 1) {
    request('/v1/responses', {
      model: 'gpt-5.6-sol',
      input: 'load test',
      stream: false,
    }, 'responses');
  } else {
    request('/v1/responses', {
      model: 'gpt-5.6-sol',
      input: 'load test',
      stream: true,
    }, 'responses_stream');
  }
  // Yield a small amount so the test models client pacing instead of a busy loop.
  sleep(0.05);
}

export function handleSummary(data) {
  const output = __ENV.SUMMARY_PATH;
  if (!output) return {};
  return { [output]: JSON.stringify({
    run_id: RUN_ID,
    vus: Number(__ENV.VUS || 0),
    duration: __ENV.DURATION || '',
    generated_at: new Date().toISOString(),
    metrics: data.metrics,
  }, null, 2) };
}
