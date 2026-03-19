import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 20,
  duration: '1m',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:9090';

export default function () {
  // 8.2 – hit a few hot paths
  const searchRes = http.get(`${BASE_URL}/api/v1/aggregates/search?q=test`);
  check(searchRes, {
    'search status is 200 or 404': r => r.status === 200 || r.status === 404,
  });

  const recentRes = http.get(`${BASE_URL}/api/v1/events/recent?limit=20`);
  check(recentRes, {
    'recent events status is 200': r => r.status === 200,
  });

  const anomaliesRes = http.get(`${BASE_URL}/api/v1/anomalies/recent?limit=50`);
  check(anomaliesRes, {
    'anomalies status is 200': r => r.status === 200,
  });

  sleep(1);
}

