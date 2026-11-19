# EconomyCore

Plataforma económica y multimercado para servidores Minecraft (Paper/Spigot 1.20+, Java 17).
EconomyCore guarda saldos de forma segura, ejecuta compras con protección contra duplicados y pérdidas de dinero,
y ofrece una API para que **otros plugins** creen sus propios mercados y vendan sus propios productos.

> **Estado actual (v0.x): es el motor y la API. NO es todavía un plugin de tiendas listo para jugadores.**
> Mira la sección [Qué funciona y qué falta](#qué-funciona-y-qué-falta) antes de instalarlo.

---

## Instalación

1. Descarga `EconomyCore-X.Y.Z.jar` desde la pestaña **Releases** del repositorio.
2. Cópialo a la carpeta `plugins/` de tu servidor y reinicia.
3. Se crea `plugins/EconomyCore/config.yml` y la base de datos `economy.db` (SQLite) o se conecta a MySQL.

Requisitos: Java 17+, Paper o Spigot 1.20+. SQLite y MySQL/MariaDB usan los drivers JDBC que ya incluye el servidor.

---

## Comandos

| Comando | Permiso | Qué hace |
|---|---|---|
| `/markets` o `/markets list` | `economy.market.use` (todos por defecto) | Muestra los mercados registrados en este momento (por otros plugins). |

Eso es todo lo que existe hoy. **No hay todavía** `/markets open|create|delete|reload|info|editor|enable|disable`, ni `/balance`, `/pay`, `/eco`, ni GUI.

## Permisos

| Permiso | Por defecto | Uso |
|---|---|---|
| `economy.market.use` | todos | Usar `/markets`. |
| `economy.market.admin` | op | Reservado para la administración (aún sin comandos). |

Los permisos por mercado (`economy.market.<id>`) se aplican mediante *requisitos* (ver más abajo), no automáticamente.

---

## Configuración (`config.yml`)

```yaml
storage:
  type: sqlite            # sqlite | mysql
  mysql: { host, port, database, user, password }
currencies:               # monedas ilimitadas
  coins: { name: Coins, symbol: "$", decimals: 2 }
  gems:  { name: Gems,  symbol: "G", decimals: 0 }
recovery-interval-seconds: 60   # cada cuánto se reintentan entregas pendientes
max-delivery-attempts: 5        # reintentos automáticos antes de dejarla para revisión manual
```

- Las cantidades se guardan como **enteros en la unidad mínima** (con `decimals: 2`, `12.50` = `1250`). Nunca se usa `double`.
- El id de moneda: minúsculas, números y `_` (máx. 32). Se admiten 0–8 decimales.
- Cambiar los decimales de una moneda con datos existentes cambia su valor: no lo hagas.

---

## Qué hace el plugin (funcionamiento)

### 1. Economía central
- Saldos por cuenta y moneda (cuenta = UUID de jugador en texto, o ids de sistema como `treasury`).
- Depositar, retirar y transferir con **idempotencia**: cada operación lleva un `opId`; repetirlo no aplica dos veces.
- Libro mayor (historial) de cada movimiento, y comprobación de desbordamiento de saldo.
- Todo es transaccional: o se aplica entero o no se aplica nada.

### 2. Motor multimercado
Cada **mercado** es independiente: id, nombre, modo, impuestos, moneda(s) admitidas, requisitos de acceso,
ventana de apertura/cierre y rotación. Se pueden crear mercados ilimitados desde otros plugins; uno que falle no afecta a los demás.

Modos con compra funcionando: `BUY_SHOP`, `BUY_SELL`, `OFFERS`, `BLACK_MARKET`, `CUSTOM`.
Los modos `SELL_SHOP`, `PLAYER_MARKET`, `AUCTION`, `ORDERS`, `EXCHANGE` existen como valores pero devuelven `MODE_UNSUPPORTED` al comprar.

### 3. Productos
Cada producto tiene: id, categoría, nombre, moneda, precio, recompensa, stock, límite por jugador,
cantidad máxima por compra, requisitos, si es secreto, si está activo, si siempre es visible en rotación,
destinatario del pago, descuento temporal.

- **Stock:** `-1` = ilimitado; si no, existencias globales. Se reserva al comprar y se repone si la compra falla o se reembolsa.
- **Límite por jugador:** máximo de unidades que un mismo jugador puede comprar.
- **Secreto:** no aparece en el catálogo público; solo lo ven quienes cumplen sus requisitos.

### 4. Precios
| Tipo | Comportamiento |
|---|---|
| `FIXED` | Precio fijo. |
| `RANDOM` | Entre un mínimo y un máximo; estable durante la ventana de rotación (o el día si no hay rotación). |
| `STOCK` | Sube hasta `factor` % del precio base según el stock consumido. |
| `DEMAND` | Sube un % por cada unidad comprada en la ventana, con tope opcional. |
| Descuento | Porcentaje con fecha de fin, aplicado sobre cualquiera de los anteriores. |

El precio **siempre lo calcula el servidor**; quien compra no puede fijarlo. El impuesto del mercado (`taxBps`, en
centésimas de %) se suma al total y va a la cuenta `treasury`; el resto va al `payee` del producto o al sumidero `sink:<mercado>`.

### 5. Mercado negro (rotación)
Un mercado con `Rotation(intervalo, slots)` muestra solo `slots` productos elegidos de forma determinista por ventana de tiempo
(más los marcados `alwaysVisible`). Lo que no está en la rotación actual no se puede comprar (`NOT_AVAILABLE`).
Combínalo con productos secretos, stock limitado, límites por jugador, precios aleatorios/dinámicos y ventanas de apertura para el efecto “mercado negro”.

### 6. Compra segura (máquina de estados)
```
CREATED → VALIDATING → RESERVED → PAID → DELIVERING → COMPLETED
                          │          │         └→ PENDING_RECOVERY → (reintento) → COMPLETED
                          ↓          ↓                         └→ REFUNDED
                        FAILED     (fondos en custodia)
```
1. **VALIDATING:** mercado activo y abierto, producto disponible, cantidad, moneda, requisitos, validación del proveedor, y que exista un entregador de la recompensa.
2. **RESERVED:** se reserva stock y límite en una transacción atómica (el último artículo no se vende dos veces).
3. **PAID:** el dinero pasa a una cuenta de **custodia** (`escrow:<compra>`). Si no hay fondos → `FAILED`, sin cobro y con stock restaurado.
4. **DELIVERING:** se ejecuta la recompensa **solo después** de confirmar el pago.
5. **COMPLETED:** la custodia se libera al destinatario y a tesorería.
6. Si la entrega falla temporalmente → `PENDING_RECOVERY` (se reintenta sola cada `recovery-interval-seconds`, hasta `max-delivery-attempts`, **sin volver a cobrar**).
   Si se rechaza definitivamente → `REFUNDED` (dinero y stock devueltos).

Cada compra lleva un `purchaseId` (clave de idempotencia): repetir la misma petición nunca cobra ni entrega dos veces.
Si el servidor se cae a mitad, `recover()` limpia o completa las compras pendientes al reiniciar.

Motivos de fallo (`note` de la compra): `MARKET_NOT_FOUND`, `MARKET_DISABLED`, `MARKET_CLOSED`, `MODE_UNSUPPORTED`,
`PRODUCT_NOT_FOUND`, `PRODUCT_DISABLED`, `NOT_AVAILABLE`, `QUANTITY_TOO_HIGH`, `UNKNOWN_CURRENCY`, `CURRENCY_NOT_ACCEPTED`,
`ACCESS_DENIED`, `NO_REWARD_HANDLER`, `PROVIDER_UNAVAILABLE`, `PROVIDER_REJECTED:<motivo>`, `PROVIDER_ERROR`,
`OUT_OF_STOCK`, `LIMIT_REACHED`, `PRICE_OVERFLOW`, `INSUFFICIENT_FUNDS`.

### 7. Requisitos de acceso
Un mercado o producto lista requisitos con formato `tipo:argumento` (ej. `perm:economy.market.blackmarket`).
Cada tipo lo resuelve un `ConditionProvider` registrado por un plugin. **Un tipo sin proveedor, o que lance error, deniega el acceso.**

### 8. Proveedores y recompensas (para otros plugins)
- **ItemProvider:** un plugin publica productos en un mercado (mascotas, armas, cosméticos…). Si falla, se conservan sus productos anteriores y el resto del sistema no se ve afectado.
- **RewardHandler:** entrega la recompensa (item, comando, mascota…). Debe ser **idempotente**: recibe una `idempotencyKey` estable por compra, porque en una recuperación puede llamarse otra vez.
  Devuelve `DELIVERED`, `RETRY` (fallo temporal) o `FAILED` (rechazo definitivo → reembolso).

---

## Qué funciona y qué falta

**Funciona y está probado (23 pruebas automáticas, con almacenamiento en memoria):** dinero exacto, idempotencia, concurrencia
(transferencias, último artículo, mismo `purchaseId`), pagos, impuestos, custodia, reembolsos, recuperación tras caída, límites, rotación,
precios, proveedores externos, requisitos, recarga y desactivación de mercados, búsqueda y paginación con miles de productos.

**Implementado pero SIN probar en ejecución:** el almacenamiento SQLite/MySQL (`JdbcStorage`) y el arranque del plugin en un servidor real.

**Importante – lo que el plugin por sí solo NO trae:**
- No registra ningún `RewardHandler`, así que **sin otro plugin que lo aporte, las compras fallan con `NO_REWARD_HANDLER`**.
- No registra ningún `ConditionProvider`: los requisitos deniegan el acceso hasta que otro plugin los resuelva.
- No carga mercados desde YAML (`markets/<id>/market.yml`), no tiene GUI de tienda ni panel administrativo.
- No tiene comandos de economía para jugadores (`/balance`, `/pay`) ni de administración (`/eco`).
- No hay subastas, mercado de jugadores, órdenes, intercambio ni tienda de venta.
- No hay stock por mundo/servidor/grupo, ni integración con Citizens ni PlaceholderAPI/Vault.

Roadmap detallado y estado: `PROGRESS.md`.

---

## API para desarrolladores

Declara la dependencia en tu `plugin.yml` (`depend: [EconomyCore]`) y compila contra el JAR de EconomyCore (solo necesitas el paquete `com.economycore.api`).

```java
MarketService markets = Bukkit.getServicesManager().load(MarketService.class);
EconomyService eco    = Bukkit.getServicesManager().load(EconomyService.class);

// 1) Entregador de recompensas (idempotente)
markets.registerRewardHandler(new RewardHandler() {
    public String type() { return "pet"; }
    public DeliveryResult deliver(DeliveryContext ctx) {
        // ctx.idempotencyKey(): si ya entregaste esta clave, devuelve delivered() sin duplicar
        petPlugin.giveOnce(ctx.idempotencyKey(), ctx.buyer(), ctx.reward().data().get("pet"));
        return DeliveryResult.delivered();
    }
});

// 2) Mercado con un producto
Product legendary = Product.builder("legendary_pet", "gems", PriceSpec.fixed(500),
        RewardSpec.of("pet", Map.of("pet", "dragon")))
    .category("Mascotas").name("Mascota legendaria").stock(10).perPlayerLimit(1).build();
markets.registerMarket(Market.simple("pets_market", "Mercado de mascotas", Market.Mode.CUSTOM), List.of(legendary));

// 3) (Opcional) publicar productos mediante un ItemProvider
markets.attachProvider("pets_market", myItemProvider);

// 4) Comprar (purchaseId = clave de idempotencia; reutilízala en reintentos)
PurchaseRecord r = markets.purchase(new PurchaseRequest(UUID.randomUUID().toString(),
        "pets_market", "legendary_pet", player.getUniqueId().toString(), 1));
// r.state() == COMPLETED, o FAILED/REFUNDED/PENDING_RECOVERY con r.note() explicando el motivo

// 5) Economía directa (opId único por operación)
eco.deposit("quest-123-reward", uuid, new Money("coins", 1000), "Misión completada");
long saldo = eco.balance(uuid, "coins");
```

Otras llamadas de `MarketService`: `setMarketEnabled`, `unregisterMarket`, `upsertProducts`, `removeProduct`, `visibleProducts`,
`search`, `registerConditionProvider`, `refreshProvider`, `detachProvider`, `purchasesOf`, `retryDelivery`, `refund`, `restock`, `addListener`.
`purchase(...)` y las llamadas de economía tocan la base de datos: **no las ejecutes en el hilo principal** con carga alta.

---

## Compilar y publicar

```
gradle check build        # → economycore-plugin/build/libs/EconomyCore-<versión>.jar
```

Publicar una versión (GitHub Actions compila, prueba y crea el Release con el JAR):
```
git tag v1.0.0
git push origin v1.0.0
```
Tags con sufijo (`v1.0.0-beta.1`) se publican como pre-release. Si compilación o pruebas fallan, no se crea el Release.
No hacen falta secretos: se usa `GITHUB_TOKEN`.
