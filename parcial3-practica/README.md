# Parcial 2 (práctica v2): Subasta en línea sobre TCP y UDP

**Computación en Internet I**

| | |
|---|---|
| **Duración** | 2 horas (el parcial real está pensado para 1 h 15) |
| **Modalidad** | Individual |
| **Puertos** | TCP **9090** (postores) · UDP **5000** (pantallas) |

---

## 1. Contexto

Un sistema de subastas tiene 5 artículos. Hay dos tipos de cliente:

- **Postores** (cliente `postor`): se conectan por **TCP** y mantienen la conexión abierta **durante toda su sesión**. Inician sesión con un nombre, ven los artículos y pujan. Los mensajes son **JSON**.
- **Pantallas** (cliente `pantalla`): están en el salón de la subasta. Consultan por **UDP** el precio actual de un artículo o cuál es el más disputado. Como UDP no garantiza la entrega, la pantalla **reintenta** si no le llega respuesta.

Un solo servidor atiende los dos canales a la vez sobre **el mismo estado**: una puja hecha por TCP se tiene que ver de inmediato en las pantallas por UDP.

| Id | Artículo | Precio base |
|---|---|---|
| A1 | Portatil | 1000 |
| A2 | Monitor 27 | 400 |
| A3 | Teclado mecanico | 150 |
| A4 | Audifonos | 120 |
| A5 | Silla gamer | 600 |

### Reglas de la subasta
- La **primera** puja de un artículo debe ser **≥ precio base**.
- Las siguientes deben ser **≥ precio actual + 10**.
- El líder actual **no puede** volver a pujar por el mismo artículo (`ALREADY_LEADER`).
- El monto debe ser un número finito mayor que 0.

---

## 2. Estructura del proyecto

```text
├── server/        Servidor (ENTREGADO: incompleto y con defectos)
│   └── src/main/java/co/icesi/subasta/
│       ├── Main.java
│       ├── controllers/   TCPController, UDPController
│       │   └── dtos/      Request, Response
│       ├── model/         Item
│       └── services/      ServicesImpl, AuctionException
├── pantalla/      Cliente UDP (VACÍO: lo construye usted)
├── postor/        Cliente TCP (VACÍO: lo construye usted)
└── verificador/   Pruebas automáticas (NO modificar)
```

---

## 3. Protocolo UDP (pantallas → servidor, puerto 5000)

Los mensajes son texto con campos separados por `;`. Cada datagrama lleva una petición y el servidor responde con **un** datagrama al emisor. Los comandos van **en mayúsculas exactas**.

| Petición | Respuesta |
|---|---|
| `PING` | `PONG` |
| `PRICE;<id>` | `PRICE;<id>;<precioActual>;<lider>` (lider = `-` si no hay pujas). Ej: `PRICE;A1;1200.0;ana` |
| `TOP` | `TOP;<id>;<numPujas>` del artículo con **más pujas**. En empate gana el que va primero en el catálogo. Si no hay pujas: `TOP;-;0` |
| `PRICE;<id>` con un id que no existe | `ERROR;UNKNOWN_ITEM` |
| Cualquier otra cosa (comando desconocido, vacío, campos de más o de menos) | `ERROR;INVALID_FORMAT` |

---

## 4. Protocolo TCP (postores → servidor, puerto 9090)

- Cada mensaje es **un objeto JSON en una línea terminada en `\n`**.
- **Conexión persistente:** el postor abre **una** conexión y envía por ella todas sus peticiones, una tras otra. Por cada petición recibe exactamente una respuesta. La conexión se cierra cuando el postor envía `LOGOUT` (el servidor responde y cierra) o cuando el postor se desconecta.
- El servidor **recuerda qué usuario inició sesión en cada conexión**.

**Petición:** `{"action":"BID","data":{"itemId":"A1","amount":"1200"}}` (todos los valores de `data` son strings)

**Respuesta OK:** `{"status":"OK","data":{ ... }}`
**Respuesta con error:** `{"status":"ERROR","data":{"message":"<CODIGO>"}}`

| `action` | `data` | Requiere login | `data` si OK | Códigos de error |
|---|---|---|---|---|
| `LOGIN` | `user` | no | `user` | `INVALID_DATA` (vacío o sin data) · `USER_IN_USE` (otro postor conectado ya usa ese nombre) · `ALREADY_LOGGED_IN` (esta conexión ya inició sesión) |
| `LIST_ITEMS` | — | no | `items`: arreglo de artículos | — |
| `BID` | `itemId`, `amount` | **sí** | `item`: el artículo actualizado | `NOT_LOGGED_IN` · `INVALID_DATA` (falta algún campo o el monto no es válido) · `UNKNOWN_ITEM` · `ALREADY_LEADER` · `BID_TOO_LOW` |
| `MY_LEADS` | — | **sí** | `items`: artículos que el usuario va ganando | `NOT_LOGGED_IN` |
| `LOGOUT` | — | no | — (después el servidor cierra la conexión) | — |
| otra acción | | | | `UNKNOWN_ACTION` |
| línea que no es JSON | | | | `INVALID_JSON` |

