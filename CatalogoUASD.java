package catalogouasd;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Catálogo de carreras de la UASD - Grupo Mentes Digitales
 * - Base de datos:
 *     * En tu PC: SQLite (archivo uasd.db que se crea solo)
 *     * En la nube: PostgreSQL (si existe la variable de entorno DATABASE_URL)
 * - Servidor web: el que ya trae Java (com.sun.net.httpserver)
 * - Página web: http://localhost:8080 (o el link del hosting)
 */
public class CatalogoUASD {

    static final String URL_SQLITE = "jdbc:sqlite:uasd.db";
    static Connection con;
    static boolean usarPostgres = false;
    static String urlPostgres, usuarioPostgres, clavePostgres;

    // ------------------------------------------------------------------
    // PROGRAMA PRINCIPAL
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        configurarBaseDeDatos();
        crearTablas();
        if (usarPostgres && estaVacia() && java.nio.file.Files.exists(java.nio.file.Path.of("uasd.db"))) {
            importarDesdeSqlite();   // primera vez en la nube: copia los datos de uasd.db
        }
        crearAdminInicial();
        if (estaVacia()) {
            cargarDatosIniciales();
        }
        cargarMencionesIniciales();

        // En la nube el hosting indica el puerto con la variable PORT; en tu PC se usa 8080
        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer servidor = HttpServer.create(new InetSocketAddress(puerto), 0);
        servidor.createContext("/", CatalogoUASD::paginaWeb);
        servidor.createContext("/api/escuelas", CatalogoUASD::apiEscuelas);
        servidor.createContext("/api/carreras", CatalogoUASD::apiCarreras);
        servidor.createContext("/api/menciones", CatalogoUASD::apiMenciones);
        servidor.createContext("/logo.jpg", CatalogoUASD::logo);
        servidor.createContext("/api/login", CatalogoUASD::apiLogin);
        servidor.createContext("/api/logout", CatalogoUASD::apiLogout);
        servidor.createContext("/api/sesion", CatalogoUASD::apiSesion);
        servidor.createContext("/api/clave", CatalogoUASD::apiClave);
        servidor.start();

