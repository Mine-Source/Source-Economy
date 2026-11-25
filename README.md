# EconomyCore

Plugin de economía avanzada para **Paper / Spigot 1.21+** (Java 21). Un solo JAR, sin NMS, sin panel web: **todo se administra dentro del juego** con menús (`/eco`).

Incluye: economía multimoneda, tienda, mercados personalizados, casa de subastas, bazaar, comerciantes para NPCs, intercambio entre jugadores, tiendas de jugadores, banco, bolsa de valores, empresas, tesorería e impuestos, y analítica. Se integra con **Vault** (opcional).

---

## Índice
1. [Instalación](#instalación)
2. [Conceptos básicos](#conceptos-básicos)
3. [Módulos](#módulos)
4. [Comandos](#comandos)
5. [Permisos](#permisos)
6. [Configuración](#configuración)
7. [Integración con Vault y API](#integración-con-vault-y-api)
8. [Integración con NPCs](#integración-con-npcs)
9. [Datos, backups y seguridad](#datos-backups-y-seguridad)
10. [Compilar y publicar](#compilar-y-publicar)

---

## Instalación
1. Pon `EconomyCore-<versión>.jar` en `plugins/`.
2. Reinicia el servidor. Se crean `config.yml`, `messages.yml` y la base de datos en `plugins/EconomyCore/data/economy.db`.
3. Entra y escribe `/eco`. Si tienes `economycore.admin` verás el centro administrativo con un botón por cada módulo.

Al actualizar el JAR, **tus datos y tu configuración se conservan**: las claves nuevas se añaden solas a `config.yml` y `messages.yml` sin pisar tus valores, y la base de datos se migra con backup previo.

Requisitos: Paper/Spigot 1.21+, Java 21. El driver SQLite lo trae el propio servidor. Vault es opcional.

---

## Conceptos básicos

**Monedas.** Puedes crear las que quieras (`/eco` → Monedas). Cada una tiene id, nombre, símbolo, decimales, saldo inicial y si se puede transferir entre jugadores. Hay una moneda por defecto (`economy.default-currency`).

**Cuentas.** El dinero pertenece a una cuenta: jugador, cuenta de banco, empresa, tesorería, sistema o depósito en garantía (escrow). Solo la cuenta del sistema puede quedar en negativo; el resto nunca baja de cero.

**Dinero seguro.** Se usa `BigDecimal` en todo. Cada operación es una transacción atómica de base de datos: o se aplica entera o no se aplica nada.

**Comisiones e impuestos.** Muchas operaciones (ventas, subastas, bazaar, bolsa, tiendas de jugadores, transferencias) cobran una comisión y/o un impuesto progresivo. Todo lo cobrado va a la **tesorería**.

**Buzón (`/mailbox`).** Cuando un objeto no puede entregarse (inventario lleno, jugador desconectado, fallo) se guarda en el buzón y se reclama después. El registro del objeto se escribe en la misma transacción que el movimiento de dinero, así que **no se pierden ni se duplican objetos**.

**Menús.** Todos los menús tienen paginación, búsqueda (cuando aplica), botón de volver y cerrar, y confirmación en acciones destructivas. Los valores se editan escribiendo en el chat (se puede cancelar).

---

## Módulos

Cada módulo se puede activar o desactivar en `config.yml` → `modules`.

### Economía
- Saldos por moneda, `/balance`, `/pay`, `/baltop`.
- Comisión de transferencia (`economy.transfer-fee-percent`) y mínimo (`economy.min-transfer`).
- Historial de transacciones por jugador y global, y registro de auditoría de acciones administrativas.
- Administración de jugadores: dar, quitar, establecer saldos (`/eco give|take|set`).
- **Impuestos progresivos** por tramos y tipo (ventas, ingresos, transferencias, patrimonio), con **exenciones** por jugador.

### Tienda (`/shop`, `/sellall`)
- Categorías, compra y venta de objetos con precios, stock, descuentos y límites.
- `/sellall` vende todo lo vendible del inventario de una vez.
- La tienda es un mercado especial; se edita desde `/eco` → Tienda.

### Mercados personalizados (`/markets`)
- Crea todos los mercados que quieras. Cada uno tiene: productos, categorías, moneda propia, impuesto, comisión, permiso de acceso, **horario** (días y horas, con zona horaria `markets.timezone`) y **rotación** automática de productos (tamaño e intervalo).
- Precios de compra/venta, stock y límites por producto.

### Casa de subastas (`/ah`)
- **BIN** (compra inmediata) y **subastas** con pujas.
- `/ah sell <precio> [horas] [bin|auction]` vende el objeto de tu mano.
- Filtros por tipo y búsqueda, **favoritos**, comisión y tarifa de publicación.
- **Anti-sniping**: una puja en los últimos segundos alarga la subasta (hasta un máximo de extensiones).
- Límite de publicaciones configurable y por permiso (`economycore.ah.limit.N`).
- Lo ganado, lo vendido y los objetos no vendidos se reclaman desde el menú.

### Bazaar (`/bazaar`)
- Mercado de **órdenes de compra y de venta** con libro de órdenes por producto.
- Prioridad precio-tiempo, **ejecución parcial y automática**, órdenes de mercado y límite.
- El dinero de una orden de compra queda **reservado** (escrow) y se devuelve si cancelas.
- Comisión configurable y máximo de órdenes por jugador.

### Comerciantes (`/trader`)
- **Ventana nativa de aldeano** para los trueques de objeto por objeto (hasta dos objetos de costo). El cliente rellena los ingredientes al elegir un trato, y al sacar el resultado se ejecuta la transacción real con sus límites.
- **Menú propio** para los tratos con dinero o comandos de recompensa, que la ventana de aldeano no puede mostrar. Cada coste indica con ✔/✖ si lo cumples.
- Interfaz por comerciante: **Automático** (aldeano si todas las ofertas son de objetos, menú si no), **Ventana de aldeano** (solo objetos) o **Menú** (todo). Se cambia en el editor y se puede forzar con `/trader open <id> [jugador] <villager|menu>`.
- **Editor visual de ofertas** (`/eco` → Comerciantes → clic derecho → Ofertas): toma un objeto con el cursor y haz clic en la ranura de costo 1, costo 2 o resultado. Clic izq/der cambia la cantidad (shift: 16) y shift + clic der la quita.
- Opciones por oferta: costo y pago en dinero, moneda, usos por jugador, stock global, espera entre usos, permiso, comandos de recompensa (consola, `%player%`, varios separados por `||`) y activar/desactivar.
- Lista de ofertas: clic izq editar, clic der duplicar, shift izq / der subir / bajar.
- Se vinculan a **NPCs de otros plugins** mediante un identificador. No crea NPCs ni depende de Citizens. Ver [Integración con NPCs](#integración-con-npcs).

### Intercambio entre jugadores (`/trade <jugador>`)
- GUI bilateral: cada jugador ofrece objetos y dinero.
- Flujo: **ofertas → aceptan ambos → bloqueo de ofertas → confirmación final**. Cualquier cambio reinicia las aceptaciones.
- Retardo anti-error antes de confirmar, distancia máxima opcional, cancelación, cancelación automática por inactividad o desconexión, e historial (`/trade history`).
- Al ejecutar se **vuelve a verificar todo** (objetos y dinero) en una transacción, lo que evita duplicaciones y exploits.

### Tiendas de jugadores (`/pshop`)
- Cada jugador crea sus tiendas (coste y límite configurables y por permiso `economycore.pshop.limit.N`).
- Productos con precio y stock, comisión para el servidor, y retirada de stock y ganancias.

### Banco (`/bank`)
- Cuentas **personales y compartidas** (con miembros), depósitos, retiros y transferencias.
- **Intereses** periódicos con tasas separadas para cuentas personales y compartidas, tope de saldo con interés y origen del dinero configurable (`bank.interest.source`).

### Bolsa de valores (`/stocks`)
- Las empresas cotizan con su ticker. Compra y venta con **órdenes** (de mercado y límite) y libro de órdenes.
- **IPO**: una empresa vende acciones nuevas a un precio fijado hasta agotarlas.
- El precio se mueve con la **oferta y la demanda** (desequilibrio del libro) con límites de variación por ciclo.
- **Portafolio** con coste medio, **índice** ponderado por capitalización, volumen e **historial** de precios con gráfico en el menú.
- **Dividendos** repartidos a los accionistas.

### Empresas (`/company`)
- **Empresas predeterminadas** (definidas en `config.yml`) que funcionan solas: producen, venden y pagan dividendos automáticos.
- **Empresas de jugadores**: `/company create <nombre> <ticker>`.
- Jerarquía **CEO / directivos / empleados**, con permisos finos por miembro (contratar, despedir, salarios, productos, finanzas, dividendos, contabilidad, ventas).
- **Salarios y nómina** automática periódica.
- **Productos y servicios** que los clientes compran; producción automática con tope.
- **Contabilidad**: libro de ingresos y gastos e informes.
- **Acciones y dividendos**: la empresa puede cotizar y repartir beneficios; impuesto de sociedades configurable.

### Tesorería e impuestos (`/treasury`)
- La tesorería acumula comisiones e impuestos de todos los módulos.
- **Presupuestos**, **subsidios** (pagos periódicos), **recompensas** por acciones (entrar, matar mobs, romper bloques) con enfriamiento.
- **Proyectos con votación**: los jugadores proponen y votan; si se aprueban y hay fondos, se financian.
- Impuesto al patrimonio periódico y auditoría de movimientos.

### Analítica (`/ecostats`)
- Masa monetaria, circulación, **inflación** y velocidad del dinero, con instantáneas periódicas.
- **Rankings** (saldo, patrimonio, comerciantes) y estadísticas.
- **Alertas** configurables (transferencias grandes, concentración de riqueza, crecimiento de la masa monetaria, tesorería baja). Los administradores con `economycore.alerts` las reciben y pueden marcarlas como atendidas.

---

## Comandos

| Comando | Alias | Descripción |
|---|---|---|
| `/eco` | economy, ecocore | Centro administrativo. Subcomandos: `give`, `take`, `set`, `reload`, `backup`, `open` |
| `/balance [jugador]` | bal, money | Ver saldos |
| `/pay <jugador> <cantidad> [moneda]` | | Pagar a otro jugador |
| `/baltop [moneda]` | balancetop | Ranking de riqueza |
| `/shop` | | Tienda del servidor |
| `/sellall` | | Vender todo lo vendible |
| `/markets [id]` | market, mercados | Lista de mercados |
| `/ah [sell <precio> [horas] [bin\|auction]]` | auction, auctionhouse | Casa de subastas |
| `/bazaar` | bz | Bazaar |
| `/trader open <id> [jugador]` | | Abrir un comerciante (para NPCs) |
| `/trade <jugador\|accept\|deny\|history>` | | Intercambio entre jugadores |
| `/pshop` | playershop, tiendas | Tiendas de jugadores |
| `/bank` | | Banco |
| `/stocks` | bolsa, stock | Bolsa de valores |
| `/company [create <nombre> <ticker>]` | empresa, empresas | Empresas |
| `/treasury` | tesoreria | Tesorería, impuestos y proyectos |
| `/ecostats` | analytics | Analítica |
| `/mailbox` | buzon | Reclamar objetos pendientes |

---

## Permisos

**Jugadores**

| Permiso | Para qué |
|---|---|
| `economycore.use` | Usar las funciones de jugador |
| `economycore.pay` | `/pay` |
| `economycore.trade` | Intercambios |
| `economycore.ah.sell` | Vender en la casa de subastas |
| `economycore.company.create` | Crear empresas |
| `economycore.pshop.create` | Crear tiendas de jugador |
| `economycore.bank.create` | Crear cuentas de banco |
| `economycore.ah.limit.N` | Límite de publicaciones en subastas (N = número) |
| `economycore.pshop.limit.N` | Límite de tiendas de jugador (N = número) |
| `economycore.market.*` | Acceso a todos los mercados, ignorando sus permisos |
| `economycore.tax.exempt` | Exento de impuestos |

**Administración**

| Permiso | Para qué |
|---|---|
| `economycore.admin` | Todo (incluye los de abajo) |
| `economycore.alerts` | Recibir alertas económicas |
| `economycore.shop.admin` | Administrar la tienda |
| `economycore.market.admin` | Administrar mercados |
| `economycore.ah.admin` | Administrar la casa de subastas |
| `economycore.bazaar.admin` | Administrar el bazaar |
| `economycore.trader.admin` | Administrar comerciantes |
| `economycore.bank.admin` | Administrar el banco |
| `economycore.stock.admin` | Administrar la bolsa |
| `economycore.company.admin` | Administrar empresas |
| `economycore.treasury.admin` | Administrar la tesorería |
| `economycore.analytics.admin` | Ver y gestionar analítica |

Cada mercado puede además exigir un permiso propio, definido al crearlo.

---

## Configuración

Todo está comentado en `config.yml`. Secciones principales:

- `modules`: activa o desactiva cada módulo (`economy`, `shop`, `markets`, `auction`, `bazaar`, `traders`, `trade`, `playershops`, `bank`, `stocks`, `company`, `treasury`, `analytics`).
- `database.backup`: backups automáticos (`enabled`, `interval-hours`, `keep`, `on-shutdown`).
- `economy`: moneda por defecto, lista de monedas iniciales, transferencia mínima y comisión.
- `auction-house`, `bazaar`, `trade`, `playershops`, `bank`, `stocks`: límites, comisiones, tiempos e intereses.
- `companies`: coste de creación, acciones iniciales, nómina, dividendos, impuesto de sociedades y la lista `defaults` de **empresas predeterminadas** con sus productos.
- `treasury` y `analytics`: propuestas, votos mínimos, impuesto al patrimonio y umbrales de alertas.
- `vault`: integración con Vault.

Los textos están en `messages.yml` (español) y se pueden editar. `/eco reload` recarga configuración y mensajes. Los cambios de módulos activos requieren reiniciar.

---

## Integración con Vault y API

**Vault (opcional).** Si Vault está instalado, EconomyCore se registra como proveedor de economía. Otros plugins (tiendas, rangos, `/sell`…) leerán y cobrarán de EconomyCore.

- `vault.enabled`: activa o desactiva el puente.
- `vault.currency`: moneda expuesta a Vault (vacío = la moneda por defecto).
- `vault.force-priority` (por defecto `true`): desregistra a cualquier otro proveedor de economía en Vault, como Essentials, para que EconomyCore sea siempre el activo.
- Los **bancos de Vault** están soportados: `createBank`, `deleteBank`, `bankBalance`, `bankHas`, `bankDeposit`, `bankWithdraw`, `isBankOwner` e `isBankMember` trabajan sobre las cuentas compartidas de `/bank`, así que plugins de clanes o ciudades usan tu banco.

Esto solo afecta a lo que pasa por Vault. Si otro plugin trae comandos con el mismo nombre (`/pay`, `/balance`), desactívalos en ese plugin.

**API de Bukkit.** `EconomyAPI` se registra en el `ServicesManager` para que otros plugins consulten y muevan dinero sin pasar por Vault.

---

## PlaceholderAPI
Si PlaceholderAPI está instalado se registra la expansión `economycore` (se refresca en segundo plano cada `placeholders.refresh-seconds`):

| Placeholder | Resultado |
|---|---|
| `%economycore_balance%`, `_balance_formatted`, `_balance_compact` | Saldo en la moneda por defecto |
| `%economycore_balance_<moneda>%` (+ `_formatted` / `_compact`) | Saldo en otra moneda |
| `%economycore_rank%`, `%economycore_rank_<moneda>%` | Puesto en el ranking de riqueza |
| `%economycore_top_<n>_name%`, `_top_<n>_balance%` (+ `_formatted`) | Top de jugadores (también `top_<moneda>_<n>_...`) |
| `%economycore_stock_<TICKER>_price%`, `_prev`, `_change`, `_volume` | Datos de una acción |
| `%economycore_index%` | Índice de la bolsa |
| `%economycore_treasury%`, `%economycore_treasury_<moneda>%` | Saldo de la tesorería |
| `%economycore_symbol%` | Símbolo de la moneda por defecto |

## Sumideros de dinero
Módulo **Sumideros** (`/eco` → Sumideros, valores editables en el juego y por defecto en `config.yml` → `sinks`): tarifa de yunque por nivel, mantenimiento diario de tiendas de jugador y de empresas de jugador, y decaimiento por inactividad sobre saldos grandes. El dinero se destruye (`BURN`) o va a la tesorería (`TREASURY`). Permiso de exención del yunque: `economycore.sinks.bypass`.

## Notificaciones
Los jugadores reciben aviso (mensaje y sonido) cuando se llena una orden del bazaar o de la bolsa, los superan en una subasta, venden o ganan una subasta, cobran salario o dividendos. Si están desconectados, el aviso queda guardado y se entrega al entrar.

## Detección de manipulación
La analítica vigila la bolsa y lanza alertas (`economycore.alerts`) por *wash trading* entre dos cuentas y por precios movidos por una sola cuenta. Se ajusta en `analytics.manipulation`.

## Interfaz
Banco, Casa de subastas y Bazaar siguen un diseño tipo Hypixel: menú principal con opciones claras, explorador con categorías arriba y rejilla central, orden y filtros con lista de opciones, depósito/retiro/compra con cantidades rápidas (todo, mitad, stack, personalizada) y confirmaciones. Los avisos importantes traen botones clicables (aceptar o rechazar un intercambio, reclamar, abrir la casa de subastas); se pueden desactivar con `gui.clickable-messages`. En `messages.yml` puedes crear los tuyos con `[[texto|/comando|ayuda al pasar el ratón]]` (solo comandos de EconomyCore).

Paleta moderna (colores hex) en todos los menús y mensajes, marcos, botones de navegación con estado, sonidos y tamaño de página configurable en `gui` (`modern-colors`, `sounds`, `page-size`).

## Integración con NPCs

EconomyCore no crea NPCs. Se enlaza con los de otros plugins de dos formas:

1. **Por comando.** En el NPC configura la acción de comando `trader open <id> %player%`.
2. **Por identificador.** En `/eco` → Comerciantes, vincula un NPC a un comerciante. El clic derecho sobre el NPC abre la tienda. Hay un **modo captura** que lee el identificador del NPC al que haces clic. Se admiten claves `uuid:`, `name:`, `tag:` y `citizens:` (leyendo el metadato de Citizens si existe, sin depender de él).

---

## Datos, backups y seguridad

- **Base de datos:** SQLite en `plugins/EconomyCore/data/economy.db`, con WAL, índices y migraciones versionadas.
- **Backups:** se hacen automáticamente al arrancar, cada `interval-hours`, y antes de cada migración. Se guardan en `data/backups` y se rotan según `keep`. También con `/eco backup`.
- **Asíncrono:** todas las operaciones de base de datos corren fuera del hilo principal, con un único escritor que serializa las transacciones; el servidor no se bloquea.
- **Protección contra duplicaciones y concurrencia:** transacciones atómicas, reservas de dinero en escrow, reverificación al ejecutar intercambios y entrega de objetos a través del buzón.
- **Sin NMS:** solo API pública de Bukkit/Paper, con una pequeña capa de compatibilidad entre versiones.

---

## Compilar y publicar

```bash
mvn -B package
```

El JAR queda en `target/EconomyCore-<versión>.jar`.

El workflow de GitHub Actions (`.github/workflows/build.yml`) compila en cada push a `main`/`master`, sube el JAR como artefacto y publica un Release (`build-N`, o la etiqueta si subes un tag `v*`).
