import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Divisor de Tarefas - JDK 17+.
 * Backend: com.sun.net.httpserver + API REST.
 * Algoritmo: LPT (Longest Processing Time first) para balancear carga.
 * Frontend: HTML/JS embutido, servido em "/".
 *
 * Rodar: java TaskDivider.java [porta]
 */
public class TaskDivider {

    record Task(int id, String title, int effort) {}

    record Assignment(String member, int load, List<Task> tasks) {}

    // ---------- Domínio ----------
    static final Object LOCK = new Object();
    static final List<String> members = new ArrayList<>();
    static final List<Task> tasks = new ArrayList<>();
    static int nextId = 1;

    /** LPT: ordena tarefas da maior para a menor e entrega sempre ao membro menos carregado. */
    static List<Assignment> divide() {
        Map<String, List<Task>> bucket = new LinkedHashMap<>();
        Map<String, Integer> load = new LinkedHashMap<>();
        for (String m : members) {
            bucket.put(m, new ArrayList<>());
            load.put(m, 0);
        }
        if (members.isEmpty()) return List.of();

        List<Task> sorted = new ArrayList<>(tasks);
        sorted.sort(Comparator.comparingInt(Task::effort).reversed().thenComparingInt(Task::id));

        for (Task t : sorted) {
            String target = Collections.min(load.entrySet(),
                    Map.Entry.<String, Integer>comparingByValue()).getKey();
            bucket.get(target).add(t);
            load.merge(target, t.effort(), Integer::sum);
        }
        List<Assignment> result = new ArrayList<>();
        bucket.forEach((m, list) -> result.add(new Assignment(m, load.get(m), list)));
        return result;
    }

