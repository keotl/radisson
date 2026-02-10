package radisson.actors.http.api.routes

object DashboardHtml {

  def render(tracingEnabled: Boolean): String = {
    val tracingSection = if (tracingEnabled) {
      """
      |    <div id="tracing-section">
      |      <h2>Recent Requests</h2>
      |      <table id="requests-table">
      |        <thead>
      |          <tr>
      |            <th>Request ID</th>
      |            <th>Backend</th>
      |            <th>Model</th>
      |            <th>Type</th>
      |            <th>Status</th>
      |            <th>Duration (ms)</th>
      |            <th>Tokens</th>
      |            <th>Time</th>
      |          </tr>
      |        </thead>
      |        <tbody></tbody>
      |      </table>
      |    </div>
      """.stripMargin
    } else {
      ""
    }

    val tracingJs = if (tracingEnabled) {
      """
      |    async function fetchRequests() {
      |      try {
      |        const resp = await fetch('/admin/requests');
      |        const data = await resp.json();
      |        const tbody = document.querySelector('#requests-table tbody');
      |        tbody.innerHTML = '';
      |        data.traces.forEach(t => {
      |          const tokens = [t.prompt_tokens, t.completion_tokens, t.total_tokens]
      |            .filter(x => x != null).join('/') || '-';
      |          const time = new Date(t.started_at).toLocaleTimeString();
      |          const statusClass = t.status === 'success' ? 'status-running' : 'status-error';
      |          tbody.innerHTML += `<tr>
      |            <td title="${t.request_id}">${t.request_id.substring(0, 8)}...</td>
      |            <td>${t.backend_id}</td>
      |            <td>${t.model}</td>
      |            <td>${t.request_type}</td>
      |            <td class="${statusClass}">${t.status}</td>
      |            <td>${t.duration_ms}</td>
      |            <td>${tokens}</td>
      |            <td>${time}</td>
      |          </tr>`;
      |        });
      |        document.getElementById('total-captured').textContent = data.total_captured;
      |      } catch (e) {
      |        console.error('Failed to fetch requests:', e);
      |      }
      |    }
      """.stripMargin
    } else {
      ""
    }

    val tracingRefresh = if (tracingEnabled) "fetchRequests();" else ""
    val totalCapturedLine = if (tracingEnabled) {
      """<p>Total captured: <span id="total-captured">0</span></p>"""
    } else {
      ""
    }

    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <title>radisson</title>
       |  <style>
       |    body { font-family: monospace; background: #1a1a2e; color: #e0e0e0; margin: 20px; }
       |    h1 { color: #e94560; }
       |    h2 { color: #0f3460; background: #e94560; padding: 8px 12px; display: inline-block; }
       |    table { border-collapse: collapse; width: 100%; margin-bottom: 20px; }
       |    th, td { border: 1px solid #333; padding: 8px; text-align: left; }
       |    th { background: #16213e; }
       |    tr:nth-child(even) { background: #1a1a2e; }
       |    tr:nth-child(odd) { background: #16213e; }
       |    .status-running { color: #4ecca3; font-weight: bold; }
       |    .status-starting { color: #f0c040; font-weight: bold; }
       |    .status-draining { color: #e94560; font-weight: bold; }
       |    .status-stopped { color: #666; }
       |    .status-error { color: #e94560; font-weight: bold; }
       |    .memory-bar { background: #16213e; border: 1px solid #333; height: 20px; width: 300px; display: inline-block; }
       |    .memory-fill { background: #4ecca3; height: 100%; }
       |    button { background: #0f3460; color: #e0e0e0; border: 1px solid #4ecca3; padding: 4px 12px; cursor: pointer; font-family: monospace; }
       |    button:hover { background: #4ecca3; color: #1a1a2e; }
       |    button.release { border-color: #e94560; }
       |    button.release:hover { background: #e94560; }
       |    #last-updated { color: #666; font-size: 0.9em; }
       |  </style>
       |</head>
       |<body>
       |  <h1>radisson dashboard</h1>
       |  <p id="last-updated">Last updated: never</p>
       |
       |  <h2>Memory</h2>
       |  <p>
       |    <span id="memory-used">0</span> / <span id="memory-total">0</span> MB
       |    (<span id="memory-pct">0</span>%)
       |  </p>
       |  <div class="memory-bar"><div class="memory-fill" id="memory-fill" style="width:0%"></div></div>
       |
       |  <h2>Backends</h2>
       |  <table id="backends-table">
       |    <thead>
       |      <tr>
       |        <th>ID</th>
       |        <th>Type</th>
       |        <th>State</th>
       |        <th>Port</th>
       |        <th>Memory (MB)</th>
       |        <th>Last Access</th>
       |        <th>Held</th>
       |        <th>Actions</th>
       |      </tr>
       |    </thead>
       |    <tbody></tbody>
       |  </table>
       |
       |  $tracingSection
       |  $totalCapturedLine
       |
       |  <script>
       |    async function holdBackend(id) {
       |      await fetch('/admin/backend/' + id + '/acquire', {
       |        method: 'POST',
       |        headers: {'Content-Type': 'application/json'},
       |        body: JSON.stringify({ttl_seconds: 300})
       |      });
       |      refresh();
       |    }
       |
       |    async function releaseBackend(id) {
       |      await fetch('/admin/backend/' + id + '/release', {
       |        method: 'POST',
       |        headers: {'Content-Type': 'application/json'},
       |        body: '{}'
       |      });
       |      refresh();
       |    }
       |
       |    async function fetchStatus() {
       |      try {
       |        const resp = await fetch('/admin/status');
       |        const data = await resp.json();
       |
       |        const usedMB = Math.round(data.used_memory_bytes / 1048576);
       |        const totalMB = Math.round(data.total_memory_bytes / 1048576);
       |        const pct = totalMB > 0 ? Math.round(usedMB / totalMB * 100) : 0;
       |        document.getElementById('memory-used').textContent = usedMB;
       |        document.getElementById('memory-total').textContent = totalMB;
       |        document.getElementById('memory-pct').textContent = pct;
       |        document.getElementById('memory-fill').style.width = pct + '%';
       |
       |        const tbody = document.querySelector('#backends-table tbody');
       |        tbody.innerHTML = '';
       |        data.backends.forEach(b => {
       |          const memMB = b.memory_bytes != null ? Math.round(b.memory_bytes / 1048576) : '-';
       |          const lastAccess = b.last_access_time != null ? new Date(b.last_access_time).toLocaleTimeString() : '-';
       |          const held = b.held ? 'Yes' : 'No';
       |          const stateClass = 'status-' + b.state;
       |          const actions = b.held
       |            ? `<button class="release" onclick="releaseBackend('$${b.id}')">Release</button>`
       |            : `<button onclick="holdBackend('$${b.id}')">Hold</button>`;
       |          tbody.innerHTML += `<tr>
       |            <td>$${b.id}</td>
       |            <td>$${b.backend_type}</td>
       |            <td class="$${stateClass}">$${b.state}</td>
       |            <td>$${b.port != null ? b.port : '-'}</td>
       |            <td>$${memMB}</td>
       |            <td>$${lastAccess}</td>
       |            <td>$${held}</td>
       |            <td>$${actions}</td>
       |          </tr>`;
       |        });
       |
       |        document.getElementById('last-updated').textContent = 'Last updated: ' + new Date().toLocaleTimeString();
       |      } catch (e) {
       |        console.error('Failed to fetch status:', e);
       |      }
       |    }
       |
       |    $tracingJs
       |
       |    function refresh() {
       |      fetchStatus();
       |      $tracingRefresh
       |    }
       |
       |    refresh();
       |    setInterval(refresh, 5000);
       |  </script>
       |</body>
       |</html>""".stripMargin
  }
}
