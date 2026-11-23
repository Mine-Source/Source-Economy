# EconomyCore

Plugin de economía avanzada para Paper/Spigot 1.21+ (Java 21). Todo se administra en el juego con `/eco`.

**Módulos:** economía multi-moneda, Shop/SellAll, mercados personalizados, Auction House, Bazaar, comerciantes (vinculados a NPCs de otros plugins por identificador), intercambio entre jugadores, tiendas de jugadores, banco, bolsa, empresas, impuestos y tesorería, analítica.

## Compilar
`mvn -B package` → `target/EconomyCore-<versión>.jar`. Requiere el driver `org.sqlite.JDBC` (incluido en Paper 1.21+).

## Release
Sube una etiqueta `vX.Y.Z` y el workflow `release.yml` publica el JAR.

## Datos
- Base de datos: `plugins/EconomyCore/data/economy.db` (SQLite, WAL, migraciones y backups en `data/backups`).
- `config.yml` y `messages.yml` se completan con claves nuevas al actualizar sin tocar tus valores.

## Comandos
`/eco`, `/balance`, `/pay`, `/baltop`, `/shop`, `/sellall`, `/markets`, `/ah`, `/bazaar`, `/trader`, `/trade`, `/pshop`, `/bank`, `/stocks`, `/company`, `/treasury`, `/ecostats`, `/mailbox`.

## NPCs
Abre un comerciante desde cualquier plugin de NPC con la acción de comando `trader open <id> %player%`, o vincula el NPC desde `/eco` → Comerciantes (modo captura).

## API
`EconomyAPI` se registra en el `ServicesManager` de Bukkit.