Un artículo en JSON se ve así (si `leader` es null, no aparece):
```json
{"id":"A1","name":"Portatil","basePrice":1000.0,"currentPrice":1200.0,"leader":"ana","bids":1}
```

---

## 5. Requisitos del servidor

1. `Main` deja funcionando **los dos** servidores al mismo tiempo, sobre la **misma** instancia de `ServicesImpl`.
2. El TCP acepta conexiones en **todas las interfaces** de la máquina.
3. El servidor debe atender **al menos 10 postores conectados al mismo tiempo**.
4. **Un error en una petición nunca cierra la sesión ni la deja sin respuesta.** El servidor responde el código de error y sigue atendiendo esa conexión.
5. Si un postor se desconecta (con o sin `LOGOUT`), su nombre de usuario **queda libre** para volver a usarse.
6. El estado es compartido entre el hilo UDP y los hilos TCP. Aunque haya peticiones simultáneas:
   - dos conexiones nunca pueden quedar con el mismo usuario;
   - una puja rechazada nunca debe impedir que se sigan procesando las demás pujas.

---

## 6. Tareas

### Parte A: Servidor (≈ 55 min)
El código de `server/` compila, pero **está incompleto y tiene varios defectos**. Encuéntrelos y corríjalos hasta que cumpla las secciones 3, 4 y 5. No cambie los nombres de clases, paquetes ni las firmas públicas existentes.

### Parte B: Cliente pantalla, UDP (≈ 15 min)
En el módulo `pantalla/`, paquete `pantalla`:
- `DisplayClient(String host, int port, int timeoutMs, int retries)`.
- `String query(String message) throws IOException`: envía el datagrama y espera la respuesta. Si no llega en `timeoutMs`, **reenvía**, hasta `retries` envíos en total. Si ninguno recibe respuesta, lanza `SocketTimeoutException`.
- `DisplayMain`: menú con precio de un artículo, artículo más disputado, PING y salir. Usa 1000 ms y 3 reintentos, muestra las respuestas de forma legible y, si se agotan los reintentos, muestra "Servidor no disponible".

### Parte C: Cliente postor, TCP persistente (≈ 30 min)
En el módulo `postor/`, paquete `postor`:
- `Request` y `Response`, con la misma estructura de los DTO del servidor (campos públicos y constructor vacío).
- `AuctionClient(String host, int port)` con:
  - `void connect() throws IOException`: abre **la** conexión.
  - `Response send(Request r) throws IOException`: envía por **esa misma** conexión y lee una respuesta. Si el servidor cerró la conexión, lanza `IOException`.
  - `void close()`.
- `PostorMain`: se conecta, pide el nombre hasta que el LOGIN sea exitoso, y luego muestra el menú:
  1. Ver artículos (tabla con id, nombre, precio actual, líder y número de pujas).
  2. Pujar.
  3. Artículos que voy ganando.
  4. Salir (envía LOGOUT).

  Muestra los códigos de error que llegan del servidor y no se cae si se pierde la conexión.

### Parte D: Preguntas (≈ 10 min)
1. El servidor entregado usaba un pool fijo de 5 hilos. En el Buscaminas ese pool alcanzaba para muchos jugadores, pero aquí no. ¿Por qué? ¿Qué le pasa exactamente al postor número 6?
2. ¿Qué pasa con un `Semaphore` si se lanza una excepción entre `acquire()` y `release()`? ¿Por qué con `synchronized` no ocurre lo mismo?
3. ¿Por qué el usuario de la sesión debe guardarse **fuera** del ciclo que lee las peticiones? Si la conexión fuera corta (una petición por conexión, como en el Buscaminas), ¿cómo sabría el servidor quién está pujando?
4. Justifique por qué las pantallas usan UDP y los postores TCP.

---

## 7. Cómo ejecutar y verificar

```bash
./gradlew :verificador:run --console=plain   # pruebas automáticas (apague antes su servidor)
./gradlew :server:run --console=plain        # terminal 1
./gradlew :postor:run --console=plain        # terminales 2 y 3 (dos postores)
./gradlew :pantalla:run --console=plain      # terminal 4
```
En Windows use `gradlew.bat` en lugar de `./gradlew`.

Sin Gradle: `javac -cp gson.jar -d out $(find . -name "*.java")` y luego `java -cp out:gson.jar verificador.Verificador` (en Windows, `;` en lugar de `:`).

El verificador trae **70 pruebas**. Mientras haya partes sin hacer, algunas secciones se detienen antes y el total que muestra es menor.

---

## 8. Rúbrica (0.0 a 5.0)

| Componente | Peso | Evidencia |
|---|---|---|
| Lógica y servidor UDP | 0.7 | Secciones 1 y 3 |
| Servicios y concurrencia | 0.8 | Sección 2 |
| Servidor TCP: sesión persistente y robustez | 1.2 | Sección 4 |
| Arranque conjunto | 0.3 | Sección 5 |
| Cliente pantalla (UDP con reintentos) | 0.6 | Sección 6 + demo |
| Cliente postor (TCP persistente) | 1.0 | Sección 7 + demo con dos postores a la vez |
| Preguntas | 0.4 | Parte D |
| **Total** | **5.0** | |