    // ---------- JSON manual ----------
    static String esc(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static String taskJson(Task t) {
        return "{\"id\":" + t.id() + ",\"title\":" + esc(t.title()) + ",\"effort\":" + t.effort() + "}";
    }

    static String stateJson() {
        StringBuilder sb = new StringBuilder("{\"members\":[");
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(esc(members.get(i)));
        }
        sb.append("],\"tasks\":[");
        for (int i = 0; i < tasks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(taskJson(tasks.get(i)));
        }
        sb.append("],\"plan\":[");
        List<Assignment> plan = divide();
        for (int i = 0; i < plan.size(); i++) {
            Assignment a = plan.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"member\":").append(esc(a.member()))
              .append(",\"load\":").append(a.load()).append(",\"tasks\":[");
            for (int j = 0; j < a.tasks().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(taskJson(a.tasks().get(j)));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    // ---------- HTTP ----------
    static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void api(HttpExchange ex, String action) throws IOException {
        try {
            synchronized (LOCK) {
                Map<String, String> f = ex.getRequestMethod().equals("POST") ? form(ex) : Map.of();
                switch (action) {
                    case "state" -> { }
                    case "member-add" -> {
                        String n = f.getOrDefault("name", "").trim();
                        if (n.isEmpty() || n.length() > 40) throw new IllegalArgumentException("Nome inválido (1-40 caracteres).");
                        if (members.contains(n)) throw new IllegalArgumentException("Membro já existe.");
                        members.add(n);
                    }
                    case "member-del" -> members.remove(f.getOrDefault("name", ""));
                    case "task-add" -> {
                        String t = f.getOrDefault("title", "").trim();
                        int e = Integer.parseInt(f.getOrDefault("effort", "1").trim());
                        if (t.isEmpty() || t.length() > 80) throw new IllegalArgumentException("Título inválido (1-80 caracteres).");
                        if (e < 1 || e > 100) throw new IllegalArgumentException("Esforço deve estar entre 1 e 100.");
                        tasks.add(new Task(nextId++, t, e));
                    }
                    case "task-del" -> {
                        int id = Integer.parseInt(f.getOrDefault("id", "0"));
                        tasks.removeIf(t -> t.id() == id);
                    }
                    case "reset" -> { members.clear(); tasks.clear(); nextId = 1; }
                    default -> { send(ex, 404, "application/json", "{\"error\":\"not found\"}"); return; }
                }
                send(ex, 200, "application/json", stateJson());
            }
        } catch (NumberFormatException e) {
            send(ex, 400, "application/json", "{\"error\":\"Número inválido.\"}");
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", "{\"error\":" + esc(e.getMessage()) + "}");
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "404"); return; }
            send(ex, 200, "text/html", PAGE);
        });
        Map<String, String> routes = Map.of(
                "/api/state", "state",
                "/api/members/add", "member-add",
                "/api/members/delete", "member-del",
                "/api/tasks/add", "task-add",
                "/api/tasks/delete", "task-del",
                "/api/reset", "reset");
        routes.forEach((path, action) -> server.createContext(path, ex -> api(ex, action)));

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Divisor de Tarefas rodando em http://localhost:" + port + "  (Ctrl+C para sair)");
    }

    // ---------- Frontend embutido ----------
    static final String PAGE = """
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Divisor de Tarefas</title>
<style>
  :root { --bg:#f6f7f9; --card:#fff; --text:#1c2330; --muted:#667085; --line:#e4e7ec; --accent:#3b5bdb; --danger:#c92a2a; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#12151c; --card:#1b2030; --text:#e8ebf2; --muted:#98a2b3; --line:#2b3347; --accent:#748ffc; --danger:#ff8787; }
  }
  * { box-sizing: border-box; }
  body { margin:0; font-family: system-ui, sans-serif; background:var(--bg); color:var(--text); }
  main { max-width: 960px; margin: 0 auto; padding: 24px 16px 48px; }
  h1 { font-size: 1.5rem; margin: 0 0 4px; }
  .sub { color: var(--muted); margin: 0 0 20px; }
  .grid { display:grid; gap:16px; grid-template-columns: 1fr 1fr; }
  @media (max-width: 700px) { .grid { grid-template-columns: 1fr; } }
  .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px; }
  h2 { font-size:1rem; margin:0 0 12px; }
  form { display:flex; gap:8px; margin-bottom:12px; }
  input { flex:1; min-width:0; padding:8px 10px; border:1px solid var(--line); border-radius:6px; background:var(--bg); color:var(--text); }
  input[type=number] { flex:0 0 80px; }
  button { padding:8px 12px; border:0; border-radius:6px; background:var(--accent); color:#fff; cursor:pointer; }
  button.ghost { background:transparent; color:var(--danger); padding:2px 8px; }
  ul { list-style:none; margin:0; padding:0; }
  li { display:flex; justify-content:space-between; align-items:center; padding:6px 0; border-bottom:1px solid var(--line); }
  li:last-child { border-bottom:0; }
  .tag { background:var(--bg); border:1px solid var(--line); border-radius:99px; padding:1px 8px; font-size:.8rem; color:var(--muted); margin-left:8px; }
  .bar { height:8px; border-radius:4px; background:var(--line); margin:6px 0 10px; overflow:hidden; }
  .bar > div { height:100%; background:var(--accent); }
  .err { color:var(--danger); min-height:1.2em; margin:0 0 8px; font-size:.9rem; }
  .empty { color:var(--muted); font-size:.9rem; }
  .head { display:flex; justify-content:space-between; align-items:baseline; }
</style>
</head>
<body>
<main>
  <div class="head">
    <div><h1>Divisor de Tarefas</h1>
    <p class="sub">Distribui tarefas entre a equipe equilibrando o esforço (algoritmo LPT).</p></div>
    <button class="ghost" id="reset">Limpar tudo</button>
  </div>
  <p class="err" id="err"></p>

  <div class="grid">
    <section class="card">
      <h2>Equipe</h2>
      <form id="fm"><input name="name" placeholder="Nome do membro" required maxlength="40"><button>Adicionar</button></form>
      <ul id="members"></ul>
    </section>
    <section class="card">
      <h2>Tarefas</h2>
      <form id="ft"><input name="title" placeholder="Título da tarefa" required maxlength="80">
        <input name="effort" type="number" min="1" max="100" value="3" title="Esforço (1-100)"><button>Adicionar</button></form>
      <ul id="tasks"></ul>
    </section>
  </div>

  <section class="card" style="margin-top:16px">
    <h2>Plano de divisão</h2>
    <p class="empty" id="planEmpty">Adicione membros e tarefas para ver a divisão.</p>
    <div id="plan" class="grid"></div>
  </section>
</main>

<script>
const $ = id => document.getElementById(id);
const err = $('err');

async function call(path, data) {
  const opts = data === undefined ? {} : { method: 'POST', body: new URLSearchParams(data) };
  const res = await fetch(path, opts);
  const json = await res.json();
  if (!res.ok) { err.textContent = json.error || 'Erro'; return; }
  err.textContent = '';
  render(json);
}

function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

function row(label, tagText, onDelete) {
  const li = el('li');
  const left = el('span', label);
  if (tagText) left.append(el('span', tagText, 'tag'));
  const del = el('button', '✕', 'ghost');
  del.title = 'Remover';
  del.onclick = onDelete;
  li.append(left, del);
  return li;
}

function render(s) {
  const ms = $('members'); ms.replaceChildren();
  s.members.forEach(m => ms.append(row(m, null, () => call('/api/members/delete', { name: m }))));
  if (!s.members.length) ms.append(el('li', 'Nenhum membro ainda.', 'empty'));

  const ts = $('tasks'); ts.replaceChildren();
  s.tasks.forEach(t => ts.append(row(t.title, t.effort + ' pts', () => call('/api/tasks/delete', { id: t.id }))));
  if (!s.tasks.length) ts.append(el('li', 'Nenhuma tarefa ainda.', 'empty'));

  const plan = $('plan'); plan.replaceChildren();
  $('planEmpty').style.display = s.plan.length && s.tasks.length ? 'none' : '';
  if (!s.tasks.length) return;
  const max = Math.max(1, ...s.plan.map(a => a.load));
  s.plan.forEach(a => {
    const card = el('div', undefined, 'card');
    const h = el('h2', a.member); h.append(el('span', a.load + ' pts', 'tag'));
    const bar = el('div', undefined, 'bar'); const fill = el('div');
    fill.style.width = (a.load / max * 100) + '%'; bar.append(fill);
    const ul = el('ul');
    a.tasks.forEach(t => { const li = el('li', t.title); li.append(el('span', t.effort + ' pts', 'tag')); ul.append(li); });
    if (!a.tasks.length) ul.append(el('li', 'Sem tarefas.', 'empty'));
    card.append(h, bar, ul); plan.append(card);
  });
}

$('fm').onsubmit = e => { e.preventDefault(); call('/api/members/add', Object.fromEntries(new FormData(e.target))); e.target.reset(); };
$('ft').onsubmit = e => { e.preventDefault(); call('/api/tasks/add', Object.fromEntries(new FormData(e.target))); e.target.reset(); e.target.effort.value = 3; };
$('reset').onclick = () => confirm('Apagar equipe e tarefas?') && call('/api/reset', {});
call('/api/state');
</script>
</body>
</html>
""";
}
