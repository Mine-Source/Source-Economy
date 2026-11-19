# PROGRESS — EconomyCore

## Entorno de esta sesión
Sin red, sin Gradle/Maven, solo JRE 21 con `jdk.compiler`. Compilar y probar:
```
J="java -m jdk.compiler/com.sun.tools.javac.Main"
$J -d out/api -source 17 -target 17 $(find economycore-api -name '*.java')
$J -d out/core -cp out/api -source 17 -target 17 $(find economycore-core/src/main -name '*.java')
$J -d out/test -cp out/api:out/core -source 17 -target 17 $(find economycore-core/src/test -name '*.java')
java -cp out/api:out/core:out/test com.economycore.core.TestRunner
```
Con Gradle: `gradle check` (ejecuta `runTests`). Los archivos .kts NO se han ejecutado aún.

## HECHO y verificado (23/23 pruebas pasan, InMemoryStorage)
- `economycore-api` (sin dependencias): Currency, Money (long exacto), Result, LedgerEntry, EconomyService,
  Market, Product, PriceSpec, Rotation, RewardSpec, RewardHandler, DeliveryContext/Result, ItemProvider,
  ConditionProvider, MarketListener, PurchaseRequest/Record/State, MarketService.
- `economycore-core`: Storage SPI transaccional, InMemoryStorage, JdbcStorage (SQLite/MySQL), CurrencyRegistry,
  Accounts, EconomyEngine (idempotencia por opId, libro mayor), Pricing (fijo/aleatorio/stock/demanda/descuento,
  impuesto, rotación determinista), MarketRegistry (estado por mercado, caché por ventana, índice, paginación,
  recarga individual), MarketEngine (máquina de estados con escrow, CAS por transición, recuperación, reembolso,
  aislamiento de proveedores, condiciones que fallan cerradas, secretos, límites por jugador, ventanas de apertura).
- Pruebas: dinero exacto, idempotencia, concurrencia (transferencias, último artículo, mismo purchaseId),
  entrega fallida/recuperación/reembolso, caída a mitad, proveedores externos, recarga, monedas, acceso, rotación, precios.

## NO verificado
- `JdbcStorage` en ejecución: no hay driver JDBC offline. Solo el DDL de SQLite se validó con Python sqlite3.
  Pendiente: probarlo con sqlite-jdbc y MariaDB reales (misma batería de pruebas contra Storage).
- Scripts Gradle (.kts) sin ejecutar.

## Decisiones / límites conocidos
- Dinero: long en unidad mínima. Compra = stock reservado -> fondos a `escrow:<id>` -> entrega -> liberación a payee/`treasury`.
- Modos con compra: BUY_SHOP, BUY_SELL, OFFERS, BLACK_MARKET, CUSTOM. Los demás devuelven MODE_UNSUPPORTED.
- Si un producto desaparece del registro tras pagar, la compra queda en PENDING_RECOVERY (reembolsable con `refund`).
- Exclusión mutua de `advance` es por JVM; entre servidores sobre la misma BD protege el CAS + `staleMillis` en `recover`.
- Stock por mundo/servidor/grupo: no implementado (solo global y por jugador).

## PENDIENTE (orden)
1. Pruebas JdbcStorage con drivers reales (módulo `economycore-jdbc-tests` o tareas con sqlite-jdbc).
2. Módulo `economycore-plugin` (Paper/Spigot): bootstrap, ConnectionProvider (SQLite/Hikari), carga YAML de
   `markets/<id>/market.yml|categories|products` con recarga por mercado, comandos `/markets ...`, permisos
   `economy.market.<id>`, ConditionProviders (permission, world), RewardHandlers (item, command, permission, currency, exp),
   eventos Bukkit desde MarketListener, tarea periódica de `recover`.
3. GUI de mercados y panel administrativo (con previsualización).
4. Tiendas de venta/compra-venta (SELL_SHOP), subastas estilo Hypixel (AUCTION), mercado de órdenes, intercambio, mercado de jugadores.
5. Citizens (opcional), stock por mundo/servidor/grupo, estadísticas.
6. Documentación de integración (ejemplo pets_market) y Javadoc de la API.

## CI/Release (añadido)
- `.github/workflows/build.yml` (push/PR) y `release.yml` (tag `vX.Y.Z[-sufijo]`: job `build` con permisos de solo lectura -> job `publish` con `contents: write`, solo si build pasó).
- Módulo `economycore-plugin`: plugin.yml, config.yml, `EconomyCorePlugin` (monedas, SQLite/MySQL, registra EconomyService y MarketService, tarea `recover`, `/markets` lista). JAR único `EconomyCore-<versión>.jar` (plugin + api + core).
- SIN VERIFICAR (no hay Gradle ni API de Paper en esta sesión): scripts Gradle, compilación del módulo plugin, ejecución de los workflows. Primera ejecución real = primer push a GitHub; si falla, corregir según el log.
- Verificado: YAML de workflows/plugin/config parsean; regex de tag; lógica de verificación del JAR sobre un zip simulado.
- El plugin aún NO carga mercados desde YAML ni tiene GUI/comandos de administración: instalado solo expone los servicios a otros plugins.