        System.out.println("Base de datos: " + (usarPostgres ? "PostgreSQL (nube)" : "SQLite (uasd.db)"));
        System.out.println("Servidor listo. Abre en tu navegador: http://localhost:" + puerto);
    }

    // ------------------------------------------------------------------
    // CONEXIÓN A LA BASE DE DATOS
    // ------------------------------------------------------------------
    // Si existe DATABASE_URL (en la nube) se usa PostgreSQL; si no, SQLite.
    // Acepta el formato que da Neon: postgresql://usuario:clave@servidor/basedatos?sslmode=require
    static void configurarBaseDeDatos() throws Exception {
        String url = System.getenv("DATABASE_URL");
        if (url == null || url.isBlank()) return;
        url = url.trim();
        usarPostgres = true;
        if (url.startsWith("jdbc:")) {
            urlPostgres = url;
            return;
        }
        java.net.URI uri = new java.net.URI(url.replaceFirst("^postgres(ql)?://", "http://"));
        String info = uri.getRawUserInfo();
        if (info != null) {
            String[] partes = info.split(":", 2);
            usuarioPostgres = URLDecoder.decode(partes[0], StandardCharsets.UTF_8);
            clavePostgres = partes.length > 1 ? URLDecoder.decode(partes[1], StandardCharsets.UTF_8) : "";
        }
        String sslmode = "require";   // Neon y la mayoría de servicios en la nube exigen SSL
        if (uri.getQuery() != null) {
            for (String par : uri.getQuery().split("&")) {
                if (par.startsWith("sslmode=")) sslmode = par.substring(8);
            }
        }
        urlPostgres = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                + uri.getPath() + "?sslmode=" + sslmode;
    }

    // Devuelve la conexión. Si se cayó (por ejemplo, la base en la nube se durmió), se reconecta sola.
    static Connection bd() throws Exception {
        if (con == null || !con.isValid(5)) {
            if (con != null) {
                try { con.close(); } catch (Exception ignorar) { }
            }
            con = usarPostgres
                    ? DriverManager.getConnection(urlPostgres, usuarioPostgres, clavePostgres)
                    : DriverManager.getConnection(URL_SQLITE);
        }
        return con;
    }

    static void crearTablas() throws Exception {
        String id = usarPostgres ? "id SERIAL PRIMARY KEY," : "id INTEGER PRIMARY KEY AUTOINCREMENT,";
        try (Statement st = bd().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS escuela ("
                    + id
                    + "nombre TEXT NOT NULL UNIQUE)");
            st.execute("CREATE TABLE IF NOT EXISTS carrera ("
                    + id
                    + "nombre TEXT NOT NULL,"
                    + "escuela_id INTEGER NOT NULL,"
                    + "FOREIGN KEY (escuela_id) REFERENCES escuela(id))");
            st.execute("CREATE TABLE IF NOT EXISTS mencion ("
                    + id
                    + "nombre TEXT NOT NULL,"
                    + "codigo TEXT,"
                    + "carrera_id INTEGER NOT NULL,"
                    + "FOREIGN KEY (carrera_id) REFERENCES carrera(id))");
            st.execute("CREATE TABLE IF NOT EXISTS usuario ("
                    + "usuario TEXT PRIMARY KEY,"
                    + "sal TEXT NOT NULL,"
                    + "hash TEXT NOT NULL)");
        }
    }

    // Copia facultades, escuelas, carreras y usuarios desde uasd.db a PostgreSQL.
    // Solo se ejecuta una vez: cuando la base en la nube todavía está vacía.
    static void importarDesdeSqlite() throws Exception {
        System.out.println("Importando datos desde uasd.db ...");
        int nf = 0, ne = 0, nc = 0;
        try (Connection origen = DriverManager.getConnection(URL_SQLITE);
             Statement st = origen.createStatement()) {
            Map<Long, Long> idsFacultad = new HashMap<>();
            Map<Long, Long> idsEscuela = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT id, nombre FROM escuela ORDER BY id")) {
                while (rs.next()) {
                    idsFacultad.put(rs.getLong("id"), insertarEscuela(rs.getString("nombre")));
                    nf++;
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id, nombre, escuela_id FROM carrera ORDER BY id")) {
                while (rs.next()) {
                    Long nuevaFacultad = idsFacultad.get(rs.getLong("escuela_id"));
                    if (nuevaFacultad == null) continue;
                    idsEscuela.put(rs.getLong("id"), insertarCarreraConId(rs.getString("nombre"), nuevaFacultad));
                    ne++;
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT nombre, codigo, carrera_id FROM mencion ORDER BY id")) {
                while (rs.next()) {
                    Long nuevaEscuela = idsEscuela.get(rs.getLong("carrera_id"));
                    if (nuevaEscuela == null) continue;
                    insertarMencion(rs.getString("nombre"), rs.getString("codigo"), nuevaEscuela);
                    nc++;
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT usuario, sal, hash FROM usuario")) {
                while (rs.next()) {
                    try (PreparedStatement ps = bd().prepareStatement(
                            "INSERT INTO usuario(usuario, sal, hash) VALUES (?, ?, ?) ON CONFLICT(usuario) DO NOTHING")) {
                        ps.setString(1, rs.getString("usuario"));
                        ps.setString(2, rs.getString("sal"));
                        ps.setString(3, rs.getString("hash"));
                        ps.executeUpdate();
                    }
                }
            } catch (java.sql.SQLException sinTablaUsuario) {
                // uasd.db es de una versión sin login: se creará el usuario inicial
            }
        }
        System.out.println("Importado: " + nf + " facultades, " + ne + " escuelas, " + nc + " carreras.");
    }

    static long insertarCarreraConId(String nombre, long escuelaId) throws Exception {
        try (PreparedStatement ps = bd().prepareStatement(
                "INSERT INTO carrera(nombre, escuela_id) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, limpiar(nombre));
            ps.setLong(2, escuelaId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ------------------------------------------------------------------
    // LOGIN Y SESIONES
    // ------------------------------------------------------------------
    // Usuario que se crea la primera vez que se ejecuta el programa.
    // Después de entrar, cambia la contraseña desde el panel de la página.
    static final String USUARIO_INICIAL = "admin";
    // En la nube puedes ponerla con la variable de entorno ADMIN_CLAVE.
    static final String CLAVE_INICIAL = System.getenv().getOrDefault("ADMIN_CLAVE", "mentes2026");

    // Sesiones abiertas (cada una es un código secreto guardado en una cookie del navegador)
    static final Set<String> sesiones = ConcurrentHashMap.newKeySet();
    static final SecureRandom azar = new SecureRandom();

    static void crearAdminInicial() throws Exception {
        try (Statement st = bd().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM usuario")) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }
        guardarClave(USUARIO_INICIAL, CLAVE_INICIAL);
        System.out.println("Usuario creado -> usuario: " + USUARIO_INICIAL
                + (System.getenv("ADMIN_CLAVE") == null ? "  contraseña: " + CLAVE_INICIAL : "  (contraseña de ADMIN_CLAVE)"));
    }

    // La contraseña nunca se guarda tal cual: se guarda su "hash" con una sal aleatoria
    static String calcularHash(String clave, byte[] sal) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(clave.toCharArray(), sal, 120000, 256);
        byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return Base64.getEncoder().encodeToString(h);
    }

    static void guardarClave(String usuario, String clave) throws Exception {
        byte[] sal = new byte[16];
        azar.nextBytes(sal);
        try (PreparedStatement ps = bd().prepareStatement(
                "INSERT INTO usuario(usuario, sal, hash) VALUES (?, ?, ?) "
                + "ON CONFLICT(usuario) DO UPDATE SET sal = excluded.sal, hash = excluded.hash")) {
            ps.setString(1, usuario);
            ps.setString(2, Base64.getEncoder().encodeToString(sal));
            ps.setString(3, calcularHash(clave, sal));
            ps.executeUpdate();
        }
    }

    static boolean claveCorrecta(String usuario, String clave) throws Exception {
        try (PreparedStatement ps = bd().prepareStatement("SELECT sal, hash FROM usuario WHERE usuario = ?")) {
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                byte[] sal = Base64.getDecoder().decode(rs.getString("sal"));
                String calculado = calcularHash(clave, sal);
                return MessageDigest.isEqual(calculado.getBytes(StandardCharsets.UTF_8),
                        rs.getString("hash").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static String leerCookieSesion(HttpExchange ex) {
        String cookies = ex.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) return null;
        for (String c : cookies.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals("sesion")) return kv[1];
        }
        return null;
    }

    static boolean esAdmin(HttpExchange ex) {
        String token = leerCookieSesion(ex);
        return token != null && sesiones.contains(token);
    }

    // Si no hay sesión, responde 401 y devuelve false
    static boolean exigirAdmin(HttpExchange ex) throws IOException {
        if (esAdmin(ex)) return true;
        enviar(ex, 401, "application/json", "{\"error\":\"Necesitas iniciar sesión para modificar\"}");
        return false;
    }

    static void apiLogin(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                enviar(ex, 405, "application/json", "{\"error\":\"Usa POST\"}");
                return;
            }
            Map<String, String> p = leerFormulario(ex);
            String usuario = p.getOrDefault("usuario", "").trim();
            String clave = p.getOrDefault("clave", "");
            if (!claveCorrecta(usuario, clave)) {
                Thread.sleep(800); // frena a quien intente adivinar contraseñas
                enviar(ex, 401, "application/json", "{\"error\":\"Usuario o contraseña incorrectos\"}");
                return;
            }
            byte[] b = new byte[32];
            azar.nextBytes(b);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            sesiones.add(token);
            ex.getResponseHeaders().add("Set-Cookie",
                    "sesion=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            enviar(ex, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            enviar(ex, 500, "application/json", "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    static void apiLogout(HttpExchange ex) throws IOException {
        String token = leerCookieSesion(ex);
        if (token != null) sesiones.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", "sesion=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        enviar(ex, 200, "application/json", "{\"ok\":true}");
    }

    static void apiSesion(HttpExchange ex) throws IOException {
        enviar(ex, 200, "application/json", "{\"admin\":" + esAdmin(ex) + "}");
    }

    static void apiClave(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("POST")) {
                enviar(ex, 405, "application/json", "{\"error\":\"Usa POST\"}");
                return;
            }
            if (!exigirAdmin(ex)) return;
            Map<String, String> p = leerFormulario(ex);
            String usuario = p.getOrDefault("usuario", "").trim();
            String actual = p.getOrDefault("actual", "");
            String nueva = p.getOrDefault("nueva", "");
            if (nueva.length() < 6) {
                enviar(ex, 400, "application/json", "{\"error\":\"La nueva contraseña debe tener al menos 6 caracteres\"}");
                return;
            }
            if (!claveCorrecta(usuario, actual)) {
                enviar(ex, 400, "application/json", "{\"error\":\"El usuario o la contraseña actual no son correctos\"}");
                return;
            }
            guardarClave(usuario, nueva);
            enviar(ex, 200, "application/json", "{\"ok\":true}");
        } catch (Exception e) {
            enviar(ex, 500, "application/json", "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    static boolean estaVacia() throws Exception {
        try (Statement st = bd().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM escuela")) {
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    static void cargarDatosIniciales() throws Exception {
        // Datos de ejemplo: puedes cambiarlos desde la página web
        String[][] datos = {
            {"Ingeniería y Arquitectura", "Arquitectura", "Ingeniería Civil", "Ingeniería Eléctrica",
                "Ingeniería Industrial", "Ingeniería Mecánica", "Agrimensura"},
            {"Ciencias Económicas y Sociales", "Administración de Empresas", "Contabilidad",
                "Economía", "Mercadotecnia", "Negocios Internacionales"},
            {"Artes", "Artes Plásticas", "Música", "Teatro", "Diseño de Interiores", "Publicidad"},
            {"Ciencias de la Salud", "Medicina", "Odontología", "Enfermería", "Bioanálisis"},
            {"Ciencias", "Informática", "Matemática", "Física", "Química", "Biología"},
            {"Humanidades", "Psicología", "Letras", "Filosofía", "Comunicación Social", "Educación"},
            {"Ciencias Jurídicas y Políticas", "Derecho"},
            {"Ciencias Agronómicas y Veterinarias", "Agronomía", "Medicina Veterinaria"}
        };
        for (String[] fila : datos) {
            long idEscuela = insertarEscuela(fila[0]);
            for (int i = 1; i < fila.length; i++) {
                insertarCarrera(fila[i], idEscuela);
            }
        }
    }

    static long insertarEscuela(String nombre) throws Exception {
        try (PreparedStatement ps = bd().prepareStatement(
                "INSERT INTO escuela(nombre) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, limpiar(nombre));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void insertarCarrera(String nombre, long escuelaId) throws Exception {
        try (PreparedStatement ps = bd().prepareStatement(
                "INSERT INTO carrera(nombre, escuela_id) VALUES (?, ?)")) {
            ps.setString(1, limpiar(nombre));
            ps.setLong(2, escuelaId);
            ps.executeUpdate();
        }
    }

    static void insertarMencion(String nombre, String codigo, long carreraId) throws Exception {
        try (PreparedStatement ps = bd().prepareStatement(
                "INSERT INTO mencion(nombre, codigo, carrera_id) VALUES (?, ?, ?)")) {
            ps.setString(1, limpiar(nombre));
            ps.setString(2, limpiar(codigo));
            ps.setLong(3, carreraId);
            ps.executeUpdate();
        }
    }

    static void cargarMencionesIniciales() throws Exception {
        try (Statement st = bd().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM mencion")) {
            if (!rs.next() || rs.getInt(1) > 0) return;
        }
        long carreraId = -1;
        try (PreparedStatement ps = bd().prepareStatement("SELECT id FROM carrera WHERE nombre = ?")) {
            ps.setString(1, "Comunicación Social");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) carreraId = rs.getLong(1);
            }
        }
        if (carreraId == -1) return;
        insertarMencion("Licenciatura en Com Social Mención Comunicación Gráfica", "30405 - P-CSCG", carreraId);
        insertarMencion("Licenciatura en Com Social Mención Periodismo", "30403 - P-CSPE", carreraId);
        insertarMencion("Licenciatura en Com Social Mención Relaciones Públicas", "30404 - P-CSRP", carreraId);
    }

    // ------------------------------------------------------------------
    // API: ESCUELAS  (GET = listar, POST = agregar / eliminar)
    // ------------------------------------------------------------------
    static void apiEscuelas(HttpExchange ex) throws IOException {
        try {
            if (ex.getRequestMethod().equals("POST")) {
                if (!exigirAdmin(ex)) return;   // solo con sesión iniciada
                Map<String, String> p = leerFormulario(ex);
                if ("agregar".equals(p.get("accion")) && !vacio(p.get("nombre"))) {
                    insertarEscuela(p.get("nombre"));
                } else if ("eliminar".equals(p.get("accion"))) {
                    long id = Long.parseLong(p.get("id"));
                    try (PreparedStatement ps = bd().prepareStatement(
                            "DELETE FROM mencion WHERE carrera_id IN "
                            + "(SELECT id FROM carrera WHERE escuela_id = ?)")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = bd().prepareStatement(
                            "DELETE FROM carrera WHERE escuela_id = ?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = bd().prepareStatement(
                            "DELETE FROM escuela WHERE id = ?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                }
                enviar(ex, 200, "application/json", "{\"ok\":true}");
                return;
            }

            StringBuilder json = new StringBuilder("[");
            try (Statement st = bd().createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, nombre FROM escuela ORDER BY nombre")) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero) json.append(",");
                    primero = false;
                    json.append("{\"id\":").append(rs.getInt("id"))
                        .append(",\"nombre\":\"").append(esc(rs.getString("nombre"))).append("\"}");
                }
            }
            json.append("]");
            enviar(ex, 200, "application/json", json.toString());
        } catch (Exception e) {
            enviar(ex, 500, "application/json", "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    // ------------------------------------------------------------------
    // API: CARRERAS  (GET ?escuela=ID = listar, POST = agregar / eliminar)
    // ------------------------------------------------------------------
    static void apiCarreras(HttpExchange ex) throws IOException {
        try {
            if (ex.getRequestMethod().equals("POST")) {
                if (!exigirAdmin(ex)) return;   // solo con sesión iniciada
                Map<String, String> p = leerFormulario(ex);
                if ("agregar".equals(p.get("accion")) && !vacio(p.get("nombre"))) {
                    insertarCarrera(p.get("nombre"), Long.parseLong(p.get("escuela_id")));
                } else if ("eliminar".equals(p.get("accion"))) {
                    try (PreparedStatement ps = bd().prepareStatement("DELETE FROM mencion WHERE carrera_id = ?")) {
                        ps.setLong(1, Long.parseLong(p.get("id")));
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = bd().prepareStatement("DELETE FROM carrera WHERE id = ?")) {
                        ps.setLong(1, Long.parseLong(p.get("id")));
                        ps.executeUpdate();
                    }
                }
                enviar(ex, 200, "application/json", "{\"ok\":true}");
                return;
            }

            String consulta = ex.getRequestURI().getQuery();
            Map<String, String> q = parsear(consulta == null ? "" : consulta);
            String sql = "SELECT c.id, c.nombre, e.nombre AS escuela FROM carrera c "
                    + "JOIN escuela e ON e.id = c.escuela_id "
                    + (q.containsKey("escuela") ? "WHERE e.id = ? " : "")
                    + "ORDER BY c.nombre";

            StringBuilder json = new StringBuilder("[");
            try (PreparedStatement ps = bd().prepareStatement(sql)) {
                if (q.containsKey("escuela")) {
                    ps.setLong(1, Long.parseLong(q.get("escuela")));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    boolean primero = true;
                    while (rs.next()) {
                        if (!primero) json.append(",");
                        primero = false;
                        json.append("{\"id\":").append(rs.getInt("id"))
                            .append(",\"nombre\":\"").append(esc(rs.getString("nombre")))
                            .append("\",\"escuela\":\"").append(esc(rs.getString("escuela"))).append("\"}");
                    }
                }
            }
            json.append("]");
            enviar(ex, 200, "application/json", json.toString());
        } catch (Exception e) {
            enviar(ex, 500, "application/json", "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    // ------------------------------------------------------------------
    // API: MENCIONES  (GET ?carrera=ID = listar, POST = agregar / eliminar)
    // ------------------------------------------------------------------
    static void apiMenciones(HttpExchange ex) throws IOException {
        try {
            if (ex.getRequestMethod().equals("POST")) {
                if (!exigirAdmin(ex)) return;   // solo con sesión iniciada
                Map<String, String> p = leerFormulario(ex);
                if ("agregar".equals(p.get("accion")) && !vacio(p.get("nombre"))) {
                    insertarMencion(p.get("nombre"), p.get("codigo"), Long.parseLong(p.get("carrera_id")));
                } else if ("eliminar".equals(p.get("accion"))) {
                    try (PreparedStatement ps = bd().prepareStatement("DELETE FROM mencion WHERE id = ?")) {
                        ps.setLong(1, Long.parseLong(p.get("id")));
                        ps.executeUpdate();
                    }
                }
                enviar(ex, 200, "application/json", "{\"ok\":true}");
                return;
            }

            String consulta = ex.getRequestURI().getQuery();
            Map<String, String> q = parsear(consulta == null ? "" : consulta);
            long carreraId = Long.parseLong(q.getOrDefault("carrera", "0"));

            StringBuilder json = new StringBuilder("[");
            try (PreparedStatement ps = bd().prepareStatement(
                    "SELECT id, nombre, codigo FROM mencion WHERE carrera_id = ? ORDER BY nombre")) {
                ps.setLong(1, carreraId);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean primero = true;
                    while (rs.next()) {
                        if (!primero) json.append(",");
                        primero = false;
                        json.append("{\"id\":").append(rs.getInt("id"))
                            .append(",\"nombre\":\"").append(esc(rs.getString("nombre")))
                            .append("\",\"codigo\":\"").append(esc(rs.getString("codigo"))).append("\"}");
                    }
                }
            }
            json.append("]");
            enviar(ex, 200, "application/json", json.toString());
        } catch (Exception e) {
            enviar(ex, 500, "application/json", "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    // ------------------------------------------------------------------
    // UTILIDADES
    // ------------------------------------------------------------------
    static boolean vacio(String s) {
        return s == null || s.trim().isEmpty();
    }

    // Quita caracteres invisibles (tabuladores, saltos de línea, etc.) y espacios de los extremos.
    // Se usa al GUARDAR, para que no entren nombres "sucios" a la base de datos.
    static String limpiar(String s) {
        if (s == null) return "";
        return s.replaceAll("\\p{Cntrl}", " ").replaceAll(" +", " ").trim();
    }

    // Prepara un texto para meterlo en JSON. Se usa al ENVIAR a la página.
    // También quita caracteres invisibles, por si ya hay alguno guardado en la base de datos.
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("\\p{Cntrl}", " ");
    }

    static Map<String, String> leerFormulario(HttpExchange ex) throws IOException {
        String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parsear(cuerpo);
    }

    static Map<String, String> parsear(String texto) {
        Map<String, String> mapa = new HashMap<>();
        for (String par : texto.split("&")) {
            if (par.isEmpty()) continue;
            String[] kv = par.split("=", 2);
            String clave = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String valor = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            mapa.put(clave, valor);
        }
        return mapa;
    }

    static void enviar(HttpExchange ex, int codigo, String tipo, String texto) throws IOException {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", tipo + "; charset=utf-8");
        ex.sendResponseHeaders(codigo, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static void paginaWeb(HttpExchange ex) throws IOException {
        enviar(ex, 200, "text/html", PAGINA);
    }

    // Envía el logo del grupo. El logo va guardado dentro de este mismo archivo
    // (en LOGO_BASE64, al final), así que no hace falta copiar ninguna imagen.
    static void logo(HttpExchange ex) throws IOException {
        byte[] bytes = java.util.Base64.getDecoder().decode(LOGO_BASE64);
        ex.getResponseHeaders().set("Content-Type", "image/jpeg");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // ------------------------------------------------------------------
    // PÁGINA WEB (HTML + CSS + JavaScript)
    // ------------------------------------------------------------------
    static final String PAGINA = """
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Catálogo de Carreras - UASD</title>
<style>
  :root { --azul:#0b3d91; --claro:#eef3fb; --texto:#1b1f2a; --borde:#c9d4ea; }
  * { box-sizing: border-box; }
  body { font-family: Arial, sans-serif; margin:0; background:var(--claro); color:var(--texto); }
  header { background:linear-gradient(135deg, #0a1f44 0%, var(--azul) 55%, #1e88e5 100%);
    color:#fff; padding:28px 20px; }
  .marca { max-width:900px; margin:0 auto; display:flex; align-items:center; gap:24px; flex-wrap:wrap; }
  .logo { width:120px; height:120px; object-fit:contain; background:#fff; border-radius:50%;
    padding:6px; box-shadow:0 6px 20px rgba(0,0,0,.35); }
  .marca h1 { margin:0; font-size:28px; }
  .grupo { margin:8px 0 2px; font-size:20px; font-weight:bold; letter-spacing:2px; color:#bfe0ff; }
  .lema { margin:0; font-size:13px; letter-spacing:3px; text-transform:uppercase; opacity:.85; }
  .instruccion { max-width:900px; margin:16px auto 0; opacity:.9; }
  .equipo { display:grid; grid-template-columns:repeat(auto-fit, minmax(190px, 1fr)); gap:14px; }
  .miembro { border:1px solid var(--borde); border-radius:12px; padding:18px 14px; text-align:center;
    background:linear-gradient(180deg, #f7faff 0%, #fff 100%); }
  .avatar { width:58px; height:58px; margin:0 auto 10px; border-radius:50%; display:flex;
    align-items:center; justify-content:center; font-weight:bold; font-size:20px; color:#fff;
    background:linear-gradient(135deg, var(--azul), #1e88e5); }
  .miembro .nombre { font-weight:bold; color:var(--texto); }
  .miembro .matricula { margin-top:6px; font-size:14px; color:#4a5875; }
  .miembro.pendiente { border-style:dashed; background:#fafbfd; }
  .miembro.pendiente .avatar { background:#c9d4ea; }
  .miembro.pendiente .nombre, .miembro.pendiente .matricula { color:#8a96ad; font-style:italic; }
  footer { text-align:center; padding:24px 16px; color:#4a5875; font-size:14px; }
  /* ---- Login ---- */
  body:not(.modo-admin) .solo-admin { display:none !important; }
  .marca .textos { flex:1; }
  .acceso { display:flex; flex-direction:column; align-items:flex-end; gap:6px; }
  .btn-acceso { background:#fff; color:var(--azul); border:0; border-radius:20px;
    padding:9px 18px; font-weight:bold; cursor:pointer; box-shadow:0 3px 10px rgba(0,0,0,.25); }
  .btn-acceso:hover { background:#e3efff; }
  .estado-admin { font-size:13px; background:rgba(255,255,255,.15); padding:4px 10px; border-radius:12px; }
  .fondo-modal { position:fixed; inset:0; background:rgba(10,31,68,.6); display:none;
    align-items:center; justify-content:center; padding:16px; z-index:10; }
  .fondo-modal.abierto { display:flex; }
  .modal { background:#fff; border-radius:14px; padding:26px; width:100%; max-width:360px;
    box-shadow:0 12px 40px rgba(0,0,0,.35); text-align:center; }
  .modal img { width:80px; height:80px; object-fit:contain; }
  .modal h2 { margin:8px 0 16px; }
  .modal input { width:100%; margin:6px 0; }
  .modal .acciones { display:flex; gap:10px; margin-top:12px; }
  .modal .acciones button { flex:1; padding:10px; border-radius:8px; border:0; cursor:pointer; font-weight:bold; }
  .btn-entrar { background:var(--azul); color:#fff; }
  .btn-cancelar { background:#e6ebf5; color:var(--texto); }
  .error-login { color:#c62828; font-size:14px; min-height:18px; margin:6px 0 0; }
  @media (max-width:600px) { .acceso { align-items:center; width:100%; } }
  @media (max-width:600px) { .marca { justify-content:center; text-align:center; } }
  main { max-width:900px; margin:20px auto; padding:0 16px; }
  h2 { color:var(--azul); }
  .botones { display:flex; flex-wrap:wrap; gap:10px; }
  .botones button { background:#fff; border:2px solid var(--azul); color:var(--azul);
    padding:10px 16px; border-radius:8px; cursor:pointer; font-size:15px; }
  .botones button:hover, .botones button.activo { background:var(--azul); color:#fff; }
  .tarjeta { background:#fff; border:1px solid var(--borde); border-radius:10px;
    padding:16px; margin-top:16px; }
  ul { padding-left:20px; }
  li { margin:6px 0; }
  li button { margin-left:10px; background:#c62828; color:#fff; border:0;
    border-radius:4px; cursor:pointer; padding:2px 8px; }
  input, select { padding:8px; border:1px solid var(--borde); border-radius:6px; margin:4px 4px 4px 0; }
  .admin button { background:var(--azul); color:#fff; border:0; padding:8px 14px;
    border-radius:6px; cursor:pointer; }
  .rojo { background:#c62828 !important; }
  .carrera { cursor:pointer; color:var(--azul); font-weight:bold; }
  .carrera:hover { text-decoration:underline; }
  .menu { margin:8px 0 12px 22px; padding:10px; background:var(--claro);
    border-left:4px solid var(--azul); border-radius:6px; }
  .menu ul { margin:0 0 8px 0; }
  .menu > div > button { background:var(--azul); color:#fff; border:0; border-radius:6px;
    padding:8px 14px; cursor:pointer; margin-left:0; }
</style>
</head>
<body>
<header>
  <div class="marca">
    <img src="/logo.jpg" alt="Logo de Mentes Digitales" class="logo">
    <div class="textos">
      <h1>Catálogo de Carreras - UASD</h1>
      <p class="grupo">MENTES DIGITALES</p>
      <p class="lema">Innovar, aprender y avanzar</p>
    </div>
    <div class="acceso">
      <button class="btn-acceso" id="btnAcceso" onclick="clicAcceso()">Iniciar sesión</button>
      <span class="estado-admin solo-admin">Modo administrador</span>
    </div>
  </div>
  <p class="instruccion">Selecciona una Facultad para ver sus escuelas</p>
</header>

<main>
  <h2>Facultad</h2>
  <div class="botones" id="botones"></div>

  <div class="tarjeta">
    <h2 id="titulo">Escuelas</h2>
    <ul id="lista"><li>Elige una facultad arriba.</li></ul>
  </div>

  <div class="tarjeta admin solo-admin">
    <h2>Panel para modificar el catálogo</h2>

    <p><b>Agregar facultad (crea un botón nuevo):</b><br>
      <input id="nuevaEscuela" placeholder="Nombre de la facultad">
      <button onclick="agregarEscuela()">Agregar</button></p>

    <p><b>Agregar escuela:</b><br>
      <select id="selEscuela"></select>
      <input id="nuevaCarrera" placeholder="Nombre de la escuela">
      <button onclick="agregarCarrera()">Agregar</button></p>

    <p><b>Eliminar facultad (también borra sus escuelas):</b><br>
      <select id="selEliminar"></select>
      <button class="rojo" onclick="eliminarEscuela()">Eliminar</button></p>

    <p><b>Cambiar contraseña:</b><br>
      <input id="cUsuario" placeholder="Usuario" autocomplete="username">
      <input id="cActual" type="password" placeholder="Contraseña actual" autocomplete="current-password">
      <input id="cNueva" type="password" placeholder="Nueva contraseña" autocomplete="new-password">
      <button onclick="cambiarClave()">Cambiar</button></p>
  </div>

  <div class="tarjeta">
    <h2>Integrantes del grupo</h2>
    <div class="equipo">
      <div class="miembro">
        <div class="avatar">AR</div>
        <div class="nombre">Alinson Fernando Ramírez Navarro</div>
        <div class="matricula">Matrícula: 100570096</div>
      </div>
      <div class="miembro">
        <div class="avatar">EC</div>
        <div class="nombre">Edwin Cedano Ogando</div>
        <div class="matricula">Matrícula: 100782998</div>
      </div>
      <div class="miembro">
        <div class="avatar">JS</div>
        <div class="nombre">Jesus Santos</div>
        <div class="matricula">Matrícula: 100588729</div>
      </div>
      <div class="miembro">
        <div class="avatar">GM</div>
        <div class="nombre">Gregorio Martes Abreu</div>
        <div class="matricula">Matrícula: 100333136</div>
      </div>
    </div>
  </div>
</main>

<footer>Grupo Mentes Digitales · Universidad Autónoma de Santo Domingo (UASD)</footer>

<div class="fondo-modal" id="fondoLogin" onclick="if (event.target === this) cerrarLogin()">
  <form class="modal" onsubmit="event.preventDefault(); entrar();">
    <img src="/logo.jpg" alt="">
    <h2>Acceso de administrador</h2>
    <input id="lUsuario" placeholder="Usuario" autocomplete="username">
    <input id="lClave" type="password" placeholder="Contraseña" autocomplete="current-password">
    <p class="error-login" id="errorLogin"></p>
    <div class="acciones">
      <button type="button" class="btn-cancelar" onclick="cerrarLogin()">Cancelar</button>
      <button type="submit" class="btn-entrar">Entrar</button>
    </div>
  </form>
</div>

<script>
let escuelas = [];
let escuelaActual = null;

let esAdmin = false;

async function post(url, datos) {
  const r = await fetch(url, { method: 'POST', body: new URLSearchParams(datos) });
  if (r.status === 401) {
    ponerModoAdmin(false);
    alert('Tu sesión terminó. Inicia sesión de nuevo para modificar.');
    throw new Error('sin sesión');
  }
  if (!r.ok) {
    const txt = await r.text();
    alert('Error del servidor: ' + txt);
    throw new Error(txt);
  }
}

// ---------- Login ----------
function ponerModoAdmin(activo) {
  esAdmin = activo;
  document.body.classList.toggle('modo-admin', activo);
  document.getElementById('btnAcceso').textContent = activo ? 'Cerrar sesión' : 'Iniciar sesión';
}

async function revisarSesion() {
  try {
    const r = await fetch('/api/sesion');
    const d = await r.json();
    ponerModoAdmin(d.admin === true);
  } catch (e) {
    ponerModoAdmin(false);
  }
}

function clicAcceso() {
  if (esAdmin) salir(); else abrirLogin();
}

function abrirLogin() {
  document.getElementById('errorLogin').textContent = '';
  document.getElementById('fondoLogin').classList.add('abierto');
  document.getElementById('lUsuario').focus();
}

function cerrarLogin() {
  document.getElementById('fondoLogin').classList.remove('abierto');
  document.getElementById('lClave').value = '';
}

async function entrar() {
  const usuario = document.getElementById('lUsuario').value;
  const clave = document.getElementById('lClave').value;
  const error = document.getElementById('errorLogin');
  error.textContent = 'Verificando...';
  const r = await fetch('/api/login', { method: 'POST', body: new URLSearchParams({ usuario, clave }) });
  if (r.ok) {
    cerrarLogin();
    ponerModoAdmin(true);
  } else {
    const d = await r.json().catch(() => ({}));
    error.textContent = d.error || 'No se pudo iniciar sesión';
  }
}

async function salir() {
  await fetch('/api/logout', { method: 'POST' });
  ponerModoAdmin(false);
}

async function cambiarClave() {
  const datos = {
    usuario: document.getElementById('cUsuario').value,
    actual: document.getElementById('cActual').value,
    nueva: document.getElementById('cNueva').value
  };
  const r = await fetch('/api/clave', { method: 'POST', body: new URLSearchParams(datos) });
  const d = await r.json().catch(() => ({}));
  if (r.ok) {
    alert('Contraseña cambiada correctamente.');
    document.getElementById('cActual').value = '';
    document.getElementById('cNueva').value = '';
  } else {
    alert(d.error || 'No se pudo cambiar la contraseña');
  }
}

document.addEventListener('keydown', e => { if (e.key === 'Escape') cerrarLogin(); });

async function cargarEscuelas() {
  const r = await fetch('/api/escuelas');
  escuelas = await r.json();

  const cont = document.getElementById('botones');
  cont.innerHTML = '';
  escuelas.forEach(e => {
    const b = document.createElement('button');
    b.textContent = e.nombre;
    b.dataset.id = e.id;
    b.onclick = () => mostrarCarreras(e.id, e.nombre);
    cont.appendChild(b);
  });

  const opciones = escuelas.map(e => `<option value="${e.id}">${e.nombre}</option>`).join('');
  document.getElementById('selEscuela').innerHTML = opciones;
  document.getElementById('selEliminar').innerHTML = opciones;
  marcarActual();
}

// Resalta el botón de la facultad actual y deja el selector "Agregar escuela" en ella,
// sin volver a pedir ni reconstruir nada
function marcarActual() {
  document.querySelectorAll('#botones button').forEach(b => {
    b.classList.toggle('activo', Number(b.dataset.id) === escuelaActual);
  });
  if (escuelaActual !== null && escuelas.some(e => e.id === escuelaActual)) {
    document.getElementById('selEscuela').value = escuelaActual;
  }
}

async function mostrarCarreras(id, nombre) {
  escuelaActual = id;
  marcarActual();
  document.getElementById('titulo').textContent = 'Escuelas de ' + nombre;
  const lista = document.getElementById('lista');

  let carreras;
  try {
    const r = await fetch('/api/carreras?escuela=' + id);
    if (!r.ok) throw new Error(await r.text());
    carreras = await r.json();
  } catch (err) {
    lista.innerHTML = '';
    const li = document.createElement('li');
    li.textContent = 'No se pudo cargar: ' + err.message;
    lista.appendChild(li);
    return;
  }

  lista.innerHTML = '';
  if (carreras.length === 0) {
    lista.innerHTML = '<li>Esta facultad aún no tiene escuelas.</li>';
  }
  carreras.forEach(c => {
    const li = document.createElement('li');

    const titulo = document.createElement('span');
    titulo.className = 'carrera';
    titulo.textContent = '▸ ' + c.nombre;

    const menu = document.createElement('div');
    menu.className = 'menu';
    menu.style.display = 'none';
    titulo.onclick = () => alternarMenu(c, titulo, menu);

    const x = document.createElement('button');
    x.textContent = 'X';
    x.className = 'solo-admin';
    x.onclick = async () => {
      await post('/api/carreras', { accion: 'eliminar', id: c.id });
      mostrarCarreras(id, nombre);
    };

    li.appendChild(titulo);
    li.appendChild(x);
    li.appendChild(menu);
    lista.appendChild(li);
  });
}

async function alternarMenu(c, titulo, menu) {
  if (menu.style.display === 'block') {
    menu.style.display = 'none';
    titulo.textContent = '▸ ' + c.nombre;
    return;
  }
  titulo.textContent = '▾ ' + c.nombre;
  menu.style.display = 'block';
  await cargarMenciones(c, menu);
}

async function cargarMenciones(c, menu) {
  const r = await fetch('/api/menciones?carrera=' + c.id);
  const menciones = await r.json();
  menu.innerHTML = '';

  const ul = document.createElement('ul');
  if (menciones.length === 0) {
    ul.innerHTML = '<li>Esta escuela no tiene carreras registradas.</li>';
  }
  menciones.forEach(m => {
    const li = document.createElement('li');
    li.textContent = m.nombre + (m.codigo ? ' - (' + m.codigo + ')' : '');
    const b = document.createElement('button');
    b.textContent = 'X';
    b.className = 'solo-admin';
    b.onclick = async () => {
      await post('/api/menciones', { accion: 'eliminar', id: m.id });
      cargarMenciones(c, menu);
    };
    li.appendChild(b);
    ul.appendChild(li);
  });
  menu.appendChild(ul);

  const nombre = document.createElement('input');
  nombre.placeholder = 'Nueva carrera';
  const codigo = document.createElement('input');
  codigo.placeholder = 'Código (opcional)';
  const agregar = document.createElement('button');
  agregar.textContent = 'Agregar carrera';
  agregar.onclick = async () => {
    if (!nombre.value.trim()) return;
    await post('/api/menciones', { accion: 'agregar', nombre: nombre.value, codigo: codigo.value, carrera_id: c.id });
    cargarMenciones(c, menu);
  };
  const zonaAgregar = document.createElement('div');
  zonaAgregar.className = 'solo-admin';
  zonaAgregar.appendChild(nombre);
  zonaAgregar.appendChild(codigo);
  zonaAgregar.appendChild(agregar);
  menu.appendChild(zonaAgregar);
}

async function agregarEscuela() {
  const campo = document.getElementById('nuevaEscuela');
  if (!campo.value.trim()) return;
  await post('/api/escuelas', { accion: 'agregar', nombre: campo.value });
  campo.value = '';
  cargarEscuelas();
}

async function agregarCarrera() {
  const campo = document.getElementById('nuevaCarrera');
  const id = document.getElementById('selEscuela').value;
  if (!campo.value.trim() || !id) return;
  await post('/api/carreras', { accion: 'agregar', nombre: campo.value, escuela_id: id });
  campo.value = '';
  const e = escuelas.find(x => String(x.id) === id);
  mostrarCarreras(Number(id), e.nombre);
}

async function eliminarEscuela() {
  const id = document.getElementById('selEliminar').value;
  if (!id || !confirm('¿Seguro que quieres eliminar esta facultad y sus escuelas?')) return;
  await post('/api/escuelas', { accion: 'eliminar', id: id });
  escuelaActual = null;
  document.getElementById('titulo').textContent = 'Escuelas';
  document.getElementById('lista').innerHTML = '<li>Elige una facultad arriba.</li>';
  cargarEscuelas();
}

revisarSesion();
cargarEscuelas();
</script>
</body>
</html>
""";

    // ------------------------------------------------------------------
    // LOGO DEL GRUPO (imagen JPG convertida a texto Base64). No modificar.
    // ------------------------------------------------------------------
    static final String LOGO_BASE64 = "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";
}
