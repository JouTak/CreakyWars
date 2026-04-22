# CreakyWars — криповый аналог BedWars

Мини-игра для сервера ITMOcraft miniGAMES: игроки делятся на команды, защищают своё **ядро** (блок `CREAKING_HEART`) и пытаются уничтожить ядра соперников. Пока ядро цело — команда может возрождаться; после разрушения ядра — следующая смерть становится финальной. Режим интегрирован с **MiniGames API** (очередь/выбор команд/результаты).

---

## Возможности

- Автозапуск матчей через MiniGames API
- Клонирование арен под каждый матч через Multiverse-Core (`cw_game_<map>_<id>`)
- Командные ядра (Creaking Heart): уничтожение ядра отключает возрождение для команды
- Генераторы ресурсов с голограммами, множители фаз/улучшений
- Торговец (Illusioner) с GUI-магазином (настройка в `game-config.yml -> trades`)
- Улучшения команды: `forge`, `protection`, `sharpness`, `efficiency`, `fast-respawn`, `trap`
- Сценарий фаз (`scenario-config.yml`): множители ресурсов, отключение респавна, сужение границы, glow и т.д.
- День/ночь (`game-config.yml -> day-night-cycle`): ночью открываются **глазалии** (Eyeblossom) и спавнятся **Скрипуны** (Creaking)
- Ограничения строительства: разрешённые блоки + защита зон возле спавнов/генераторов/торговцев
- Админ-команда `/creakywars` (наблюдение, управление матчами/фазами, reload)
- Автоочистка клон-миров при запуске/выключении плагина (`cw_game_*`, `cw_ceremony_*`)

---

## Требования

- Paper / Purpur **1.21.x**
- **Multiverse-Core**
- **MiniGames API** (внутренняя библиотека)

---

## Установка

1. Положите `CreakyWars.jar` в `plugins/`
2. Установите `Multiverse-Core`
3. Подготовьте шаблонные миры:
   - `game` — шаблон арены (имя берётся из `admin-config.yml -> world.template-name`)
   - `cw_ceremony` — шаблон церемонии (опционально, если включена `ceremony.enabled`)
4. Запустите сервер — создадутся конфиги:
   - `plugins/CreakyWars/admin-config.yml`
   - `plugins/CreakyWars/game-config.yml`
   - `plugins/CreakyWars/maps-config.yml`
   - `plugins/CreakyWars/scenario-config.yml`

---

## Конфигурация

### `admin-config.yml` (параметры матчей)

Ключевые поля:

- `teams.count` — количество команд
- `players.max` — максимум игроков в матче
- `world.template-name` — имя шаблонного мира арены
- `world.border-size` — стартовый размер границы мира
- `ceremony.*` — финальная церемония (мир-шаблон + длительность + подиумы)

Пример:

```yaml
teams:
  count: 4

players:
  max: 16

world:
  template-name: game
  border-size: 261.0

ceremony:
  enabled: true
  template-name: cw_ceremony
  duration-seconds: 10
```

> Очередь и команды (например `/ready`, `/unready`, `/teamselect`) предоставляются MiniGames API.

⚠️ В текущей реализации часть полей в `admin-config.yml` (например `maps.available`, `games.max-parallel`, `teams.max-players-per-team`, `players.min-percent-to-start`) **не влияет** на логику матчей и оставлена как резерв/для будущих правок.

---

### `maps-config.yml` (карты и точки)

Каждая верхнеуровневая секция — **id карты** (именно этот id регистрируется в MiniGames API).

Формат координат: `"x, y, z, yaw, pitch"` (world не указывается — используется клон арены).

Минимально нужны:

- `team-spawns` (>= `teams.count`, порядок совпадает с индексами команд)
- `core-locations` (>= `teams.count`, порядок совпадает)
- `resource-spawners` (под ваши `resources.*` из `game-config.yml`)

Рекомендуется также настроить:

- `trader-locations` (точки торговцев)
- `upgrade-locations` (точки апгрейд-станций)
- `team-chest-locations`, `ender-chest-locations`
- `eyeblossom-locations`, `creaking-spawns` (ночные события)

Пример:

