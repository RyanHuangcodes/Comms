import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

public final class Back {

	private static final int DEFAULT_PORT = 8000;
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
	private static final List<String> STANDARD_SPORTS = List.of("Soccer", "Basketball", "Baseball", "Football", "Tennis");

	private final State state = new State();

	public static void main(String[] args) throws IOException {
		int port = args.length > 0 ? parsePort(args[0]) : DEFAULT_PORT;
		new Back().start(port);
	}

	private static int parsePort(String value) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception exception) {
			return DEFAULT_PORT;
		}
	}

	private void start(int port) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", new Router());
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		System.out.println("Coach Studio backend running on http://localhost:" + port);
	}

	private final class Router implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
			String path = stripQuery(exchange.getRequestURI().getPath());

			try {
				if ("GET".equals(method)) {
					handleGet(exchange, path);
					return;
				}
				if ("POST".equals(method)) {
					handlePost(exchange, path);
					return;
				}
				sendText(exchange, 405, "Method Not Allowed");
			} catch (Exception exception) {
				sendText(exchange, 500, "Server error: " + escapeHtml(exception.getMessage()));
			}
		}
	}

	private void handleGet(HttpExchange exchange, String path) throws IOException {
		Map<String, String> query = parseQuery(exchange.getRequestURI());
		switch (path) {
			case "/":
			case "/index.html":
				sendHtml(exchange, page("home", currentDate(), homeBody()));
				return;
			case "/plans":
				sendHtml(exchange, page("plans", currentDate(), plansBody()));
				return;
			case "/players":
				sendHtml(exchange, page("players", currentDate(), blankBody("Players", "Player management space", "This page is intentionally blank for now so you can build out the roster tools later.")));
				return;
			case "/settings":
				sendHtml(exchange, page("settings", currentDate(), blankBody("Settings", "Settings space", "This page is intentionally blank for now so you can add preferences and controls later.")));
				return;
			case "/planner":
				sendHtml(exchange, page("planner", currentDate(), blankBody("Planner", "Blank planning space", "This page is intentionally blank for now so you can build the planning workflow later.")));
				return;
			case "/new-plan":
				sendHtml(exchange, page("home", currentDate(), newPlanBody(query.getOrDefault("draft", ""))));
				return;
			case "/drafts":
				sendHtml(exchange, page("home", currentDate(), draftsBody()));
				return;
			default:
				sendText(exchange, 404, "Not Found");
		}
	}

	private void handlePost(HttpExchange exchange, String path) throws IOException {
		Map<String, String> form = parseForm(exchange);
		switch (path) {
			case "/session/start":
				redirect(exchange, "/planner");
				return;
			case "/drafts/save":
				saveDraft(form);
				redirect(exchange, "/drafts");
				return;
			case "/plans/create":
				createPlan(form);
				redirect(exchange, "/plans");
				return;
			case "/drafts/load":
				redirect(exchange, "/new-plan?draft=" + encode(form.getOrDefault("draftId", "")));
				return;
			default:
				sendText(exchange, 404, "Not Found");
		}
	}

	private void saveDraft(Map<String, String> form) {
		PlanInput input = parsePlanInput(form);
		if (input.name.isBlank()) {
			return;
		}
		synchronized (state) {
			state.preferredSport = input.name;
			String draftId = form.getOrDefault("draftId", "").trim();
			DraftRecord draft = new DraftRecord(draftId.isEmpty() ? generatedId("draft") : draftId, input.name, currentDate(), formatDuration(input.durationMinutes), input.durationMinutes);
			state.drafts.removeIf(existing -> existing.id.equals(draft.id));
			state.drafts.add(0, draft);
		}
	}

	private void createPlan(Map<String, String> form) {
		PlanInput input = parsePlanInput(form);
		if (input.name.isBlank()) {
			return;
		}
		synchronized (state) {
			state.preferredSport = input.name;
			state.plans.add(0, new PlanRecord(generatedId("plan"), input.name, currentDate(), formatDuration(input.durationMinutes), input.durationMinutes));
			String draftId = form.getOrDefault("draftId", "").trim();
			if (!draftId.isEmpty()) {
				state.drafts.removeIf(existing -> existing.id.equals(draftId));
			}
		}
	}

	private PlanInput parsePlanInput(Map<String, String> form) {
		String sport = form.getOrDefault("sport", state.preferredSport).trim();
		String customSport = form.getOrDefault("customSport", "").trim();
		String selectedSport = "__other__".equals(sport) ? customSport : sport;
		int duration = clampDuration(form.getOrDefault("duration", form.getOrDefault("durationDisplay", "60")));
		return new PlanInput(selectedSport, duration);
	}

	private String homeBody() {
		int draftCount;
		String preferredSport;
		synchronized (state) {
			draftCount = state.drafts.size();
			preferredSport = state.preferredSport;
		}

		return """
			<section class="hero-card" aria-label="Today session overview">
				<div class="hero-row">
					<div class="hero-badge">Live Today</div>
					<div class="calendar" aria-hidden="true"></div>
				</div>
				<h2 class="session-title">Elite Squad Training</h2>
				<p class="session-subtitle">Offense &amp; transition focus</p>
				<div class="stats">
					<div><div class="stat-label">Duration</div><div class="stat-value">90 <span class="stat-unit">m</span></div></div>
					<div><div class="stat-label">Roster</div><div class="stat-value">24 <span class="stat-unit">p</span></div></div>
				</div>
				<div class="button-row">
					<form action="/session/start" method="post"><button class="primary-action" type="submit"><span class="play">&gt;</span><span>Start Session</span></button></form>
					<a class="secondary-action" href="/new-plan">New Plan</a>
					<a class="secondary-action" href="/drafts">Drafts (""" + draftCount + "")</a>
				</div>
				<p class="hero-footnote">Preferred sport: <strong>""" + escapeHtml(preferredSport) + """</strong></p>
			</section>
			<section class="cards" aria-label="Quick actions">
				<a class="mini-card" href="/new-plan"><div class="mini-icon" aria-hidden="true">N</div><div class="mini-label">New Plan</div></a>
				<a class="mini-card" href="/drafts"><div class="mini-icon" aria-hidden="true">D<span class="dot"></span></div><div class="mini-label">Drafts <span>(""" + draftCount + "")</span></div></a>
			</section>
			<section class="insight" aria-label="AI strategy insight">
				<h2 class="insight-title">AI Strategy Insight</h2>
				<p class="insight-copy">Based on yesterday's metrics, focusing on <strong>rapid transition defense</strong> will optimize the next training block.</p>
			</section>
			<nav class="nav" aria-label="Primary navigation">
				<a class="nav-item active" href="/"><span class="icon">H</span><span>Home</span></a>
				<a class="nav-item" href="/plans"><span class="icon">P</span><span>Plans</span></a>
				<a class="nav-item" href="/players"><span class="icon">U</span><span>Players</span></a>
				<a class="nav-item" href="/settings"><span class="icon">S</span><span>Settings</span></a>
			</nav>
		""";
	}

	private String plansBody() {
		List<PlanRecord> snapshot;
		synchronized (state) {
			snapshot = new ArrayList<>(state.plans);
		}

		StringBuilder html = new StringBuilder();
		html.append("<section class=\"blank-panel plans-panel\"><div class=\"plans-header\"><div><div class=\"blank-eyebrow\">Plans</div><h2>Lesson plans</h2></div><p>Created plans appear here as a clean working list.</p></div><div class=\"plans-list\">");
		if (snapshot.isEmpty()) {
			html.append("<div class=\"plan-empty\">No plans yet</div>");
		} else {
			for (PlanRecord plan : snapshot) {
				html.append("<div class=\"plan-item\"><div class=\"plan-name\">")
					.append(escapeHtml(plan.name))
					.append("</div><div class=\"plan-meta\"><div class=\"plan-date\">")
					.append(escapeHtml(plan.date))
					.append("</div><div class=\"plan-time\">")
					.append(escapeHtml(plan.time))
					.append("</div></div></div>");
			}
		}
		html.append("</div></section><nav class=\"nav\" aria-label=\"Primary navigation\"><a class=\"nav-item\" href=\"/\"><span class=\"icon\">H</span><span>Home</span></a><a class=\"nav-item active\" href=\"/plans\"><span class=\"icon\">P</span><span>Plans</span></a><a class=\"nav-item\" href=\"/players\"><span class=\"icon\">U</span><span>Players</span></a><a class=\"nav-item\" href=\"/settings\"><span class=\"icon\">S</span><span>Settings</span></a></nav>");
		return html.toString();
	}

	private String draftsBody() {
		List<DraftRecord> snapshot;
		synchronized (state) {
			snapshot = new ArrayList<>(state.drafts);
		}

		StringBuilder html = new StringBuilder();
		html.append("<section class=\"blank-panel plans-panel\"><div class=\"plans-header\"><div><div class=\"blank-eyebrow\">Drafts</div><h2>Selected drafts</h2></div><p>Open a draft to resume planning in the backend.</p></div><div class=\"draft-list\">");
		if (snapshot.isEmpty()) {
			html.append("<div class=\"draft-empty\">No drafts yet</div>");
		} else {
			for (DraftRecord draft : snapshot) {
				html.append("<form action=\"/drafts/load\" method=\"post\"><input type=\"hidden\" name=\"draftId\" value=\"")
					.append(escapeHtml(draft.id))
					.append("\"><button class=\"draft-item\" type=\"submit\"><div><strong>")
					.append(escapeHtml(draft.name))
					.append("</strong><span>")
					.append(escapeHtml(draft.date))
					.append("</span></div><span>")
					.append(escapeHtml(draft.time))
					.append("</span></button></form>");
			}
		}
		html.append("</div></section><nav class=\"nav\" aria-label=\"Primary navigation\"><a class=\"nav-item\" href=\"/\"><span class=\"icon\">H</span><span>Home</span></a><a class=\"nav-item\" href=\"/plans\"><span class=\"icon\">P</span><span>Plans</span></a><a class=\"nav-item\" href=\"/players\"><span class=\"icon\">U</span><span>Players</span></a><a class=\"nav-item\" href=\"/settings\"><span class=\"icon\">S</span><span>Settings</span></a></nav>");
		return html.toString();
	}

	private String newPlanBody(String draftId) {
		PlanForm form = resolveDraft(draftId);
		StringBuilder html = new StringBuilder();
		html.append("<section class=\"blank-panel\"><div class=\"blank-eyebrow\">New Plan</div><h2>Create a lesson plan</h2><p>All form handling stays on the Java backend. No client script is required.</p><form class=\"plan-form\" action=\"/plans/create\" method=\"post\"><input type=\"hidden\" name=\"draftId\" value=\"")
			.append(escapeHtml(form.draftId))
			.append("\"><div class=\"form-grid\"><label class=\"field\"><span>Sport</span><select name=\"sport\">");

		for (String sport : sportOptions(form.selectedSport)) {
			html.append("<option value=\"").append(escapeHtml(sport)).append("\"");
			if (sport.equals(form.selectedSport)) {
				html.append(" selected");
			}
			html.append(">")
				.append(escapeHtml("__other__".equals(sport) ? "Other" : sport))
				.append("</option>");
		}

		html.append("</select></label><label class=\"field\"><span>Custom sport</span><input name=\"customSport\" type=\"text\" placeholder=\"Type a sport name\" value=\"")
			.append(escapeHtml(form.customSport))
			.append("\"></label><label class=\"field\"><span>Duration</span><input name=\"duration\" type=\"number\" min=\"5\" max=\"240\" step=\"5\" value=\"")
			.append(form.durationMinutes)
			.append("\"></label></div><div class=\"modal-actions\"><a class=\"secondary-button\" href=\"/\">Cancel</a><a class=\"secondary-button\" href=\"/planner\">Open planner</a><button class=\"secondary-button\" type=\"submit\" formaction=\"/drafts/save\">Save to Draft</button><button class=\"primary-button\" type=\"submit\">Create</button></div></form></section><nav class=\"nav\" aria-label=\"Primary navigation\"><a class=\"nav-item active\" href=\"/\"><span class=\"icon\">H</span><span>Home</span></a><a class=\"nav-item\" href=\"/plans\"><span class=\"icon\">P</span><span>Plans</span></a><a class=\"nav-item\" href=\"/players\"><span class=\"icon\">U</span><span>Players</span></a><a class=\"nav-item\" href=\"/settings\"><span class=\"icon\">S</span><span>Settings</span></a></nav>");
		return html.toString();
	}

	private PlanForm resolveDraft(String draftId) {
		if (draftId == null || draftId.isBlank()) {
			synchronized (state) {
				return new PlanForm("", state.preferredSport, "", 60);
			}
		}

		synchronized (state) {
			for (DraftRecord draft : state.drafts) {
				if (draft.id.equals(draftId)) {
					boolean standard = STANDARD_SPORTS.contains(draft.name);
					return new PlanForm(draft.id, standard ? draft.name : "__other__", standard ? "" : draft.name, draft.durationMinutes);
				}
			}
			return new PlanForm("", state.preferredSport, "", 60);
		}
	}

	private String blankBody(String eyebrow, String title, String copy) {
		return "<section class=\"blank-panel\"><div class=\"blank-eyebrow\">" + escapeHtml(eyebrow) + "</div><h2>" + escapeHtml(title) + "</h2><p>" + escapeHtml(copy) + "</p></section><nav class=\"nav\" aria-label=\"Primary navigation\"><a class=\"nav-item\" href=\"/\"><span class=\"icon\">H</span><span>Home</span></a><a class=\"nav-item\" href=\"/plans\"><span class=\"icon\">P</span><span>Plans</span></a><a class=\"nav-item\" href=\"/players\"><span class=\"icon\">U</span><span>Players</span></a><a class=\"nav-item\" href=\"/settings\"><span class=\"icon\">S</span><span>Settings</span></a></nav>";
	}

	private String page(String activePage, String date, String body) {
		return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Coach Studio</title><style>" + css() + "</style></head><body><main class=\"dashboard\" data-page=\"" + escapeHtml(activePage) + "\"><header class=\"topbar\"><div><div class=\"date\">" + escapeHtml(date) + "</div><h1 class=\"brand\">Coach <span>Ryan</span></h1></div><div class=\"avatar\" aria-hidden=\"true\"></div></header>" + body + "</main></body></html>";
	}

	private String css() {
		return """
			:root { color-scheme: dark; --line: rgba(255,255,255,.08); --text: #f5f2ec; --muted: rgba(245,242,236,.62); --accent: #ecf120; --accent-soft: rgba(236,241,32,.14); --shadow: 0 30px 80px rgba(0,0,0,.55); }
			* { box-sizing: border-box; }
			body { margin: 0; min-height: 100vh; font-family: "SF Pro Display", "Segoe UI", "Helvetica Neue", Arial, sans-serif; background: linear-gradient(180deg, #1d0907 0%, #090909 22%, #050505 100%); color: var(--text); }
			.dashboard { width: 100vw; min-height: 100vh; padding: 28px 30px 22px; display: grid; gap: 18px; }
			.topbar { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
			.date { font-size: .82rem; letter-spacing: .22em; text-transform: uppercase; color: var(--muted); margin-bottom: 12px; }
			.brand { font-size: clamp(2.2rem, 8vw, 3rem); line-height: .92; letter-spacing: -.06em; font-weight: 900; text-transform: uppercase; margin: 0; }
			.brand span { color: #fff; display: inline-block; transform: skewX(-8deg); }
			.avatar { width: 64px; height: 64px; border-radius: 50%; background: radial-gradient(circle at 70% 30%, rgba(236,241,32,.95) 0 8px, transparent 9px), radial-gradient(circle at 50% 50%, #09111f 0, #02050a 70%); border: 1px solid rgba(255,255,255,.12); }
			.hero-card, .blank-panel, .insight { background: linear-gradient(180deg, rgba(34,34,37,.98), rgba(18,18,19,.98)); border: 1px solid var(--line); border-radius: 32px; padding: 22px 20px 26px; box-shadow: inset 0 1px 0 rgba(255,255,255,.04); }
			.hero-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
			.hero-badge { display: inline-flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 999px; background: var(--accent-soft); color: var(--accent); font-size: .82rem; font-weight: 800; letter-spacing: .08em; text-transform: uppercase; }
			.hero-badge::before, .blank-eyebrow::before, .insight-title::before { content: ''; width: 9px; height: 9px; border-radius: 50%; background: currentColor; box-shadow: 0 0 16px currentColor; }
			.calendar { width: 30px; height: 30px; border-radius: 50%; background: rgba(255,255,255,.08); position: relative; }
			.calendar::before, .calendar::after { content: ''; position: absolute; left: 7px; right: 7px; background: rgba(255,255,255,.56); border-radius: 2px; }
			.calendar::before { top: 10px; height: 2px; } .calendar::after { top: 14px; height: 8px; opacity: .38; }
			.session-title, .blank-panel h2 { font-size: clamp(1.95rem, 6vw, 2.55rem); line-height: .98; letter-spacing: -.06em; margin: 26px 0 8px; font-weight: 900; }
			.session-subtitle, .blank-panel p { margin: 0 0 26px; color: var(--muted); font-size: 1rem; font-weight: 600; line-height: 1.7; }
			.stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-bottom: 22px; }
			.stat-label { color: var(--muted); font-size: 1rem; font-weight: 650; margin-bottom: 8px; }
			.stat-value { display: flex; align-items: baseline; gap: 8px; font-size: 3rem; font-weight: 900; letter-spacing: -.08em; line-height: 1; }
			.stat-unit { font-size: 1.1rem; font-weight: 700; color: rgba(245,242,236,.58); }
			.button-row { display: flex; flex-wrap: wrap; gap: 10px; }
			.primary-action, .secondary-action, .primary-button, .secondary-button, .nav-item, .mini-card { display: inline-flex; align-items: center; justify-content: center; gap: 12px; border: 0; text-decoration: none; cursor: pointer; }
			.primary-action, .secondary-action, .primary-button, .secondary-button { padding: 14px 18px; border-radius: 999px; font: inherit; font-weight: 800; letter-spacing: -.02em; }
			.primary-action, .primary-button { background: linear-gradient(180deg, #fffef9, #f4f1eb); color: #090909; }
			.secondary-action, .secondary-button { background: rgba(255,255,255,.08); color: var(--text); }
			.play { width: 22px; height: 22px; border-radius: 50%; background: #090909; color: #fff; display: grid; place-items: center; font-size: .72rem; padding-left: 2px; }
			.hero-footnote { margin: 18px 0 0; color: var(--muted); }
			.cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
			.mini-card { background: linear-gradient(180deg, rgba(40,40,44,.98), rgba(28,28,30,.98)); border: 1px solid rgba(255,255,255,.05); border-radius: 24px; padding: 16px 14px 18px; min-height: 116px; display: grid; place-items: center; text-align: center; gap: 12px; color: var(--text); }
			.mini-icon { width: 50px; height: 50px; border-radius: 50%; background: #090909; display: grid; place-items: center; color: #fff; font-size: 1.2rem; position: relative; }
			.mini-icon .dot { position: absolute; top: 1px; right: 1px; width: 8px; height: 8px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 10px rgba(236,241,32,.9); }
			.mini-label { font-size: 1.05rem; font-weight: 800; letter-spacing: -.03em; color: var(--text); }
			.insight { padding-bottom: 32px; }
			.insight-title, .blank-eyebrow { display: flex; align-items: center; gap: 10px; margin: 0 0 18px; font-size: 1.06rem; font-weight: 900; letter-spacing: -.04em; text-transform: uppercase; color: var(--text); }
			.insight-copy { margin: 0; font-size: 1rem; line-height: 1.75; color: rgba(245,242,236,.74); font-weight: 600; }
			.nav { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; padding: 14px 10px 12px; border-radius: 28px; background: rgba(28,28,30,.92); border: 1px solid rgba(255,255,255,.06); box-shadow: 0 20px 50px rgba(0,0,0,.38); }
			.nav-item { color: rgba(245,242,236,.52); display: grid; justify-items: center; gap: 6px; padding: 8px 6px; text-transform: uppercase; font-size: .68rem; font-weight: 800; letter-spacing: .06em; background: transparent; }
			.nav-item .icon { width: 40px; height: 40px; border-radius: 50%; display: grid; place-items: center; background: transparent; font-size: 1rem; }
			.nav-item.active { color: var(--accent); }
			.nav-item.active .icon { background: rgba(236,241,32,.14); box-shadow: inset 0 0 0 1px rgba(236,241,32,.16), 0 0 16px rgba(236,241,32,.12); }
			.plans-panel { display: grid; gap: 18px; }
			.plans-header { display: flex; justify-content: space-between; align-items: end; gap: 16px; }
			.plans-header p, .plans-header h2 { margin: 0; }
			.plans-list, .draft-list { display: grid; gap: 14px; }
			.plan-item, .draft-item { display: grid; gap: 8px; padding: 18px 18px 16px; border-radius: 24px; background: linear-gradient(180deg, rgba(42,42,46,.98), rgba(20,20,22,.98)); border: 1px solid rgba(255,255,255,.06); box-shadow: inset 0 1px 0 rgba(255,255,255,.04); color: var(--text); }
			.plan-name { font-size: clamp(1.2rem, 2.4vw, 1.65rem); line-height: 1; letter-spacing: -.05em; font-weight: 900; margin: 0; }
			.plan-meta { display: flex; justify-content: space-between; gap: 16px; align-items: end; flex-wrap: wrap; }
			.plan-date { font-size: 1rem; font-weight: 700; letter-spacing: -.02em; color: rgba(245,242,236,.92); }
			.plan-time { font-size: .88rem; color: var(--muted); font-weight: 700; letter-spacing: .04em; text-transform: uppercase; }
			.plan-empty, .draft-empty { padding: 24px 18px; border-radius: 24px; background: rgba(255,255,255,.04); border: 1px dashed rgba(255,255,255,.12); color: var(--muted); line-height: 1.65; }
			.draft-item { width: 100%; font: inherit; text-align: left; appearance: none; -webkit-appearance: none; display: flex; justify-content: space-between; gap: 12px; padding: 14px 16px; border-radius: 18px; background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.06); }
			.draft-item strong { display: block; font-size: 1rem; margin-bottom: 4px; }
			.draft-item span { color: var(--muted); font-size: .92rem; }
			.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
			.field { display: grid; gap: 8px; }
			.field span { font-size: .8rem; letter-spacing: .18em; text-transform: uppercase; color: var(--muted); font-weight: 700; }
			.field select, .field input { width: 100%; border: 1px solid rgba(255,255,255,.08); background: rgba(255,255,255,.04); color: var(--text); border-radius: 18px; padding: 14px 16px; font: inherit; }
			.modal-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 12px; margin-top: 8px; }
			@media (max-width: 720px) { .dashboard { padding: 18px 16px 20px; } .form-grid, .cards { grid-template-columns: 1fr; } .plans-header { flex-direction: column; align-items: flex-start; } .nav { padding: 8px 8px 6px; border-radius: 24px; gap: 4px; } .nav-item { font-size: .62rem; padding: 6px 4px; } .nav-item .icon { width: 34px; height: 34px; font-size: .9rem; } }
		""";
	}

	private static String stripQuery(String path) {
		int index = path.indexOf('?');
		return index >= 0 ? path.substring(0, index) : path;
	}

	private static Map<String, String> parseQuery(URI uri) {
		return decodeForm(uri.getRawQuery());
	}

	private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
		return decodeForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
	}

	private static Map<String, String> decodeForm(String encoded) {
		Map<String, String> values = new LinkedHashMap<>();
		if (encoded == null || encoded.isBlank()) {
			return values;
		}
		for (String pair : encoded.split("&")) {
			if (pair.isBlank()) continue;
			String[] parts = pair.split("=", 2);
			String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
			String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
			values.put(key, value);
		}
		return values;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String generatedId(String prefix) {
		return prefix + "-" + System.currentTimeMillis();
	}

	private static int clampDuration(String value) {
		try {
			int parsed = Integer.parseInt(value.trim());
			return Math.max(5, Math.min(240, parsed));
		} catch (Exception exception) {
			return 60;
		}
	}

	private static String formatDuration(int minutes) {
		return minutes + " min";
	}

	private static String currentDate() {
		return LocalDate.now().format(DATE_FORMAT);
	}

	private static String escapeHtml(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	private static List<String> sportOptions(String selectedSport) {
		List<String> options = new ArrayList<>();
		if (selectedSport == null || selectedSport.isBlank()) {
			selectedSport = "Soccer";
		}
		options.add(selectedSport);
		for (String sport : STANDARD_SPORTS) {
			if (!options.contains(sport)) {
				options.add(sport);
			}
		}
		if (!options.contains("__other__")) {
			options.add("__other__");
		}
		return options;
	}

	private static void sendHtml(HttpExchange exchange, String html) throws IOException {
		byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void redirect(HttpExchange exchange, String location) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.set("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private static final class State {
		private final List<DraftRecord> drafts = new ArrayList<>();
		private final List<PlanRecord> plans = new ArrayList<>();
		private String preferredSport = "Soccer";
	}

	private static final class DraftRecord {
		private final String id;
		private final String name;
		private final String date;
		private final String time;
		private final int durationMinutes;

		private DraftRecord(String id, String name, String date, String time, int durationMinutes) {
			this.id = id;
			this.name = name;
			this.date = date;
			this.time = time;
			this.durationMinutes = durationMinutes;
		}
	}

	private static final class PlanRecord {
		private final String id;
		private final String name;
		private final String date;
		private final String time;
		private final int durationMinutes;

		private PlanRecord(String id, String name, String date, String time, int durationMinutes) {
			this.id = id;
			this.name = name;
			this.date = date;
			this.time = time;
			this.durationMinutes = durationMinutes;
		}
	}

	private static final class PlanInput {
		private final String name;
		private final int durationMinutes;

		private PlanInput(String name, int durationMinutes) {
			this.name = name;
			this.durationMinutes = durationMinutes;
		}
	}

	private static final class PlanForm {
		private final String draftId;
		private final String selectedSport;
		private final String customSport;
		private final int durationMinutes;

		private PlanForm(String draftId, String selectedSport, String customSport, int durationMinutes) {
			this.draftId = draftId;
			this.selectedSport = selectedSport;
			this.customSport = customSport;
			this.durationMinutes = durationMinutes;
		}
	}
}