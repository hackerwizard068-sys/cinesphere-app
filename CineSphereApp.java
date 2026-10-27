import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// =========================================================================
// 1. MAIN HTTP APPLICATION CONTROLLER (Entry point for Java runtime)
// =========================================================================
public class CineSphereApp {

    public static void main(String[] args) throws IOException {
        int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", new StaticPageHandler());
        server.createContext("/api/movies", new ApiMoviesHandler());
        server.createContext("/api/theatre", new ApiTheatreHandler());
        server.createContext("/api/book", new ApiBookHandler());
        server.createContext("/api/cancel", new ApiCancelHandler());
        server.createContext("/api/history", new ApiHistoryHandler());

        server.setExecutor(null);
        System.out.println("===============================================================");
        System.out.println("  CINESPHERE - MOVIE TICKET BOOKING SYSTEM                     ");
        System.out.println("===============================================================");
        System.out.println("  Server is LIVE at port: " + port);
        System.out.println("===============================================================");
        server.start();
    }

    private static Map<String, String> parseParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8.name()),
                            URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name()));
                } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) bos.write(buffer, 0, len);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static class ApiMoviesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            for (Movie m : AppState.movieCatalog.values()) {
                if (i++ > 0) sb.append(",");
                sb.append(String.format(Locale.US,
                    "{\"movieId\":%d,\"title\":\"%s\",\"genre\":\"%s\",\"movieType\":\"%s\",\"basePrice\":%.2f,\"language\":\"%s\",\"duration\":\"%s\",\"rating\":%.1f,\"synopsis\":\"%s\",\"status\":\"%s\"}",
                    m.getMovieId(), m.getTitle(), m.getGenre(), m.getMovieType(), m.getBasePrice(),
                    m.getLanguage(), m.getDuration(), m.getRating(), m.getSynopsis().replace("\"", "\\\""), m.getStatus()
                ));
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        }
    }

    static class ApiTheatreHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            Map<String, String> q = parseParams(ex.getRequestURI().getQuery());
            int movieId = Integer.parseInt(q.getOrDefault("movieId", "101"));
            Movie movie = AppState.movieCatalog.get(movieId);
            if (movie == null) movie = AppState.movieCatalog.values().iterator().next();
            Theatre theatre = AppState.theatreMap.get(movie.getMovieId());

            StringBuilder sb = new StringBuilder("{");
            sb.append("\"movieId\":").append(movie.getMovieId()).append(",");
            sb.append("\"movieTitle\":\"").append(movie.getTitle()).append("\",");
            sb.append("\"movieType\":\"").append(movie.getMovieType()).append("\",");
            sb.append("\"basePrice\":").append(movie.getBasePrice()).append(",");
            sb.append("\"rows\":").append(theatre.getRows()).append(",");
            sb.append("\"columns\":").append(theatre.getColumns()).append(",");
            sb.append("\"seats\":[");

            for (int r = 0; r < theatre.getRows(); r++) {
                if (r > 0) sb.append(",");
                sb.append("[");
                for (int c = 0; c < theatre.getColumns(); c++) {
                    if (c > 0) sb.append(",");
                    sb.append(theatre.isSeatBooked(r, c) ? 1 : 0);
                }
                sb.append("]");
            }
            sb.append("]}");
            sendJson(ex, 200, sb.toString());
        }
    }

    static class ApiBookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
            Map<String, String> params = parseParams(readBody(ex));
            int movieId = Integer.parseInt(params.get("movieId"));
            String custName = params.getOrDefault("customerName", "Customer");
            String email = params.getOrDefault("email", "guest@cinesphere.com");
            String phone = params.getOrDefault("phone", "+1 555-0199");
            String seatsStr = params.get("seats");
            String screen = params.getOrDefault("screen", "SCREEN 1");
            String showTime = params.getOrDefault("showTime", "FRI, OCT 23, 2026 - 6:30 PM");

            try {
                Ticket t = AppState.bookMultiple(movieId, custName, email, phone, seatsStr, screen, showTime);
                String json = String.format(Locale.US,
                    "{\"success\":true,\"ticketId\":\"%s\",\"movieTitle\":\"%s\",\"customerName\":\"%s\",\"seats\":\"%s\",\"finalAmount\":%.2f,\"bookingTime\":\"%s\"}",
                    t.getTicketId(), t.getMovie().getTitle(), t.getCustomer().getName(), String.join(", ", t.getSeats()),
                    t.getFinalAmount(), t.getBookingTime()
                );
                sendJson(ex, 200, json);
            } catch (SeatNotAvailableException e) {
                String err = "{\"success\":false,\"exception\":\"SeatNotAvailableException\",\"message\":\"" + e.getMessage() + "\"}";
                sendJson(ex, 409, err);
            } catch (Exception e) {
                String err = "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
                sendJson(ex, 400, err);
            }
        }
    }

    static class ApiCancelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
            Map<String, String> params = parseParams(readBody(ex));
            String ticketId = params.get("ticketId");
            boolean ok = AppState.cancel(ticketId);
            sendJson(ex, 200, "{\"success\":" + ok + ",\"message\":\"" + (ok ? "Seats released!" : "Failed") + "\"}");
        }
    }

    static class ApiHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            StringBuilder sb = new StringBuilder("[");
            int i = 0;
            List<Ticket> list = new ArrayList<>(AppState.ticketStore.values());
            list.sort((a, b) -> b.getBookingTime().compareTo(a.getBookingTime()));
            for (Ticket t : list) {
                if (i++ > 0) sb.append(",");
                sb.append(String.format(Locale.US,
                    "{\"ticketId\":\"%s\",\"customerName\":\"%s\",\"movieTitle\":\"%s\",\"theatre\":\"%s\",\"show\":\"%s\",\"seats\":\"%s\",\"finalAmount\":%.2f,\"status\":\"%s\",\"bookingTime\":\"%s\"}",
                    t.getTicketId(), t.getCustomer().getName(), t.getMovie().getTitle(), t.getScreen(),
                    t.getShowTime(), String.join(", ", t.getSeats()), t.getFinalAmount(), t.getStatus(), t.getBookingTime()
                ));
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        }
    }

    static class StaticPageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            byte[] html = getHtml().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            ex.sendResponseHeaders(200, html.length);
            ex.getResponseBody().write(html);
            ex.close();
        }

        private static String getHtml() {
            return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>CineSphere - Movie Ticket Booking System</title>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&family=JetBrains+Mono:wght@400;600;700&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg-dark: #07090e;
      --card-bg: #0f141f;
      --card-border: #1a2234;
      --text-main: #f1f5f9;
      --text-muted: #94a3b8;
      --primary-red: #ef4444;
      --primary-red-hover: #dc2626;
      --accent-green: #10b981;
      --accent-amber: #f59e0b;
      --accent-blue: #38bdf8;
      --seat-avail: #182234;
      --seat-selected: #10b981;
      --seat-booked: #334155;
    }
    * { margin:0; padding:0; box-sizing:border-box; font-family:'Space Grotesk',sans-serif; }
    body { background-color: var(--bg-dark); color: var(--text-main); min-height: 100vh; display:flex; flex-direction:column; }
    header {
      display:flex; justify-content:space-between; align-items:center;
      padding:16px 40px; background: rgba(7,9,14,0.98);
      border-bottom: 1px solid var(--card-border); position:sticky; top:0; z-index:100;
    }
    .brand { display:flex; align-items:center; gap:12px; font-weight:700; font-size:1.25rem; cursor:pointer; }
    .brand-logo { background: var(--primary-red); color:#fff; padding:6px 10px; border-radius:6px; font-weight:800; }
    .nav-links { display:flex; gap:26px; font-size:0.85rem; font-weight:700; text-transform:uppercase; color:var(--text-muted); letter-spacing:0.5px; }
    .nav-links span { cursor:pointer; transition:0.2s; }
    .nav-links span:hover, .nav-links span.active { color:#fff; }
    .nav-actions { display:flex; gap:12px; align-items:center; }
    .btn-demo { background:#141c2c; border:1px solid var(--card-border); color:#fff; padding:8px 16px; border-radius:6px; font-size:0.85rem; font-weight:700; cursor:pointer; }
    .btn-red { background:var(--primary-red); border:none; color:#fff; padding:8px 20px; border-radius:6px; font-size:0.85rem; font-weight:700; cursor:pointer; transition:0.2s; }
    .btn-red:hover { background:var(--primary-red-hover); transform:translateY(-1px); }
    main { flex:1; max-width:1440px; margin:0 auto; width:100%; padding:28px 40px; }
    .view { display:none; }
    .view.active { display:block; animation: fadeIn 0.25s ease-in-out; }
    @keyframes fadeIn { from{opacity:0; transform:translateY(6px);} to{opacity:1; transform:translateY(0);} }
    .badge-pill {
      display:inline-flex; align-items:center; gap:8px; background:#1e1416; border:1px solid #4a1d24;
      color: var(--primary-red); padding:4px 12px; border-radius:20px; font-size:0.75rem; font-weight:700;
      letter-spacing:1px; text-transform:uppercase; margin-bottom:14px;
    }
    .hero-container {
      display:grid; grid-template-columns: 1.25fr 0.75fr; gap:40px; align-items:center;
      padding:30px 0; min-height: 480px;
    }
    .hero-title { font-size: 3.4rem; font-weight:800; line-height:1.1; margin-bottom:16px; text-transform:uppercase; letter-spacing:0.5px; }
    .hero-meta { display:flex; gap:14px; align-items:center; margin-bottom:20px; font-size:0.85rem; font-family:'JetBrains Mono',monospace; color:var(--text-muted); }
    .rating-badge { background:#eab308; color:#000; font-weight:800; padding:2px 8px; border-radius:4px; font-size:0.8rem; }
    .hero-desc { color: var(--text-muted); font-size:1.05rem; line-height:1.6; max-width:580px; margin-bottom:30px; }
    .hero-btns { display:flex; gap:16px; align-items:center; margin-bottom:40px; }
    .btn-outline { background:transparent; border:1px solid var(--card-border); color:var(--text-main); padding:10px 20px; border-radius:6px; font-weight:600; cursor:pointer; }
    .poster-rack {
      background: #081113; border: 1.5px solid #134e4a; border-radius: 14px; padding: 18px;
      display:grid; grid-template-columns: 1fr 1fr; gap:12px; box-shadow: 0 0 45px rgba(20,184,166,0.18);
    }
    .rack-card {
      border-radius:8px; height:150px; display:flex; flex-direction:column; justify-content:flex-end;
      padding:14px; font-weight:700; font-size:0.85rem; border:1px solid #115e59; cursor:pointer; transition:0.2s;
      position:relative; overflow:hidden;
    }
    .rack-card:nth-child(1) { background: linear-gradient(180deg, rgba(30,27,75,0.7) 0%, #020617 100%), radial-gradient(circle at 80% 20%, #f97316 0%, transparent 60%); }
    .rack-card:nth-child(2) { background: linear-gradient(180deg, rgba(4,47,46,0.7) 0%, #083344 100%), radial-gradient(circle at 80% 20%, #38bdf8 0%, transparent 60%); }
    .rack-card:nth-child(3) { background: linear-gradient(180deg, rgba(69,26,3,0.7) 0%, #1c1917 100%), radial-gradient(circle at 80% 20%, #f59e0b 0%, transparent 60%); }
    .rack-card:nth-child(4) { background: linear-gradient(180deg, rgba(80,7,36,0.7) 0%, #1e1b4b 100%), radial-gradient(circle at 80% 20%, #ec4899 0%, transparent 60%); }
    .rack-card:hover { border-color:#2dd4bf; transform:scale(1.03); box-shadow: 0 4px 20px rgba(45,212,191,0.25); }}
    .filter-bar { display:flex; gap:14px; margin-bottom:24px; flex-wrap:wrap; }
    .search-input { flex:1; min-width:240px; padding:10px 16px; background:var(--card-bg); border:1px solid var(--card-border); border-radius:6px; color:#fff; font-size:0.9rem; }
    .filter-select { padding:10px 16px; background:var(--card-bg); border:1px solid var(--card-border); border-radius:6px; color:#fff; font-size:0.9rem; }
    .movie-grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap:24px; margin-top:10px; }
    .movie-card {
      background: var(--card-bg); border:1px solid var(--card-border); border-radius:12px; overflow:hidden;
      transition: transform 0.2s, border-color 0.2s; display:flex; flex-direction:column;
    }
    .movie-card:hover { transform: translateY(-4px); border-color: #2b3954; }
    .poster-container {
      height: 220px; position:relative; overflow:hidden; display:flex; align-items:flex-end; padding:20px;
    }
    .poster-svg { position:absolute; top:0; left:0; width:100%; height:100%; z-index:1; object-fit:cover; }
    .poster-overlay { position:absolute; top:0; left:0; width:100%; height:100%; z-index:2; background: linear-gradient(180deg, rgba(7,9,14,0.1) 0%, rgba(7,9,14,0.85) 100%); }
    .poster-content { position:relative; z-index:3; }
    .movie-card-content { padding:20px; flex:1; display:flex; flex-direction:column; }
    .booking-layout { display:grid; grid-template-columns: 1fr 380px; gap:28px; }
    .screen-hall { background:var(--card-bg); border:1px solid var(--card-border); border-radius:12px; padding:32px; display:flex; flex-direction:column; align-items:center; }
    .curved-screen {
      width:75%; height:14px; border-top:3px solid var(--accent-blue); border-radius:50% 50% 0 0;
      margin-bottom:8px; box-shadow: 0 -8px 20px rgba(56,189,248,0.25);
    }
    .screen-label { font-size:0.75rem; letter-spacing:2px; color:var(--text-muted); font-weight:700; text-transform:uppercase; margin-bottom:30px; }
    .seats-grid { display:flex; flex-direction:column; gap:12px; margin-bottom:30px; }
    .seat-row { display:flex; align-items:center; gap:10px; }
    .row-label { width:20px; font-weight:700; color:var(--text-muted); font-size:0.85rem; font-family:'JetBrains Mono',monospace; }
    .seat-btn {
      width:38px; height:38px; border-radius:6px; border:1px solid var(--card-border);
      background: var(--seat-avail); color:var(--text-muted); font-weight:600; font-size:0.8rem;
      cursor:pointer; transition:0.15s; display:flex; align-items:center; justify-content:center;
      font-family:'JetBrains Mono',monospace;
    }
    .seat-btn:hover:not(.booked) { background:#283548; color:#fff; border-color:#475569; }
    .seat-btn.selected { background: var(--seat-selected); color:#000; font-weight:800; border-color:#059669; }
    .seat-btn.booked { background: var(--seat-booked); color:#64748b; cursor:not-allowed; opacity:0.4; }
    .seat-legend { display:flex; gap:24px; font-size:0.75rem; color:var(--text-muted); font-weight:600; text-transform:uppercase; }
    .legend-box { width:12px; height:12px; border-radius:3px; display:inline-block; margin-right:6px; vertical-align:middle; }
    .summary-card { background:var(--card-bg); border:1px solid var(--card-border); border-radius:12px; padding:28px; }
    .summary-box { background:#0a0e17; border:1px solid var(--card-border); padding:16px; border-radius:8px; margin-bottom:20px; font-family:'JetBrains Mono',monospace; font-size:1.05rem; font-weight:700; color:var(--accent-amber); min-height:54px; }
    .summary-line { display:flex; justify-content:space-between; margin-bottom:10px; font-size:0.85rem; color:var(--text-muted); }
    .total-line { display:flex; justify-content:space-between; align-items:center; padding-top:14px; border-top:1px solid var(--card-border); margin-top:14px; margin-bottom:20px; }
    .total-price { font-size:1.8rem; font-weight:800; color:var(--accent-amber); font-family:'JetBrains Mono',monospace; }
    .input-group { margin-bottom:12px; }
    .input-label { font-size:0.75rem; color:var(--text-muted); font-weight:700; text-transform:uppercase; display:block; margin-bottom:5px; }
    .input-field { width:100%; padding:10px 12px; background:#090d16; border:1px solid var(--card-border); color:#fff; border-radius:6px; font-size:0.9rem; font-family:inherit; }
    .custom-table { width:100%; border-collapse:collapse; background:var(--card-bg); border:1px solid var(--card-border); border-radius:10px; overflow:hidden; }
    .custom-table th { background:#0a0e17; text-align:left; padding:14px 20px; font-size:0.75rem; text-transform:uppercase; color:var(--text-muted); letter-spacing:1px; border-bottom:1px solid var(--card-border); }
    .custom-table td { padding:16px 20px; border-bottom:1px solid var(--card-border); font-size:0.9rem; }
    .badge-status { padding:4px 10px; border-radius:20px; font-size:0.7rem; font-weight:700; letter-spacing:0.5px; text-transform:uppercase; }
    .badge-status.showing { background:rgba(16,185,129,0.12); color:var(--accent-green); border:1px solid rgba(16,185,129,0.3); }
    .badge-status.upcoming { background:rgba(245,158,11,0.12); color:var(--accent-amber); border:1px solid rgba(245,158,11,0.3); }
    .badge-status.confirmed { background:rgba(16,185,129,0.15); color:var(--accent-green); border:1px solid rgba(16,185,129,0.4); }
    .badge-status.cancelled { background:rgba(239,68,68,0.15); color:var(--primary-red); border:1px solid rgba(239,68,68,0.4); }
    .modal-overlay {
      display:none; position:fixed; top:0; left:0; width:100%; height:100%;
      background:rgba(0,0,0,0.85); backdrop-filter:blur(4px); z-index:1000;
      align-items:center; justify-content:center;
    }
    .modal-overlay.active { display:flex; }
    .modal-box { background:#0f1523; border:1px solid var(--card-border); border-radius:14px; width:480px; padding:28px; box-shadow:0 15px 40px rgba(0,0,0,0.8); }
    .ticket-confirmation {
      background:#080c14; border:1px dashed #334155; border-radius:10px; padding:20px; margin:16px 0;
      font-family:'JetBrains Mono',monospace; font-size:0.85rem; line-height:1.7;
    }
    .alert-banner { display:none; background:#450a0a; border:1px solid #b91c1c; color:#fca5a5; padding:12px 16px; border-radius:8px; font-size:0.85rem; margin-bottom:16px; }
    .sub-tabs { display:flex; gap:16px; margin-bottom:20px; border-bottom:1px solid var(--card-border); padding-bottom:12px; }
    .sub-tab-btn { background:transparent; border:none; color:var(--text-muted); font-weight:700; font-size:0.85rem; text-transform:uppercase; cursor:pointer; padding:6px 12px; border-radius:6px; }
    .sub-tab-btn.active { background:var(--primary-red); color:#fff; }
    footer { padding:24px 40px; border-top:1px solid var(--card-border); font-size:0.75rem; color:var(--text-muted); display:flex; justify-content:space-between; font-family:'JetBrains Mono',monospace; }
  </style>
</head>
<body>

  <!-- Top Header -->
  <header>
    <div class="brand" onclick="switchTab('home')">
      <span class="brand-logo">C</span>
      <span>CINESPHERE</span>
    </div>
    <div class="nav-links">
      <span class="active" id="nav-home" onclick="switchTab('home')">HOME</span>
      <span id="nav-movies" onclick="switchTab('movies')">BROWSE MOVIES</span>
      <span id="nav-history" onclick="switchTab('history')">MY BOOKINGS</span>
    </div>
    <div class="nav-actions">
      <select class="btn-demo" onchange="switchTab(this.value)">
        <option value="" disabled selected>Demo Views</option>
        <option value="home">1. Welcome Landing Page</option>
        <option value="movies">2. Movie Browsing</option>
        <option value="detail">3. Movie & Shows Detail</option>
        <option value="seats">4. Interactive Seat Grid</option>
        <option value="history">5. My Bookings / Wallet</option>
      </select>
      <button class="btn-demo" id="auth-btn-top" onclick="openAuthModal('login')">Login</button>
      <button class="btn-red" onclick="switchTab('movies')">Book Tickets</button>
    </div>
  </header>

  <main>
    <!-- VIEW 1: WELCOME / LANDING PAGE -->
    <section id="view-home" class="view active">
      <div class="hero-container">
        <div>
          <div class="badge-pill">NOW SHOWING - ENGLISH</div>
          <h1 class="hero-title" id="hero-title">INTERSTELLAR ODYSSEY</h1>
          <div class="hero-meta">
            <span class="rating-badge">9.1 IMDB</span>
            <span>SCI-FI / ADVENTURE</span>
            <span>2H 49M</span>
          </div>
          <p class="hero-desc">
            When Earth's harvests begin to fail, a former pilot leads a covert mission through a wormhole near Saturn. What waits on the far side rewrites everything he believed about time, gravity and the people he left behind.
          </p>
          <div class="hero-btns">
            <button class="btn-red" onclick="openMovieDetail(101)">Book Tickets</button>
            <button class="btn-outline" onclick="switchTab('movies')">Browse Movies &rarr;</button>
          </div>
          <div style="font-size:0.8rem; color:var(--text-muted); display:flex; gap:14px;">
            <span>QUICK ACCESS</span>
            <span style="color:#fff; cursor:pointer;" onclick="openAuthModal('login')">&rarr;] Login</span>
            <span style="color:#fff; cursor:pointer;" onclick="openAuthModal('register')">+ Create account</span>
          </div>
        </div>
        <div class="poster-rack">
          <div class="rack-card" onclick="openMovieDetail(101)">
            <div>INTERSTELLAR ODYSSEY</div>
            <small style="color:var(--text-muted)">9.1 IMDB - SCI-FI</small>
          </div>
          <div class="rack-card" onclick="openMovieDetail(102)">
            <div>NEON VELOCITY</div>
            <small style="color:var(--text-muted)">8.7 IMDB - ACTION</small>
          </div>
          <div class="rack-card" onclick="openMovieDetail(103)">
            <div>SHADOWS OF VELVET</div>
            <small style="color:var(--text-muted)">8.4 IMDB - DRAMA</small>
          </div>
          <div class="rack-card" onclick="openMovieDetail(104)">
            <div>CHRONO PARADOX 2099</div>
            <small style="color:var(--accent-amber)">UPCOMING</small>
          </div>
        </div>
      </div>
    </section>

    <!-- VIEW 2: CUSTOMER MOVIE BROWSING -->
    <section id="view-movies" class="view">
      <div style="margin-bottom:20px;">
        <div class="badge-pill">NOW SHOWING</div>
        <h1 style="font-size:2.2rem; text-transform:uppercase;">Browse Movies</h1>
        <p style="color:var(--text-muted); font-size:0.85rem">Explore currently screening blockbusters and upcoming premieres.</p>
      </div>

      <div class="filter-bar">
        <input type="text" id="movie-search" class="search-input" placeholder="Search by title, genre, or language..." onkeyup="filterMovies()">
        <select id="genre-filter" class="filter-select" onchange="filterMovies()">
          <option value="">All Genres</option>
          <option value="Sci-Fi">Sci-Fi</option>
          <option value="Action">Action</option>
          <option value="Drama">Drama</option>
          <option value="Comedy">Comedy</option>
        </select>
        <select id="status-filter" class="filter-select" onchange="filterMovies()">
          <option value="">All Statuses</option>
          <option value="NOW SHOWING">Now Showing</option>
          <option value="UPCOMING">Upcoming</option>
        </select>
      </div>

      <div class="movie-grid" id="movie-grid-container"></div>
    </section>

    <!-- VIEW 3: MOVIE & SHOW DETAILS -->
    <section id="view-detail" class="view">
      <a href="javascript:void(0)" onclick="switchTab('movies')" style="font-size:0.85rem; color:var(--text-muted); font-weight:600; text-transform:uppercase;">&larr; Back to Movies</a>
      <div style="background:var(--card-bg); border:1px solid var(--card-border); border-radius:12px; padding:32px; margin:20px 0; display:flex; gap:36px;">
        <div style="width:200px; height:260px; background:#081820; border-radius:10px; border:1px solid #14b8a6; display:flex; align-items:center; justify-content:center; text-align:center; padding:16px; font-weight:800;" id="detail-art">CINESPHERE</div>
        <div style="flex:1;">
          <div class="badge-pill" id="detail-pill">NOW SHOWING</div>
          <h1 id="detail-title" style="font-size:2.4rem; text-transform:uppercase; margin-bottom:12px;">Movie Title</h1>
          <div class="hero-meta" style="margin-bottom:16px;">
            <span class="rating-badge" id="detail-rating">9.1</span>
            <span id="detail-genre">SCI-FI</span>
            <span id="detail-lang">ENGLISH</span>
            <span id="detail-dur">2H 45M</span>
          </div>
          <p id="detail-synopsis" style="color:var(--text-muted); line-height:1.6; max-width:700px; margin-bottom:24px;"></p>
        </div>
      </div>

      <h3 style="font-size:1.1rem; text-transform:uppercase; letter-spacing:1px; margin-bottom:16px;">Available Screens & Timings</h3>
      <div>
        <div style="background:#0a0f19; border:1px solid var(--card-border); padding:18px 24px; border-radius:8px; display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;">
          <div>
            <div style="font-weight:700; margin-bottom:4px;">SCREEN 1 (4K Laser Dolby Atmos)</div>
            <div style="font-size:0.85rem; color:var(--text-muted)">FRI, OCT 23, 2026 - 6:30 PM - 9:20 PM</div>
            <div style="color:var(--accent-amber); font-weight:700; font-family:'JetBrains Mono',monospace; margin-top:6px;" id="show-price-1">$12.00 / SEAT</div>
          </div>
          <button class="btn-red" onclick="openSeatSelection('SCREEN 1', 'FRI, OCT 23, 2026 - 6:30 PM')">Select Seats</button>
        </div>
        <div style="background:#0a0f19; border:1px solid var(--card-border); padding:18px 24px; border-radius:8px; display:flex; justify-content:space-between; align-items:center; margin-bottom:14px;">
          <div>
            <div style="font-weight:700; margin-bottom:4px;">SCREEN 2 (Standard Auditorium)</div>
            <div style="font-size:0.85rem; color:var(--text-muted)">SAT, OCT 24, 2026 - 2:00 PM - 4:50 PM</div>
            <div style="color:var(--accent-amber); font-weight:700; font-family:'JetBrains Mono',monospace; margin-top:6px;" id="show-price-2">$12.00 / SEAT</div>
          </div>
          <button class="btn-red" onclick="openSeatSelection('SCREEN 2', 'SAT, OCT 24, 2026 - 2:00 PM')">Select Seats</button>
        </div>
      </div>
    </section>

    <!-- VIEW 4: SEAT SELECTION & LIVE ORDER SUMMARY -->
    <section id="view-seats" class="view">
      <a href="javascript:void(0)" onclick="switchTab('detail')" style="font-size:0.85rem; color:var(--text-muted); font-weight:600; text-transform:uppercase;">&larr; Back to Shows</a>
      <div style="margin-top:14px; margin-bottom:20px;">
        <div class="badge-pill" id="seat-movie-badge">SCREEN 1</div>
        <h1 id="seat-movie-title" style="font-size:2.2rem; text-transform:uppercase;">Movie Title</h1>
        <div style="font-size:0.85rem; color:var(--text-muted); margin-top:4px;" id="seat-hall-sub">SCREEN 1 - 4 Rows (A-D) x 10 Seats</div>
      </div>

      <div class="alert-banner" id="seat-error-banner"></div>

      <div class="booking-layout">
        <div class="screen-hall">
          <div class="curved-screen"></div>
          <div class="screen-label">SCREEN 1 &bull; 4K DOLBY ATMOS &bull; ALL EYES THIS WAY</div>
          <div class="seats-grid" id="seats-grid-container"></div>
          <div class="seat-legend">
            <div><span class="legend-box" style="background:var(--seat-avail)"></span> Available</div>
            <div><span class="legend-box" style="background:var(--seat-selected)"></span> Selected</div>
            <div><span class="legend-box" style="background:var(--seat-booked)"></span> Reserved</div>
          </div>
        </div>

        <div class="summary-card">
          <h3 style="font-size:1.1rem; text-transform:uppercase; letter-spacing:1px; margin-bottom:16px;">Order Summary</h3>
          <div style="font-size:0.75rem; text-transform:uppercase; color:var(--text-muted); font-weight:700; margin-bottom:6px;">Selected Seats</div>
          <div class="summary-box" id="summary-seat-box">None selected</div>

          <div class="summary-line">
            <span id="summary-ticket-count">Tickets &times; 0</span>
            <span id="summary-subtotal" style="font-family:'JetBrains Mono',monospace;">$0.00</span>
          </div>
          <div class="summary-line">
            <span>Convenience Fee (5%)</span>
            <span id="summary-fee" style="font-family:'JetBrains Mono',monospace;">$0.00</span>
          </div>

          <div class="total-line">
            <span style="font-weight:700; text-transform:uppercase;">Total</span>
            <span class="total-price" id="summary-total-val">$0.00</span>
          </div>

          <div class="input-group">
            <label class="input-label">Customer Name</label>
            <input type="text" id="cust-name-input" value="Anil Kumar" class="input-field" placeholder="Full name">
          </div>
          <div class="input-group">
            <label class="input-label">Email Address</label>
            <input type="email" id="cust-email-input" value="anil.kumar@gmail.com" class="input-field" placeholder="Email">
          </div>
          <div class="input-group" style="margin-bottom:20px;">
            <label class="input-label">Phone Number</label>
            <input type="tel" id="cust-phone-input" value="+1 555-0199" class="input-field" placeholder="Phone">
          </div>

          <button class="btn-red" style="width:100%; padding:14px; font-size:1rem;" onclick="confirmBooking()">Confirm Booking</button>
        </div>
      </div>
    </section>

    <!-- VIEW 5: BOOKING HISTORY / TICKET WALLET -->
    <section id="view-history" class="view">
      <div style="margin-bottom:24px;">
        <div class="badge-pill">TICKET WALLET</div>
        <h1 style="font-size:2.2rem; text-transform:uppercase;">Booking History</h1>
        <p style="color:var(--text-muted); font-size:0.85rem">Every reservation made on your account. Cancelling releases seats back to the hall.</p>
      </div>

      <table class="custom-table">
        <thead>
          <tr>
            <th>Booking ID</th>
            <th>Customer</th>
            <th>Movie</th>
            <th>Theatre</th>
            <th>Show Date & Time</th>
            <th>Seats</th>
            <th>Amount</th>
            <th>Status</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody id="history-tbody"></tbody>
      </table>
    </section>
  </main>

  <!-- AUTHENTICATION MODAL -->
  <div class="modal-overlay" id="auth-modal">
    <div class="modal-box">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:20px;">
        <div class="sub-tabs" style="border:none; margin:0; padding:0;">
          <button class="sub-tab-btn active" id="auth-tab-login" onclick="switchAuthTab('login')">Login</button>
          <button class="sub-tab-btn" id="auth-tab-reg" onclick="switchAuthTab('reg')">Register</button>
        </div>
        <span style="cursor:pointer; font-size:1.4rem;" onclick="closeAuthModal()">&times;</span>
      </div>

      <div id="form-login">
        <div class="input-group">
          <label class="input-label">Username</label>
          <input type="text" id="login-user" value="anil" class="input-field">
        </div>
        <div class="input-group" style="margin-bottom:20px;">
          <label class="input-label">Password</label>
          <input type="password" value="••••••••" class="input-field">
        </div>
        <button class="btn-red" style="width:100%;" onclick="handleLogin()">Sign In</button>
      </div>

      <div id="form-reg" style="display:none;">
        <div class="input-group">
          <label class="input-label">Username</label>
          <input type="text" id="reg-user" class="input-field" placeholder="Choose a username">
        </div>
        <div class="input-group">
          <label class="input-label">Email Address</label>
          <input type="email" id="reg-email" class="input-field" placeholder="you@domain.com">
        </div>
        <div class="input-group" style="margin-bottom:20px;">
          <label class="input-label">Password</label>
          <input type="password" id="reg-pass" class="input-field" placeholder="Enter password (min 6 chars)">
        </div>
        <button class="btn-red" style="width:100%;" onclick="handleRegister()">Create Account</button>
      </div>
    </div>
  </div>

  <!-- CONFIRMED TICKET MODAL -->
  <div class="modal-overlay" id="ticket-modal">
    <div class="modal-box">
      <h3 style="text-transform:uppercase; margin-bottom:6px; color:var(--accent-green);">&#10003; Booking Confirmed!</h3>
      <div style="font-size:0.75rem; color:var(--text-muted); text-transform:uppercase; letter-spacing:1px; margin-bottom:12px;">
        Official Reservation Invoice
      </div>
      <div class="ticket-confirmation" id="ticket-modal-body"></div>
      <div style="display:flex; justify-content:flex-end; gap:12px;">
        <button class="btn-red" onclick="closeTicketModal()">Go to My Bookings</button>
      </div>
    </div>
  </div>

  <!-- CANCELLATION MODAL -->
  <div class="modal-overlay" id="cancel-modal">
    <div class="modal-box">
      <h3 style="text-transform:uppercase; margin-bottom:6px;">Cancel this booking?</h3>
      <div style="font-size:0.75rem; color:var(--text-muted); text-transform:uppercase; letter-spacing:1px; margin-bottom:18px;">
        The seats go straight back on sale — this cannot be undone
      </div>
      <div id="modal-preview-body" style="background:#090d16; border:1px solid var(--card-border); padding:16px; border-radius:8px; margin-bottom:20px; font-family:'JetBrains Mono',monospace; font-size:0.85rem;"></div>
      <div style="display:flex; justify-content:flex-end; gap:12px;">
        <button class="btn-demo" onclick="closeCancelModal()">Keep Booking</button>
        <button class="btn-red" id="modal-confirm-btn">Confirm Cancellation</button>
      </div>
    </div>
  </div>

  <footer>
    <div>CINESPHERE — MOVIE TICKET BOOKING SYSTEM</div>
    <div>ALL RIGHTS RESERVED</div>
  </footer>

  <script>
    const posters = {
      101: `<svg class="poster-svg" viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g101" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#1e1b4b"/><stop offset="50%" stop-color="#431407"/><stop offset="100%" stop-color="#020617"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g101)"/><circle cx="200" cy="180" r="80" fill="#f97316" opacity="0.8"/><circle cx="200" cy="180" r="120" fill="#fbbf24" opacity="0.2"/><path d="M0,240 Q100,200 200,240 T400,240 L400,300 L0,300 Z" fill="#0f172a"/></svg>`,
      102: `<svg class="poster-svg" viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g102" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#042f2e"/><stop offset="50%" stop-color="#0f172a"/><stop offset="100%" stop-color="#083344"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g102)"/><circle cx="300" cy="90" r="45" fill="#38bdf8" opacity="0.7"/><polygon points="200,80 230,160 170,160" fill="#059669" opacity="0.9"/></svg>`,
      103: `<svg class="poster-svg" viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g103" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#451a03"/><stop offset="50%" stop-color="#78350f"/><stop offset="100%" stop-color="#1c1917"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g103)"/><circle cx="150" cy="120" r="50" fill="#eab308" opacity="0.3"/><circle cx="250" cy="140" r="60" fill="#f59e0b" opacity="0.3"/></svg>`,
      104: `<svg class="poster-svg" viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice"><defs><linearGradient id="g104" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#500724"/><stop offset="50%" stop-color="#030712"/><stop offset="100%" stop-color="#1e1b4b"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g104)"/><line x1="50" y1="300" x2="180" y2="100" stroke="#ec4899" stroke-width="3" opacity="0.8"/><rect x="180" y="80" width="40" height="180" fill="#090d16" stroke="#f43f5e" stroke-width="1.5"/></svg>`
    };

    let movies = [];
    let activeMovie = null;
    let selectedSeats = new Set();
    let seatMatrix = [];
    let currentScreen = "SCREEN 1";
    let currentShowTime = "FRI, OCT 23, 2026 - 6:30 PM";
    let pendingCancelTicketId = null;

    function switchTab(viewId) {
      document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
      document.querySelectorAll('.nav-links span').forEach(s => s.classList.remove('active'));
      const target = document.getElementById('view-' + viewId);
      if (target) target.classList.add('active');
      const nav = document.getElementById('nav-' + viewId);
      if (nav) nav.classList.add('active');
      window.scrollTo(0, 0);

      if (viewId === 'movies') renderMovieGrid(movies);
      if (viewId === 'history') loadHistory();
    }

    async function loadMovies() {
      try {
        const res = await fetch('/api/movies');
        movies = await res.json();
        if (movies.length > 0) activeMovie = movies[0];
        renderMovieGrid(movies);
        loadHistory();
      } catch (e) {
        console.error("Error loading movies:", e);
      }
    }

    function renderMovieGrid(items) {
      const container = document.getElementById('movie-grid-container');
      container.innerHTML = '';
      items.forEach(m => {
        const isUp = m.status === 'UPCOMING';
        const card = document.createElement('div');
        card.className = 'movie-card';
        card.innerHTML = `
          <div class="poster-container">
            ${posters[m.movieId] || posters[101]}
            <div class="poster-overlay"></div>
            <div class="poster-content">
              <div style="font-size:0.75rem; font-weight:700; color:var(--text-muted); text-transform:uppercase;">ID: ${m.movieId}</div>
              <h3 style="font-size:1.3rem; text-transform:uppercase; font-weight:800; color:#fff;">${m.title}</h3>
            </div>
          </div>
          <div class="movie-card-content">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px;">
              <span class="badge-status ${isUp ? 'upcoming' : 'showing'}">${m.status}</span>
              <span style="font-weight:700; color:var(--accent-amber); font-family:'JetBrains Mono',monospace;">★ ${m.rating}</span>
            </div>
            <div style="font-size:0.8rem; color:var(--text-muted); margin-bottom:12px;">${m.genre} &bull; ${m.language} &bull; ${m.duration}</div>
            <p style="font-size:0.85rem; color:var(--text-muted); line-height:1.5; margin-bottom:20px; flex:1;">${m.synopsis}</p>
            <div style="display:flex; justify-content:space-between; align-items:center; padding-top:14px; border-top:1px solid var(--card-border);">
              <div>
                <div style="font-size:0.7rem; color:var(--text-muted); text-transform:uppercase;">Base Price</div>
                <div style="font-weight:700; font-family:'JetBrains Mono',monospace; color:var(--accent-amber);">$${m.basePrice.toFixed(2)}</div>
              </div>
              <button class="btn-red" onclick="openMovieDetail(${m.movieId})">Select</button>
            </div>
          </div>
        `;
        container.appendChild(card);
      });
    }

    function filterMovies() {
      const q = document.getElementById('movie-search').value.toLowerCase();
      const genre = document.getElementById('genre-filter').value;
      const status = document.getElementById('status-filter').value;

      const filtered = movies.filter(m => {
        const matchesQuery = m.title.toLowerCase().includes(q) || m.genre.toLowerCase().includes(q) || m.language.toLowerCase().includes(q);
        const matchesGenre = !genre || m.genre.includes(genre);
        const matchesStatus = !status || m.status === status;
        return matchesQuery && matchesGenre && matchesStatus;
      });
      renderMovieGrid(filtered);
    }

    function openMovieDetail(movieId) {
      activeMovie = movies.find(m => m.movieId === movieId) || movies[0];
      document.getElementById('detail-title').innerText = activeMovie.title;
      document.getElementById('detail-genre').innerText = activeMovie.genre;
      document.getElementById('detail-lang').innerText = activeMovie.language;
      document.getElementById('detail-dur').innerText = activeMovie.duration;
      document.getElementById('detail-rating').innerText = activeMovie.rating;
      document.getElementById('detail-synopsis').innerText = activeMovie.synopsis;
      document.getElementById('detail-art').innerText = activeMovie.title;
      document.getElementById('show-price-1').innerText = '$' + activeMovie.basePrice.toFixed(2) + ' / SEAT';
      document.getElementById('show-price-2').innerText = '$' + activeMovie.basePrice.toFixed(2) + ' / SEAT';
      switchTab('detail');
    }

    async function openSeatSelection(screen, time) {
      currentScreen = screen;
      currentShowTime = time;
      selectedSeats.clear();
      hideError();
      updateBreakdown();

      document.getElementById('seat-movie-title').innerText = activeMovie.title;
      document.getElementById('seat-hall-sub').innerText = screen + ' &bull; ' + time + ' &bull; $' + activeMovie.basePrice.toFixed(2) + ' PER SEAT';

      await loadSeatMatrix(activeMovie.movieId);
      switchTab('seats');
    }

    async function loadSeatMatrix(movieId) {
      try {
        const res = await fetch('/api/theatre?movieId=' + movieId);
        const data = await res.json();
        seatMatrix = data.seats;
        renderSeatGrid();
      } catch (e) {
        console.error("Error loading seat matrix:", e);
      }
    }

    function renderSeatGrid() {
      const container = document.getElementById('seats-grid-container');
      container.innerHTML = '';
      const rowLabels = ['A', 'B', 'C', 'D'];

      for (let r = 0; r < seatMatrix.length; r++) {
        const rowDiv = document.createElement('div');
        rowDiv.className = 'seat-row';

        const label = document.createElement('div');
        label.className = 'row-label';
        label.innerText = rowLabels[r];
        rowDiv.appendChild(label);

        for (let c = 0; c < seatMatrix[r].length; c++) {
          const seatCode = rowLabels[r] + (c + 1);
          const isBooked = seatMatrix[r][c] === 1;
          const isSelected = selectedSeats.has(seatCode);

          const seatBtn = document.createElement('button');
          seatBtn.className = 'seat-btn' + (isBooked ? ' booked' : '') + (isSelected ? ' selected' : '');
          seatBtn.innerText = (c + 1);

          if (!isBooked) {
            seatBtn.onclick = () => toggleSeat(seatCode);
          } else {
            seatBtn.onclick = () => showError(`Seat ${seatCode} is already booked! (SeatNotAvailableException)`);
          }
          rowDiv.appendChild(seatBtn);
        }
        container.appendChild(rowDiv);
      }
    }

    function toggleSeat(seatCode) {
      hideError();
      if (selectedSeats.has(seatCode)) selectedSeats.delete(seatCode);
      else selectedSeats.add(seatCode);
      renderSeatGrid();
      updateBreakdown();
    }

    function updateBreakdown() {
      const seatBox = document.getElementById('summary-seat-box');
      if (selectedSeats.size === 0) {
        seatBox.innerText = 'None selected';
        document.getElementById('summary-ticket-count').innerText = 'Tickets \u00D7 0';
        document.getElementById('summary-subtotal').innerText = '$0.00';
        document.getElementById('summary-fee').innerText = '$0.00';
        document.getElementById('summary-total-val').innerText = '$0.00';
        return;
      }

      const arr = Array.from(selectedSeats).sort();
      seatBox.innerText = arr.join(', ');

      const subtotal = arr.length * activeMovie.basePrice;
      const fee = Math.round((subtotal * 0.05) * 100) / 100;
      const total = subtotal + fee;

      document.getElementById('summary-ticket-count').innerText = `Tickets \u00D7 ${arr.length}`;
      document.getElementById('summary-subtotal').innerText = `$${subtotal.toFixed(2)}`;
      document.getElementById('summary-fee').innerText = `$${fee.toFixed(2)}`;
      document.getElementById('summary-total-val').innerText = `$${total.toFixed(2)}`;
    }

    async function confirmBooking() {
      if (selectedSeats.size === 0) {
        showError("Please select at least one seat.");
        return;
      }
      const custName = document.getElementById('cust-name-input').value.trim();
      const email = document.getElementById('cust-email-input').value.trim();
      const phone = document.getElementById('cust-phone-input').value.trim();

      if (!custName) {
        showError("Please enter your name.");
        return;
      }

      const body = new URLSearchParams({
        movieId: activeMovie.movieId.toString(),
        customerName: custName,
        email: email,
        phone: phone,
        seats: Array.from(selectedSeats).join(','),
        screen: currentScreen,
        showTime: currentShowTime
      });

      try {
        const res = await fetch('/api/book', { method: 'POST', body: body.toString() });
        const data = await res.json();
        if (data.success) {
          showTicketModal(data);
          selectedSeats.clear();
        } else {
          showError(`[${data.exception || 'Error'}]: ${data.message}`);
          await loadSeatMatrix(activeMovie.movieId);
        }
      } catch (e) {
        showError("Failed to communicate with Java backend.");
      }
    }

    function showTicketModal(data) {
      const modalBody = document.getElementById('ticket-modal-body');
      modalBody.innerHTML = `
        <div style="color:var(--accent-amber); font-weight:700;">BOOKING ID: ${data.ticketId}</div>
        <div style="font-weight:700; color:#fff; font-size:1.1rem; margin:6px 0;">${data.movieTitle}</div>
        <div>CUSTOMER: <b>${data.customerName}</b></div>
        <div>SEATS RESERVED: <b>${data.seats}</b></div>
        <div style="font-size:1.1rem; font-weight:800; color:var(--accent-amber); margin-top:8px;">TOTAL: $${data.finalAmount.toFixed(2)}</div>
        <div style="color:var(--text-muted); font-size:0.75rem; margin-top:4px;">TIME: ${data.bookingTime}</div>
      `;
      document.getElementById('ticket-modal').classList.add('active');
    }

    function closeTicketModal() {
      document.getElementById('ticket-modal').classList.remove('active');
      switchTab('history');
    }

    async function loadHistory() {
      try {
        const res = await fetch('/api/history');
        const list = await res.json();
        const tbody = document.getElementById('history-tbody');
        tbody.innerHTML = '';

        if (list.length === 0) {
          tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; color:var(--text-muted);">No booking transactions recorded.</td></tr>';
          return;
        }

        list.forEach(t => {
          const isConf = t.status === 'CONFIRMED';
          const tr = document.createElement('tr');
          tr.innerHTML = `
            <td style="font-family:'JetBrains Mono',monospace; font-weight:700; color:var(--accent-amber);">${t.ticketId}</td>
            <td style="font-weight:600;">${t.customerName}</td>
            <td>${t.movieTitle}</td>
            <td>${t.theatre}</td>
            <td style="font-size:0.85rem; color:var(--text-muted);">${t.show}</td>
            <td style="font-family:'JetBrains Mono',monospace; font-weight:700;">${t.seats}</td>
            <td style="font-family:'JetBrains Mono',monospace; font-weight:700; color:var(--accent-amber);">$${t.finalAmount.toFixed(2)}</td>
            <td><span class="badge-status ${isConf ? 'confirmed' : 'cancelled'}">${t.status}</span></td>
            <td>
              ${isConf ? `<button class="btn-demo" style="color:var(--primary-red); border-color:#451a1a;" onclick="promptCancellation('${t.ticketId}', '${t.movieTitle}', '${t.theatre}', '${t.show}', '${t.seats}', ${t.finalAmount})">Cancel Booking</button>` : `<span style="font-size:0.75rem; color:var(--text-muted);">Released</span>`}
            </td>
          `;
          tbody.appendChild(tr);
        });
      } catch (e) {
        console.error("Error loading history:", e);
      }
    }

    function promptCancellation(ticketId, movie, theatre, show, seats, amount) {
      pendingCancelTicketId = ticketId;
      const preview = document.getElementById('modal-preview-body');
      preview.innerHTML = `
        <div style="color:var(--accent-amber); font-weight:700; margin-bottom:4px;">${ticketId}</div>
        <div style="font-size:1.05rem; font-weight:800; color:#fff; margin-bottom:4px;">${movie}</div>
        <div style="color:var(--text-muted); font-size:0.8rem;">${theatre} &bull; ${show}</div>
        <div style="margin-top:6px; font-weight:700;">SEATS: ${seats} &bull; $${amount.toFixed(2)}</div>
      `;
      document.getElementById('modal-confirm-btn').onclick = executeCancellation;
      document.getElementById('cancel-modal').classList.add('active');
    }

    function closeCancelModal() {
      document.getElementById('cancel-modal').classList.remove('active');
      pendingCancelTicketId = null;
    }

    async function executeCancellation() {
      if (!pendingCancelTicketId) return;
      try {
        const res = await fetch('/api/cancel', {
          method: 'POST',
          body: new URLSearchParams({ ticketId: pendingCancelTicketId }).toString()
        });
        const data = await res.json();
        if (data.success) {
          closeCancelModal();
          loadHistory();
        } else {
          alert(data.message);
        }
      } catch (e) {
        alert("Failed to cancel ticket.");
      }
    }

    function openAuthModal(tab) {
      document.getElementById('auth-modal').classList.add('active');
      switchAuthTab(tab);
    }
    function closeAuthModal() {
      document.getElementById('auth-modal').classList.remove('active');
    }
    function switchAuthTab(tab) {
      document.getElementById('form-login').style.display = tab === 'login' ? 'block' : 'none';
      document.getElementById('form-reg').style.display = tab === 'reg' ? 'block' : 'none';
      document.getElementById('auth-tab-login').className = 'sub-tab-btn' + (tab === 'login' ? ' active' : '');
      document.getElementById('auth-tab-reg').className = 'sub-tab-btn' + (tab === 'reg' ? ' active' : '');
    }
    function handleLogin() {
      const user = document.getElementById('login-user').value.trim() || 'User';
      closeAuthModal();
      alert(`Welcome back, ${user}!`);
      switchTab('movies');
    }
    function handleRegister() {
      const user = document.getElementById('reg-user').value.trim();
      const email = document.getElementById('reg-email').value.trim();
      const pass = document.getElementById('reg-pass').value;
      if (!user || !email || pass.length < 6) {
        alert('Please fill all fields. Password must be at least 6 characters.');
        return;
      }
      closeAuthModal();
      alert(`Account created for ${user}! You can now book tickets.`);
    }

    function showError(msg) {
      const banner = document.getElementById('seat-error-banner');
      banner.innerText = msg;
      banner.style.display = 'block';
    }
    function hideError() {
      document.getElementById('seat-error-banner').style.display = 'none';
    }

    window.onload = loadMovies;
  </script>
</body>
</html>
""";
        }
    }
}

// =========================================================================
// 2. DOMAIN MODEL & OOP CLASSES
// =========================================================================
class SeatNotAvailableException extends Exception {
    public SeatNotAvailableException(String message) {
        super(message);
    }
}

interface BookingOperations {
    void displaySeats();
    void bookSeat(int row, int col) throws SeatNotAvailableException;
    void cancelSeat(int row, int col);
    boolean isSeatBooked(int row, int col);
}

abstract class Movie {
    private int movieId;
    private String title;
    private String genre;
    private double basePrice;
    private String language;
    private String duration;
    private double rating;
    private String synopsis;
    private String status;

    public Movie(int movieId, String title, String genre, double basePrice, 
                 String language, String duration, double rating, String synopsis, String status) {
        this.movieId = movieId;
        this.title = title;
        this.genre = genre;
        this.basePrice = basePrice;
        this.language = language;
        this.duration = duration;
        this.rating = rating;
        this.synopsis = synopsis;
        this.status = status;
    }

    public int getMovieId() { return movieId; }
    public String getTitle() { return title; }
    public String getGenre() { return genre; }
    public double getBasePrice() { return basePrice; }
    public String getLanguage() { return language; }
    public String getDuration() { return duration; }
    public double getRating() { return rating; }
    public String getSynopsis() { return synopsis; }
    public String getStatus() { return status; }

    public abstract double calculateTicketPrice(String seatRow);
    public abstract String getMovieType();
    public abstract double getPremiumSurcharge();
}

class RegularMovie extends Movie {
    public RegularMovie(int movieId, String title, String genre, double basePrice,
                        String language, String duration, double rating, String synopsis, String status) {
        super(movieId, title, genre, basePrice, language, duration, rating, synopsis, status);
    }

    @Override
    public double calculateTicketPrice(String seatRow) {
        return getBasePrice();
    }

    @Override
    public String getMovieType() { return "RegularMovie"; }

    @Override
    public double getPremiumSurcharge() { return 0.0; }
}

class PremiumMovie extends Movie {
    private static final double PREMIUM_FORMAT_CHARGE = 100.0;

    public PremiumMovie(int movieId, String title, String genre, double basePrice,
                        String language, String duration, double rating, String synopsis, String status) {
        super(movieId, title, genre, basePrice, language, duration, rating, synopsis, status);
    }

    @Override
    public double calculateTicketPrice(String seatRow) {
        return getBasePrice();
    }

    @Override
    public String getMovieType() { return "PremiumMovie"; }

    @Override
    public double getPremiumSurcharge() { return PREMIUM_FORMAT_CHARGE; }
}

class Customer {
    private String name;
    private String email;
    private String phone;

    public Customer(String name, String email, String phone) {
        this.name = (name == null || name.trim().isEmpty()) ? "Customer" : name.trim();
        this.email = (email == null || email.trim().isEmpty()) ? "guest@cinesphere.com" : email.trim();
        this.phone = (phone == null || phone.trim().isEmpty()) ? "+1 555-0199" : phone.trim();
    }

    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
}

class Theatre implements BookingOperations {
    private String theatreName;
    private int rows;
    private int columns;
    private boolean[][] seats;

    public Theatre(String theatreName, int rows, int columns) {
        this.theatreName = theatreName;
        this.rows = rows;
        this.columns = columns;
        this.seats = new boolean[rows][columns];
    }

    public String getTheatreName() { return theatreName; }
    public int getRows() { return rows; }
    public int getColumns() { return columns; }

    @Override
    public synchronized boolean isSeatBooked(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= columns) return true;
        return seats[row][col];
    }

    @Override
    public synchronized void bookSeat(int row, int col) throws SeatNotAvailableException {
        if (row < 0 || row >= rows || col < 0 || col >= columns) {
            throw new SeatNotAvailableException("Invalid seat index! Out of range.");
        }
        if (seats[row][col]) {
            char rowChar = (char) ('A' + row);
            throw new SeatNotAvailableException("Seat " + rowChar + (col + 1) + " is already reserved!");
        }
        seats[row][col] = true;
    }

    @Override
    public synchronized void cancelSeat(int row, int col) {
        if (row >= 0 && row < rows && col >= 0 && col < columns) {
            seats[row][col] = false;
        }
    }

    @Override
    public void displaySeats() {
        System.out.println("Theatre seating status verified for " + theatreName);
    }
}

class Ticket {
    private String ticketId;
    private Customer customer;
    private Movie movie;
    private Theatre theatre;
    private String screen;
    private String showTime;
    private List<String> seats;
    private double subtotal;
    private double convenienceFee;
    private double finalAmount;
    private String bookingTime;
    private String status;

    public Ticket(String ticketId, Customer customer, Movie movie, Theatre theatre,
                  String screen, String showTime, List<String> seats,
                  double subtotal, double convenienceFee, double finalAmount, String status) {
        this.ticketId = ticketId;
        this.customer = customer;
        this.movie = movie;
        this.theatre = theatre;
        this.screen = screen;
        this.showTime = showTime;
        this.seats = seats;
        this.subtotal = subtotal;
        this.convenienceFee = convenienceFee;
        this.finalAmount = finalAmount;
        this.status = status;
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        this.bookingTime = dtf.format(LocalDateTime.now());
    }

    public String getTicketId() { return ticketId; }
    public Customer getCustomer() { return customer; }
    public Movie getMovie() { return movie; }
    public Theatre getTheatre() { return theatre; }
    public String getScreen() { return screen; }
    public String getShowTime() { return showTime; }
    public List<String> getSeats() { return seats; }
    public double getSubtotal() { return subtotal; }
    public double getConvenienceFee() { return convenienceFee; }
    public double getFinalAmount() { return finalAmount; }
    public String getBookingTime() { return bookingTime; }
    public void setBookingTime(String bookingTime) { this.bookingTime = bookingTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String toFileRecord() {
        return ticketId + "|" + customer.getName() + "|" + customer.getEmail() + "|" + customer.getPhone() + "|" +
                movie.getTitle() + "|" + screen + "|" + showTime + "|" +
                String.join(",", seats) + "|" + subtotal + "|" + convenienceFee + "|" +
                finalAmount + "|" + status + "|" + bookingTime;
    }
}

class AppState {
    private static final String HISTORY_FILE = "booking_history.txt";
    public static final Map<Integer, Movie> movieCatalog = new LinkedHashMap<>();
    public static final Map<Integer, Theatre> theatreMap = new ConcurrentHashMap<>();
    public static final Map<String, Ticket> ticketStore = new ConcurrentHashMap<>();
    public static final List<Map<String, String>> schedules = new ArrayList<>();

    static {
        movieCatalog.put(101, new PremiumMovie(101, "Interstellar Odyssey", "Sci-Fi", 12.00,
                "English", "2h 49m", 9.1,
                "When Earth's harvests begin to fail, a former pilot leads a covert mission through a wormhole near Saturn. What waits on the far side rewrites everything he believed about time, gravity and the people he left behind.",
                "NOW SHOWING"));

        movieCatalog.put(102, new RegularMovie(102, "Neon Velocity: Cyber Chase", "Action", 12.00,
                "English", "2h 08m", 8.7,
                "In a neon-drenched metropolis, an elite cyber operative fights corporate syndicates for human freedom and decrypted neural keys.",
                "NOW SHOWING"));

        movieCatalog.put(103, new RegularMovie(103, "Shadows of the Velvet Room", "Drama", 12.00,
                "Hindi", "2h 21m", 8.4,
                "An undercover investigator unravels decades of aristocratic secrets buried deep inside the premier opera houses of Mumbai.",
                "NOW SHOWING"));

        movieCatalog.put(104, new PremiumMovie(104, "Chrono Paradox 2099", "Sci-Fi", 12.00,
                "English", "2h 32m", 8.9,
                "Temporal agents attempt to undo an apocalyptic cascade before the multi-threaded timelines irrevocably collapse.",
                "UPCOMING"));

        for (Integer id : movieCatalog.keySet()) {
            theatreMap.put(id, new Theatre("SCREEN 1", 4, 10));
        }

        Theatre t1 = theatreMap.get(101);
        try {
            t1.bookSeat(2, 7); // C8
            t1.bookSeat(2, 8); // C9
            Ticket seed = new Ticket("CN-9C9715", new Customer("Anil Kumar", "anil@gmail.com", "+1 555-0199"),
                    movieCatalog.get(101), t1, "SCREEN 1", "FRI, OCT 23, 2026 - 6:30 PM",
                    Arrays.asList("C8", "C9"), 24.00, 1.20, 25.20, "CONFIRMED");
            ticketStore.put("CN-9C9715", seed);
        } catch (Exception ignored) {}

        restoreHistory();
    }

    public static synchronized Ticket bookMultiple(int movieId, String custName, String email, String phone, String seatsStr, String screen, String showTime)
            throws SeatNotAvailableException {
        Movie movie = movieCatalog.get(movieId);
        Theatre theatre = theatreMap.get(movieId);
        if (movie == null || theatre == null) throw new IllegalArgumentException("Invalid movie selected.");

        String[] seatCodes = seatsStr.split(",");
        List<String> bookedSeats = new ArrayList<>();
        List<int[]> coords = new ArrayList<>();

        double subtotal = 0.0;
        try {
            for (String s : seatCodes) {
                s = s.trim().toUpperCase();
                if (s.isEmpty()) continue;
                char rChar = s.charAt(0);
                int r = rChar - 'A';
                int c = Integer.parseInt(s.substring(1)) - 1;

                theatre.bookSeat(r, c);
                coords.add(new int[]{r, c});
                bookedSeats.add(s);

                subtotal += movie.getBasePrice();
            }
        } catch (SeatNotAvailableException e) {
            for (int[] p : coords) {
                theatre.cancelSeat(p[0], p[1]);
            }
            throw e;
        }

        double fee = Math.round((subtotal * 0.05) * 100.0) / 100.0;
        double finalPrice = subtotal + fee;

        String ticketId = "CN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        Customer customer = new Customer(custName, email, phone);
        Ticket ticket = new Ticket(ticketId, customer, movie, theatre, screen, showTime,
                bookedSeats, subtotal, fee, finalPrice, "CONFIRMED");

        ticketStore.put(ticketId, ticket);
        appendToFile(ticket);
        return ticket;
    }

    public static synchronized boolean cancel(String ticketId) {
        Ticket ticket = ticketStore.get(ticketId);
        if (ticket == null || "CANCELLED".equalsIgnoreCase(ticket.getStatus())) return false;

        for (String seat : ticket.getSeats()) {
            int r = seat.charAt(0) - 'A';
            int c = Integer.parseInt(seat.substring(1)) - 1;
            ticket.getTheatre().cancelSeat(r, c);
        }

        ticket.setStatus("CANCELLED");
        rewriteFile();
        return true;
    }

    private static synchronized void appendToFile(Ticket t) {
        try (FileWriter fw = new FileWriter(HISTORY_FILE, true); PrintWriter pw = new PrintWriter(fw)) {
            pw.println(t.toFileRecord());
        } catch (IOException e) {
            System.err.println("File write error: " + e.getMessage());
        }
    }

    private static synchronized void rewriteFile() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(HISTORY_FILE, false))) {
            for (Ticket t : ticketStore.values()) {
                pw.println(t.toFileRecord());
            }
        } catch (IOException e) {
            System.err.println("File rewrite error: " + e.getMessage());
        }
    }

    private static synchronized void restoreHistory() {
        File f = new File(HISTORY_FILE);
        if (!f.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\\|");
                if (p.length >= 12) {
                    String tId = p[0];
                    String cName = p[1];
                    String email = p[2];
                    String phone = p[3];
                    String mTitle = p[4];
                    String screen = p[5];
                    String show = p[6];
                    List<String> seats = Arrays.asList(p[7].split(","));
                    double subtotal = Double.parseDouble(p[8]);
                    double fee = Double.parseDouble(p[9]);
                    double amt = Double.parseDouble(p[10]);
                    String status = p[11];
                    String time = p.length > 12 ? p[12] : "";

                    Movie matched = null;
                    for (Movie m : movieCatalog.values()) {
                        if (m.getTitle().equalsIgnoreCase(mTitle)) { matched = m; break; }
                    }
                    if (matched == null) matched = movieCatalog.get(101);

                    Theatre theatre = theatreMap.get(matched.getMovieId());
                    Customer cust = new Customer(cName, email, phone);
                    Ticket t = new Ticket(tId, cust, matched, theatre, screen, show, seats, subtotal, fee, amt, status);
                    t.setBookingTime(time);
                    ticketStore.put(tId, t);

                    if ("CONFIRMED".equalsIgnoreCase(status)) {
                        for (String seat : seats) {
                            int r = seat.charAt(0) - 'A';
                            int c = Integer.parseInt(seat.substring(1)) - 1;
                            try { theatre.bookSeat(r, c); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("History restore warning: " + e.getMessage());
        }
    }
}