```yaml
game:
  display-name: "§eMap 1"

  team-spawns:
    - "8, 72, 97, 180, 0"
    - "-97, 72, 8, -90, 0"
    - "-8, 72, -97, 0, 0"
    - "97, 72, -8, 90, 0"

  core-locations:
    - "0, 71, 103, 0, 0"
    - "-103, 71, 0, 0, 0"
    - "0, 71, -103, 0, 0"
    - "103, 71, 0, 0, 0"

  resource-spawners:
    rubber_low:
      - "6, 72, 109, 0, 0"   # team 0
      - "-109, 72, 6, 0, 0"  # team 1
      - "-6, 72, -109, 0, 0" # team 2
      - "109, 72, -6, 0, 0"  # team 3
```

⚠️ В текущей реализации поля `display-name`, `description`, `time-of-day`, `weather` читаются из файла, но **не используются** в логике матча (резерв).

---

### `game-config.yml` (правила, ресурсы, магазин, улучшения)

- `settings.protection-radius` — радиус защиты вокруг спавнов/генераторов/торговцев (нельзя строить)
- `settings.void-kill-height` — смерть, если игрок ниже этого Y
- `allowed-blocks` — единственные блоки, которые игроки могут ставить/ломать
- `resources.*` — типы ресурсов + период спавна (в тиках; `-1` = не спавнить автоматически)
- `trades.*` — товары магазина (`cost`, `result`, `category`, `enchantments`)
- `upgrades.*` — апгрейды команды/базы

Мини-пример ресурса и товара:

```yaml
resources:
  rubber_low:
    material: RESIN_CLUMP
    display-name: "§7Резина"
    spawn-period: 60

trades:
  wool:
    cost: "rubber_low:4"
    result: "WHITE_WOOL:16"
    display-name: "Шерсть"
    category: "blocks"
```

---

### `scenario-config.yml` (фазы)

Фаза может быть задана:

- `end-at-tick` — абсолютный тик от старта матча
- и/или `duration` — длительность (в секундах)

Ключевые поля фаз:

- `resource-multiplier`, `respawn-enabled`
- `border-shrink`, `border-shrink-speed`, `border-final-size`
- `creaking-speed-amplifier`, `glow-players`
- `bad-weather-enabled`, `bad-weather-kill-height`
- `start-message`, `end-message`

---

## Использование

### Очередь и запуск матча

Очередь и команды — из MiniGames API (`/ready`, `/unready`, `/teamselect`, ...).

Когда MiniGames API формирует готовый матч, CreakyWars:

1. Клонирует `world.template-name` в `cw_game_<map>_<id>`
2. Телепортирует игроков на арену, запускает отсчёт и матч
3. По завершению — телепортирует на спавн первого загруженного мира (обычно лобби) и удаляет клон-арену

---

### Админ-команда `/creakywars` (`/cw`)

- `/cw info` — версия/кол-во игр
- `/cw reload` — перезагрузка конфигов
- `/cw games` — список активных матчей
- `/cw spectate [id|here]` — наблюдать
- `/cw unspectate` — выйти из наблюдения
- `/cw end [id|here] [reason...]` — завершить матч (нужно `creakywars.admin.danger`)
- `/cw startnow [id|here]` — пропустить отсчёт
- `/cw phase <info|skip|set> ...` — управление фазами

Права:

- `creakywars.admin` (по умолчанию op)
- `creakywars.admin.danger` (по умолчанию op)

---

## Геймплей (кратко)

- Команды появляются на своих базах, получают стартовую броню и меч.
- Собирайте ресурсы с генераторов и покупайте предметы у торговца.
- Пока ваше ядро цело — вы возрождаетесь (с задержкой из `game-config.yml`).
- После разрушения ядра — следующая смерть выбивает игрока из игры.
- Побеждает последняя команда, у которой остались живые игроки.

---

## Миры и очистка (Multiverse-Core)

- Матчи используют клоны: `cw_game_*`
- Финальная церемония (если включена) использует клон: `cw_ceremony_*`
- При старте/выключении плагина старые клоны удаляются (включая хвосты после крашей/рестартов)

---

## Разработка и сборка

```bash
./gradlew clean build
```

Готовый jar: `build/libs/*.jar`

---
