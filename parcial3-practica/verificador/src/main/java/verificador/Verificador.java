package verificador;

import co.icesi.subasta.controllers.TCPController;
import co.icesi.subasta.controllers.UDPController;
import co.icesi.subasta.model.Item;
import co.icesi.subasta.services.AuctionException;
import co.icesi.subasta.services.ServicesImpl;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class Verificador {

    private static final PrintStream OUT = System.out;
    private static final PrintStream SILENCIO = new PrintStream(OutputStream.nullOutputStream());
    private static int aprobadas = 0;
    private static int falladas = 0;
    private static final Map<String, int[]> porSeccion = new LinkedHashMap<>();
    private static String seccionActual;
    private static final String BLOQUEO = "(SE QUEDO BLOQUEADO)";

    public static void main(String[] args) {
        System.setOut(SILENCIO);
        System.setErr(SILENCIO);

        ejecutar("1. Logica UDP (UDPController.process)", Verificador::logicaUdp);
        ejecutar("2. Servicios y concurrencia (ServicesImpl)", Verificador::servicios);
        ejecutar("3. Servidor UDP por la red", Verificador::servidorUdp);
        ejecutar("4. Servidor TCP: sesion persistente", Verificador::servidorTcp);
        ejecutar("5. Arranque conjunto (Main)", Verificador::arranque);
        ejecutar("6. Cliente UDP (pantalla.DisplayClient)", Verificador::clientePantalla);
        ejecutar("7. Cliente TCP (postor.AuctionClient)", Verificador::clientePostor);

        OUT.println();
        OUT.println("================== RESUMEN ==================");
        for (Map.Entry<String, int[]> e : porSeccion.entrySet()) {
            int[] v = e.getValue();
            OUT.printf("  %-44s %2d/%-2d%n", e.getKey(), v[0], v[0] + v[1]);
        }
        OUT.println("---------------------------------------------");
        OUT.printf("  TOTAL: %d/%d pruebas aprobadas%n", aprobadas, aprobadas + falladas);
        OUT.println("  (si una seccion se detuvo antes, su total es menor)");
        OUT.println("=============================================");
        System.exit(0);
    }

    interface Seccion { void correr() throws Exception; }

    private static void ejecutar(String titulo, Seccion s) {
        seccionActual = titulo;
        porSeccion.put(titulo, new int[2]);
        OUT.println();
        OUT.println("== " + titulo + " ==");
        try {
            s.correr();
        } catch (Throwable t) {
            Throwable c = (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            fallo("La seccion se detuvo por una excepcion: " + c);
        }
    }

    private static void check(String nombre, boolean ok, String pista) {
        if (ok) {
            aprobadas++;
            porSeccion.get(seccionActual)[0]++;
            OUT.println("  [OK]    " + nombre);
        } else {
            falladas++;
            porSeccion.get(seccionActual)[1]++;
            OUT.println("  [FALLA] " + nombre + (pista == null ? "" : "\n          -> " + pista));
        }
    }

    private static void fallo(String msg) {
        check(msg, false, null);
    }

    private static void igual(String nombre, String esperado, String obtenido) {
        String pista = "esperado \"" + esperado + "\" pero se obtuvo \"" + visible(obtenido) + "\"";
        if (BLOQUEO.equals(obtenido)) {
            pista = "la operacion se quedo bloqueada: algo (un semaforo/lock) nunca se libero";
        }
        check(nombre, esperado.equals(obtenido), pista);
    }

    private static String visible(String s) {
        if (s == null) return "null";
        int nulos = 0;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == 0) nulos++;
            else if (c < 32) sb.append('?');
            else sb.append(c);
        }
        return nulos == 0 ? sb.toString()
                : sb + "\" + " + nulos + " bytes basura (\\0): se convirtio todo el buffer en vez de usar getLength()";
    }

    private static <T> T conLimite(Callable<T> c, long ms, T siBloquea) {
        final Object[] res = new Object[1];
        final boolean[] listo = new boolean[1];
        Thread t = new Thread(() -> {
            try {
                res[0] = c.call();
            } catch (Throwable e) {
                res[0] = e;
            }
            listo[0] = true;
        });
        t.setDaemon(true);
        t.start();
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
        if (!listo[0]) return siBloquea;
        if (res[0] instanceof RuntimeException) throw (RuntimeException) res[0];
        if (res[0] instanceof Throwable) throw new RuntimeException((Throwable) res[0]);
        @SuppressWarnings("unchecked") T r = (T) res[0];
        return r;
    }

    private static String puja(ServicesImpl s, String user, String item, double amount) {
        return conLimite(() -> {
            try {
                s.bid(user, item, amount);
                return "OK";
            } catch (AuctionException e) {
                return e.getMessage();
            }
        }, 2000, BLOQUEO);
    }

    // ------------------------------------------------------------------ 1
    private static void logicaUdp() {
        ServicesImpl s = new ServicesImpl();
        UDPController u = new UDPController(s, 0);

        igual("PING", "PONG", u.process("PING"));
        igual("PING con campos extra", "ERROR;INVALID_FORMAT", u.process("PING;1"));
        igual("PRICE sin pujas", "PRICE;A1;1000.0;-", u.process("PRICE;A1"));
        igual("TOP sin pujas", "TOP;-;0", u.process("TOP"));

        puja(s, "ana", "A1", 1200);
        igual("PRICE despues de una puja", "PRICE;A1;1200.0;ana", u.process("PRICE;A1"));
        puja(s, "ana", "A2", 400);
        puja(s, "bob", "A2", 410);
        igual("TOP con pujas", "TOP;A2;2", u.process("TOP"));
        igual("TOP con campos extra", "ERROR;INVALID_FORMAT", u.process("TOP;A1"));

        ServicesImpl s2 = new ServicesImpl();
        UDPController u2 = new UDPController(s2, 0);
        puja(s2, "ana", "A3", 150);
        puja(s2, "bob", "A1", 1000);
        igual("TOP en empate gana el primero del catalogo", "TOP;A1;1", u2.process("TOP"));

        igual("PRICE de articulo inexistente", "ERROR;UNKNOWN_ITEM", u.process("PRICE;Z9"));
        igual("PRICE sin id", "ERROR;INVALID_FORMAT", u.process("PRICE"));
        igual("PRICE con campos extra", "ERROR;INVALID_FORMAT", u.process("PRICE;A1;x"));
        igual("Comando en minusculas", "ERROR;INVALID_FORMAT", u.process("price;A1"));
        igual("Comando desconocido", "ERROR;INVALID_FORMAT", u.process("HOLA"));
        igual("Mensaje vacio", "ERROR;INVALID_FORMAT", u.process(""));
    }

    // ------------------------------------------------------------------ 2
    private static void servicios() throws Exception {
        igual("Primera puja por debajo del precio base", "BID_TOO_LOW", puja(new ServicesImpl(), "ana", "A3", 149));
        igual("Primera puja igual al precio base", "OK", puja(new ServicesImpl(), "ana", "A3", 150));

        ServicesImpl s = new ServicesImpl();
        puja(s, "ana", "A3", 150);
        igual("Lider no puede superarse a si mismo", "ALREADY_LEADER", puja(s, "ana", "A3", 300));

        ServicesImpl s2 = new ServicesImpl();
        puja(s2, "ana", "A3", 150);
        igual("Incremento menor a 10 se rechaza", "BID_TOO_LOW", puja(s2, "bob", "A3", 155));
        igual("Despues de un rechazo se puede seguir pujando", "OK", puja(s2, "bob", "A3", 160));

        ServicesImpl s3 = new ServicesImpl();
        puja(s3, "ana", "Z9", 100);
        igual("Despues de UNKNOWN_ITEM se puede seguir pujando", "OK", puja(s3, "ana", "A4", 120));

        igual("Articulo inexistente", "UNKNOWN_ITEM", puja(new ServicesImpl(), "ana", "Z9", 100));
        igual("Monto infinito", "INVALID_DATA", puja(new ServicesImpl(), "ana", "A1", Double.POSITIVE_INFINITY));
        igual("Monto negativo", "INVALID_DATA", puja(new ServicesImpl(), "ana", "A1", -5));
        igual("Sin itemId", "INVALID_DATA", puja(new ServicesImpl(), "ana", null, 100));

        ServicesImpl s4 = new ServicesImpl();
        puja(s4, "ana", "A1", 1000);
        puja(s4, "ana", "A2", 400);
        puja(s4, "bob", "A3", 150);
        List<String> ids = new ArrayList<>();
        for (Item it : s4.myLeads("ana")) ids.add(it.getId());
        check("myLeads devuelve los articulos que lidera", ids.equals(Arrays.asList("A1", "A2")), "se obtuvo " + ids);

        ServicesImpl s5 = new ServicesImpl();
        s5.login("ana");
        String r;
        try {
            s5.login("ana");
            r = "OK";
        } catch (AuctionException e) {
            r = e.getMessage();
        }
        igual("Usuario repetido", "USER_IN_USE", r);
        s5.logout("ana");
        try {
            s5.login("ana");
            r = "OK";
        } catch (AuctionException e) {
            r = e.getMessage();
        }
        igual("Despues de logout el nombre queda libre", "OK", r);

        int malas = 0;
        for (int ronda = 0; ronda < 8; ronda++) {
            ServicesImpl s6 = new ServicesImpl();
            if (carrera(20, i -> s6.login("pepe")) != 1) malas++;
        }
        check("20 hilos hacen LOGIN \"pepe\" a la vez -> solo 1 entra (8 rondas)", malas == 0,
                malas + " de 8 rondas dejaron entrar a mas de uno: condicion de carrera en login");
    }

    interface Accion { void hacer(int i) throws Exception; }

    private static int carrera(int n, Accion a) throws InterruptedException {
        CountDownLatch salida = new CountDownLatch(1);
        AtomicInteger exitos = new AtomicInteger();
        List<Thread> hilos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            Thread t = new Thread(() -> {
                try {
                    salida.await();
                    a.hacer(k);
                    exitos.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
            t.setDaemon(true);
            hilos.add(t);
            t.start();
        }
        salida.countDown();
        for (Thread t : hilos) t.join(3000);
        return exitos.get();
    }

    // ------------------------------------------------------------------ 3
    private static void servidorUdp() throws Exception {
        int puerto = 5100;
        ServicesImpl s = new ServicesImpl();
        UDPController server = new UDPController(s, puerto);
        Thread t = new Thread(server::startService);
        t.setDaemon(true);
        t.start();
        Thread.sleep(400);
        try {
            String r = udp(puerto, "PING", 1500);
            check("PING por la red -> PONG", "PONG".equals(r),
                    r == null ? "no llego respuesta"
                            : "se obtuvo \"" + visible(r) + "\". process(\"PING\") directo si funciona (seccion 1), "
                            + "asi que el problema esta en como se convierten a String los bytes recibidos");
            igual("PRICE por la red", "PRICE;A2;400.0;-", udp(puerto, "PRICE;A2", 1500));
            udp(puerto, "PRICE;A1XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", 1500);
            igual("Mensaje corto despues de uno largo", "PONG", udp(puerto, "PING", 1500));
            try (DatagramSocket a = new DatagramSocket(); DatagramSocket b = new DatagramSocket()) {
                a.setSoTimeout(1500);
                b.setSoTimeout(1500);
                enviar(a, puerto, "PING");
                enviar(b, puerto, "TOP");
                String ra = recibir(a), rb = recibir(b);
                check("Cada cliente recibe SU respuesta", "PONG".equals(ra) && "TOP;-;0".equals(rb),
                        "A recibio \"" + visible(ra) + "\", B recibio \"" + visible(rb) + "\"");
            }
        } finally {
            server.stop();
        }
    }

    private static String udp(int puerto, String msg, int timeout) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeout);
            enviar(s, puerto, msg);
            return recibir(s);
        }
    }

    private static void enviar(DatagramSocket s, int puerto, String msg) throws IOException {
        byte[] d = msg.getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(d, d.length, InetAddress.getByName("localhost"), puerto));
    }

    private static String recibir(DatagramSocket s) throws IOException {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        try {
            s.receive(p);
        } catch (SocketTimeoutException e) {
            return null;
        }
        return new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ 4
    static class Sesion implements Closeable {
        final Socket s;
        final BufferedReader r;
        final BufferedWriter w;

        Sesion(int puerto) throws IOException {
            s = new Socket("localhost", puerto);
            s.setSoTimeout(2000);
            r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        JsonObject enviar(String linea) {
            try {
                w.write(linea);
                w.newLine();
                w.flush();
                String resp = r.readLine();
                if (resp == null) return null;
                JsonElement e = JsonParser.parseString(resp);
                return e.isJsonObject() ? e.getAsJsonObject() : null;
            } catch (Exception e) {
                return null;
            }
        }

        boolean cerradaPorServidor() {
            try {
                return r.readLine() == null;
            } catch (IOException e) {
                return e instanceof SocketException;
            }
        }

        @Override
        public void close() {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String req(String action, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        JsonObject d = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) d.addProperty(kv[i], kv[i + 1]);
        o.add("data", d);
        return o.toString();
    }

    private static boolean ok(JsonObject r) {
        return r != null && r.has("status") && !r.get("status").isJsonNull()
                && "OK".equals(r.get("status").getAsString()) && r.has("data");
    }

    private static String msg(JsonObject r) {
        if (r == null) return "(sin respuesta)";
        try {
            if (ok(r)) return "OK";
            return r.getAsJsonObject("data").get("message").getAsString();
        } catch (Exception e) {
            return r.toString();
        }
    }

    private static void servidorTcp() throws Exception {
        int puerto = 9190;
        ServicesImpl svc = new ServicesImpl();
        TCPController server = new TCPController(svc, puerto);
        ServerSocket ss = campo(server, ServerSocket.class);
        if (ss == null) {
            fallo("TCPController no pudo crear el ServerSocket\n          -> revisa la direccion IP con la que se enlaza");
            return;
        }
        check("ServerSocket enlazado a todas las interfaces (0.0.0.0)", ss.getInetAddress().isAnyLocalAddress(),
                "debe escuchar en 0.0.0.0, no en una IP fija");

        Thread t = new Thread(server::startService);
        t.setDaemon(true);
        t.start();
        Thread.sleep(300);

        try (Sesion s1 = new Sesion(puerto)) {
            JsonObject r = s1.enviar(req("LIST_ITEMS"));
            check("LIST_ITEMS sin login devuelve los 5 articulos",
                    ok(r) && r.getAsJsonObject("data").getAsJsonArray("items").size() == 5, "respuesta: " + r);
            igual("BID sin login", "NOT_LOGGED_IN", msg(s1.enviar(req("BID", "itemId", "A3", "amount", "150"))));
            igual("LOGIN ana", "OK", msg(s1.enviar(req("LOGIN", "user", "ana"))));
            r = s1.enviar(req("BID", "itemId", "A3", "amount", "150"));
            check("BID en la MISMA conexion despues del LOGIN (la sesion recuerda al usuario)",
                    ok(r) && "ana".equals(r.getAsJsonObject("data").getAsJsonObject("item").get("leader").getAsString()),
                    "respuesta: " + r);
            igual("Segundo LOGIN en la misma sesion", "ALREADY_LOGGED_IN", msg(s1.enviar(req("LOGIN", "user", "pepe"))));
            r = s1.enviar(req("MY_LEADS"));
            check("MY_LEADS devuelve A3",
                    ok(r) && r.getAsJsonObject("data").getAsJsonArray("items").size() == 1
                            && "A3".equals(r.getAsJsonObject("data").getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString()),
                    "respuesta: " + r);

            try (Sesion s2 = new Sesion(puerto)) {
                igual("Otra conexion con el mismo usuario", "USER_IN_USE", msg(s2.enviar(req("LOGIN", "user", "ana"))));
                igual("LOGIN sin data", "INVALID_DATA", msg(s2.enviar("{\"action\":\"LOGIN\"}")));
                igual("LOGIN bob", "OK", msg(s2.enviar(req("LOGIN", "user", "bob"))));
                igual("BID con incremento insuficiente", "BID_TOO_LOW", msg(s2.enviar(req("BID", "itemId", "A3", "amount", "155"))));
                igual("BID valido despues de un rechazo", "OK", msg(s2.enviar(req("BID", "itemId", "A3", "amount", "160"))));
                igual("Linea que no es JSON", "INVALID_JSON", msg(s2.enviar("esto no es json")));
                igual("La sesion sigue viva despues de un error", "OK", msg(s2.enviar(req("LIST_ITEMS"))));
                igual("BID con monto no numerico", "INVALID_DATA", msg(s2.enviar(req("BID", "itemId", "A1", "amount", "abc"))));
                igual("BID sin data", "INVALID_DATA", msg(s2.enviar("{\"action\":\"BID\"}")));
                igual("Accion desconocida", "UNKNOWN_ACTION", msg(s2.enviar(req("VOLAR"))));
                igual("LOGOUT", "OK", msg(s2.enviar(req("LOGOUT"))));
                check("Despues de LOGOUT el servidor cierra la conexion", s2.cerradaPorServidor(),
                        "el servidor debe cerrar el socket tras responder LOGOUT");
            }
        }
        Thread.sleep(400);
        try (Sesion s3 = new Sesion(puerto)) {
            igual("Si ana se desconecta de golpe, su nombre queda libre", "OK", msg(s3.enviar(req("LOGIN", "user", "ana"))));
        }
        Thread.sleep(300);

        List<Sesion> sesiones = new ArrayList<>();
        int respondidas = 0;
        try {
            for (int i = 0; i < 10; i++) sesiones.add(new Sesion(puerto));
            for (int i = 0; i < 10; i++) {
                if (ok(sesiones.get(i).enviar(req("LOGIN", "user", "u" + i)))) respondidas++;
            }
        } finally {
            for (Sesion s : sesiones) s.close();
        }
        check("10 postores conectados AL MISMO TIEMPO son atendidos", respondidas == 10,
                "solo " + respondidas + " de 10 sesiones simultaneas recibieron respuesta: el pool se queda corto para conexiones persistentes");
    }

    @SuppressWarnings("unchecked")
    private static <T> T campo(Object o, Class<T> tipo) {
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (tipo.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (T) f.get(o);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ 5
    private static void arranque() throws Exception {
        if (!puertosLibres()) {
            fallo("Los puertos 9090/5000 estan ocupados: cierra tu servidor y vuelve a verificar");
            return;
        }
        final Throwable[] error = new Throwable[1];
        Thread t = new Thread(() -> {
            try {
                co.icesi.subasta.Main.main(new String[0]);
            } catch (Throwable e) {
                error[0] = e;
            }
        });
        t.setDaemon(true);
        t.start();
        Thread.sleep(1500);
        if (error[0] != null) {
            fallo("Main lanzo " + error[0] + "\n          -> si es NullPointerException, el ServerSocket no se pudo crear");
            return;
        }
        String r = udp(5000, "PING", 1500);
        check("Main levanta el UDP en 5000", "PONG".equals(r), "PING a 5000 obtuvo: " + visible(r));
        JsonObject j = null;
        try (Sesion s = new Sesion(9090)) {
            j = s.enviar(req("LIST_ITEMS"));
            check("Main levanta el TCP en 9090", ok(j), "respuesta: " + j);
            if (ok(j) && "PONG".equals(r)) {
                s.enviar(req("LOGIN", "user", "zoe"));
                s.enviar(req("BID", "itemId", "A5", "amount", "600"));
                igual("TCP y UDP comparten el estado (puja por TCP, precio por UDP)", "PRICE;A5;600.0;zoe",
                        udp(5000, "PRICE;A5", 1500));
            }
        } catch (IOException e) {
            fallo("No se pudo conectar a 9090: " + e.getMessage());
        }
    }

    private static boolean puertosLibres() {
        try (ServerSocket a = new ServerSocket(9090); DatagramSocket b = new DatagramSocket(5000)) {
            return a.isBound() && b.isBound();
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 6
    private static void clientePantalla() throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName("pantalla.DisplayClient");
        } catch (ClassNotFoundException e) {
            fallo("No existe la clase pantalla.DisplayClient");
            return;
        }
        Constructor<?> ctor;
        Method query;
        try {
            ctor = cls.getConstructor(String.class, int.class, int.class, int.class);
            query = cls.getMethod("query", String.class);
        } catch (NoSuchMethodException e) {
            fallo("Firma incorrecta: DisplayClient(String host, int port, int timeoutMs, int retries) y String query(String)");
            return;
        }
        check("Existe pantalla.DisplayMain con main()", tieneMain("pantalla.DisplayMain"), null);

        AtomicInteger recibidosLossy = new AtomicInteger();
        AtomicInteger recibidosMudo = new AtomicInteger();
        DatagramSocket eco = new DatagramSocket(5200);
        DatagramSocket lossy = new DatagramSocket(5201);
        DatagramSocket mudo = new DatagramSocket(5202);
        servidorUdpFalso(eco, null, 0);
        servidorUdpFalso(lossy, recibidosLossy, 1);
        servidorUdpFalso(mudo, recibidosMudo, Integer.MAX_VALUE);

        try {
            Object c = ctor.newInstance("localhost", 5200, 1000, 3);
            igual("Envia y recibe", "ECO:PRICE;A1", (String) query.invoke(c, "PRICE;A1"));
            query.invoke(c, "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");
            igual("Respuesta corta despues de una larga", "ECO:PING", (String) query.invoke(c, "PING"));

            Object cl = ctor.newInstance("localhost", 5201, 400, 3);
            String r;
            try {
                r = (String) query.invoke(cl, "TOP");
            } catch (InvocationTargetException e) {
                r = "lanzo " + e.getCause();
            }
            check("Si se pierde el primer datagrama, reintenta y obtiene la respuesta",
                    "ECO:TOP".equals(r) && recibidosLossy.get() == 2,
                    "respuesta: \"" + visible(r) + "\", datagramas enviados: " + recibidosLossy.get());

            Object cm = ctor.newInstance("localhost", 5202, 300, 3);
            long t0 = System.currentTimeMillis();
            Throwable lanzada = null;
            try {
                query.invoke(cm, "PING");
            } catch (InvocationTargetException e) {
                lanzada = e.getCause();
            }
            long dt = System.currentTimeMillis() - t0;
            Thread.sleep(200);
            check("Sin respuesta: reintenta 3 veces y lanza SocketTimeoutException",
                    lanzada instanceof SocketTimeoutException && recibidosMudo.get() == 3 && dt < 3000,
                    "lanzo " + lanzada + ", datagramas enviados: " + recibidosMudo.get() + ", tiempo: " + dt + " ms");
        } finally {
            eco.close();
            lossy.close();
            mudo.close();
        }
    }

    private static void servidorUdpFalso(DatagramSocket sock, AtomicInteger contador, int ignorarPrimeros) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (!sock.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    int n = contador == null ? 1 : contador.incrementAndGet();
                    if (n <= ignorarPrimeros) continue;
                    String m = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                    byte[] out = ("ECO:" + m).getBytes(StandardCharsets.UTF_8);
                    sock.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                } catch (IOException ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ 7
    private static void clientePostor() throws Exception {
        Class<?> reqCls, cliCls;
        try {
            reqCls = Class.forName("postor.Request");
            Class.forName("postor.Response");
            cliCls = Class.forName("postor.AuctionClient");
        } catch (ClassNotFoundException e) {
            fallo("No existe " + e.getMessage() + " (se necesitan postor.Request, postor.Response y postor.AuctionClient)");
            return;
        }
        Constructor<?> ctor;
        Method connect, send, close;
        try {
            ctor = cliCls.getConstructor(String.class, int.class);
            connect = cliCls.getMethod("connect");
            send = cliCls.getMethod("send", reqCls);
            close = cliCls.getMethod("close");
        } catch (NoSuchMethodException e) {
            fallo("Firma incorrecta: AuctionClient(String,int), void connect(), Response send(Request), void close()");
            return;
        }
        check("Existe postor.PostorMain con main()", tieneMain("postor.PostorMain"), null);

        String[] respuestas = {
                "{\"status\":\"OK\",\"data\":{\"user\":\"ana\"}}",
                "{\"status\":\"OK\",\"data\":{\"items\":[{\"id\":\"A1\",\"name\":\"Portatil\",\"basePrice\":1000.0,\"currentPrice\":1000.0,\"bids\":0}]}}",
                "{\"status\":\"ERROR\",\"data\":{\"message\":\"BID_TOO_LOW\"}}"
        };
        List<String> lineas = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger conexiones = new AtomicInteger();
        AtomicInteger eofs = new AtomicInteger();
        ServerSocket falso = new ServerSocket(9200);
        Thread acept = new Thread(() -> {
            while (!falso.isClosed()) {
                try {
                    Socket s = falso.accept();
                    conexiones.incrementAndGet();
                    Thread h = new Thread(() -> {
                        try (Socket sc = s) {
                            sc.setSoTimeout(5000);
                            BufferedReader r = new BufferedReader(new InputStreamReader(sc.getInputStream(), StandardCharsets.UTF_8));
                            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(sc.getOutputStream(), StandardCharsets.UTF_8));
                            int n = 0;
                            while (true) {
                                String l = r.readLine();
                                if (l == null) {
                                    eofs.incrementAndGet();
                                    return;
                                }
                                lineas.add(l);
                                if (n >= respuestas.length) return;
                                w.write(respuestas[n++]);
                                w.newLine();
                                w.flush();
                            }
                        } catch (IOException ignored) {
                        }
                    });
                    h.setDaemon(true);
                    h.start();
                } catch (IOException ignored) {
                }
            }
        });
        acept.setDaemon(true);
        acept.start();

        try {
            Object cliente = ctor.newInstance("localhost", 9200);
            conLimite(() -> connect.invoke(cliente), 3000, null);

            Object rq = nuevoRequest(reqCls, "LOGIN");
            datos(rq).put("user", "ana");
            Object r1 = conLimite(() -> send.invoke(cliente, rq), 3000, null);
            String l0 = lineas.isEmpty() ? null : lineas.get(0);
            check("Envia el JSON en una linea terminada en \\n (con flush)", l0 != null,
                    "el servidor no recibio una linea completa");
            if (l0 != null) {
                JsonObject j = null;
                try {
                    j = JsonParser.parseString(l0).getAsJsonObject();
                } catch (Exception ignored) {
                }
                check("La peticion es JSON con action y data", j != null && "LOGIN".equals(j.get("action").getAsString())
                        && "ana".equals(j.getAsJsonObject("data").get("user").getAsString()), "se recibio: " + l0);
            }
            check("Deserializa la respuesta (status OK)", r1 != null && "OK".equals(campoPublico(r1, "status")),
                    "se obtuvo: " + describir(r1));

            Object r2 = conLimite(() -> send.invoke(cliente, nuevoRequest(reqCls, "LIST_ITEMS")), 3000, null);
            Object r3 = conLimite(() -> send.invoke(cliente, nuevoRequest(reqCls, "BID")), 3000, null);
            check("Recibe data con la lista de items", r2 != null && campoPublico(r2, "data") instanceof Map
                    && ((Map<?, ?>) campoPublico(r2, "data")).get("items") instanceof List, "se obtuvo: " + describir(r2));
            check("Recibe errores (status ERROR y message)", r3 != null && "ERROR".equals(campoPublico(r3, "status"))
                    && "BID_TOO_LOW".equals(String.valueOf(((Map<?, ?>) campoPublico(r3, "data")).get("message"))),
                    "se obtuvo: " + describir(r3));
            check("Usa UNA sola conexion para toda la sesion (persistente)", conexiones.get() == 1 && lineas.size() == 3,
                    "conexiones abiertas: " + conexiones.get() + ", peticiones recibidas: " + lineas.size());

            String resultado = conLimite(() -> {
                try {
                    Object x = send.invoke(cliente, nuevoRequest(reqCls, "LIST_ITEMS"));
                    return "devolvio " + describir(x);
                } catch (InvocationTargetException e) {
                    return e.getCause() instanceof IOException ? "IOException" : "lanzo " + e.getCause();
                }
            }, 4000, "se quedo colgado");
            check("Si el servidor cierra la conexion, send lanza IOException", "IOException".equals(resultado),
                    resultado + " (debe detectar readLine()==null y lanzar IOException)");

            int antes = eofs.get();
            Object otro = ctor.newInstance("localhost", 9200);
            conLimite(() -> connect.invoke(otro), 3000, null);
            conLimite(() -> close.invoke(otro), 3000, null);
            Thread.sleep(500);
            check("close() cierra el socket", eofs.get() == antes + 1, "el servidor no detecto el cierre de la conexion");
        } finally {
            falso.close();
        }
    }

    private static Object nuevoRequest(Class<?> cls, String action) throws Exception {
        Object o;
        try {
            o = cls.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            o = cls.getDeclaredConstructor(String.class).newInstance(action);
        }
        cls.getField("action").set(o, action);
        if (cls.getField("data").get(o) == null) cls.getField("data").set(o, new HashMap<String, String>());
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> datos(Object rq) throws Exception {
        return (Map<String, String>) rq.getClass().getField("data").get(rq);
    }

    private static Object campoPublico(Object o, String nombre) {
        try {
            return o.getClass().getField(nombre).get(o);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describir(Object resp) {
        if (resp == null) return "null (no respondio, se colgo o lanzo excepcion)";
        return "status=" + campoPublico(resp, "status") + ", data=" + campoPublico(resp, "data");
    }

    private static boolean tieneMain(String clase) {
        try {
            Method m = Class.forName(clase).getMethod("main", String[].class);
            return java.lang.reflect.Modifier.isStatic(m.getModifiers());
        } catch (Exception e) {
            return false;
        }
    }
}
