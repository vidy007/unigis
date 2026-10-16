# UniGISMap — навигация по кампусу НГТУ (Android)

Приложение для навигации внутри корпусов университета и между ними. Работает полностью
офлайн: данные кампуса лежат в APK (`app/src/main/assets/campus.json`), вся логика выполняется
в нативном C++-ядре. Первый экран — карта кампуса в том виде, как на бумажной «Карте кампуса» НГТУ
(корпуса, общежития и объекты с цветными номерами, остановки, метро); корпус с поэтажными планами
(пока только корпус 4, этажи 1–4) открывается нажатием, остальные здания показаны для ориентира. Приложение собирается в двух вариантах с одной логикой, данными и версией:

| | `app/` | `lite/` |
|---|---|---|
| Интерфейс | Jetpack Compose, Material 3 | Android View + Canvas, оформление под Material 3 |
| Логика | C++-ядро + `shared/` | то же C++-ядро + тот же `shared/` |
| Сборка | Gradle + AGP (Google Maven), CMake | Gradle (только Kotlin с Maven Central), `clang++` из NDK, aapt/dx (или d8) |
| Пакет | `com.unigismap.indoormap` | `com.unigismap.indoormap.lite` (ставится рядом) |

## Архитектура
```
shared/src/main/kotlin/com/unigismap/indoormap/core/   — общий для app и lite слой без UI (Kotlin + Android framework)
├── NativeCore.kt                — external-функции JNI
├── CampusCore.kt                — загрузка campus.json, декодирование данных ядра, поиск, маршруты
└── CampusColors.kt              — цвета легенды бумажной карты кампуса (общие для app и lite)
app/src/main/
├── cpp/                         — C++20 ядро (NDK), без внешних зависимостей
│   ├── unigismap.hpp            — весь API ядра в одном заголовке, по разделам:
│   │                              геометрия (Vec2, Rect, Polygon, Frame), JSON, модель кампуса
│   │                              (Campus → Building → Floor → Room/Door/Connector), поиск, маршруты, камера
│   ├── json.cpp                 — минимальный JSON-парсер
│   ├── campus.cpp               — загрузка campus.json в модель, поиск помещения по точке
│   ├── search.cpp               — поиск помещений (кириллица, ё=е, ранжирование)
│   ├── router.cpp               — граф пешеходных путей + A*: по этажам, лестницам, лифтам и улице
│   ├── camera.cpp               — камера карты: масштаб/поворот/сдвиг, жесты, анимации
│   ├── jni_bridge.cpp           — JNI: данные передаются плоскими массивами
│   └── tests/core_test.cpp      — тесты ядра
├── java/com/unigismap/indoormap/
│   ├── map/FloorPlan.kt         — адаптер shared → типы Compose (Room, Door, FloorPlan)
│   ├── map/CampusPlan.kt        — то же для карты кампуса (здания, остановки, дорожки)
│   ├── map/MapState.kt          — состояние камеры и жесты для Compose (математика в C++)
│   ├── map/FloorMap.kt, CampusMap.kt — отрисовка (Compose Canvas)
│   └── ui/                      — экраны (CampusScreen → MapScreen), тема (Material 3)
└── assets/campus.json           — данные кампуса (генерируется, см. ниже)
lite/src/main/kotlin/com/unigismap/indoormap/lite/
├── FloorPlan.kt, CampusPlan.kt  — адаптеры shared → android.graphics (зеркала app/.../map/)
├── MapView.kt                   — камера и жесты (математика камеры в C++)
├── FloorMapView.kt, CampusMapView.kt — отрисовка этажа и кампуса
└── MainActivity.kt              — те же экраны, что app/.../ui/, на обычных View
```
Версия (`unigismap.versionName`/`unigismap.versionCode`) задаётся в `gradle.properties`, версия Kotlin — в
`gradle/libs.versions.toml`; оба варианта берут их оттуда. Новая функция добавляется в C++-ядро и
`shared/`, а в интерфейс — в обоих вариантах.

## Данные кампуса
- **Состав карты** — легенда бумажной «Карты кампуса» НГТУ, `tools/data/campus_map.json`: учебные
  корпуса 1–8 (включая 3а/3б и 7), общежития 1–8, объекты кампуса 1–7 (центр культуры, дворец
  спорта, бассейн, спортплощадки, библиотека, поликлиника, «Гараж»/«Фотон»), автобусные и трамвайные
  остановки, метро «Студенческая». Для каждого объекта указан номер на карте, тип (цвет) и как найти
  его в OSM: по `osm`-id или по тегам (адрес, название, POI внутри здания). Не найденные объекты
  скрипт перечисляет — им нужно вписать `osm`-id. Бумажная карта повёрнута относительно севера
  (сверху ул. Новогодняя), поэтому в данных есть `viewRotation`: карта кампуса в приложении
  открывается и «выравнивается» в том же виде.
- **Контуры зданий, этажность, входы, дорожки, остановки** взяты из OpenStreetMap
  (© участники OpenStreetMap, лицензия ODbL; указание авторства обязательно).
- **Поэтажные планы** есть только у корпуса 4 (этажи 1–4, `tools/data/k4_floors.json`): помещения,
  двери, лестницы у обоих торцов, лифт, вход с ул. Новогодней. Они оцифрованы по скриншотам
  картографического приложения и привязаны к контуру корпуса в OSM (183 × 16,6 м). Перед публикацией
  их стоит сверить с поэтажными планами эвакуации или согласовать с университетом: планы в 2ГИС
  защищены лицензией. Планы остальных корпусов можно отрисовать в SVG (см. ниже) и подключить
  так же, полем `floors` в легенде.

Пересборка `campus.json`:
```
curl -o nstu.osm "https://api.openstreetmap.org/api/0.6/map?bbox=82.8960,54.9810,82.9150,54.9910"
python tools/osm_to_campus.py nstu.osm
```
Формат: у корпуса `kind` (`academic`, `dorm`, `facility`) и `number` (номер на карте), у кампуса
`stops` (`bus`, `tram`, `metro`) и `viewRotation`. Этаж: помещения (`rect` или `polygon` в метрах),
двери (`a`, `b`, `exterior`), лестницы и лифты (`connectors` с общим `group` на разных этажах).
Подробности в `unigismap.hpp` (раздел «Campus model»).

### Этажи из SVG (`tools/svg_floor_to_json.py`)
Планы удобно обводить в Inkscape или Figma поверх плана эвакуации, а не писать координаты вручную.
Один SVG — один этаж. Начало координат SVG — начало координат корпуса, ось y вниз, поэтому все этажи
корпуса рисуйте на одном холсте (или ставьте `<circle id="origin">` в одну и ту же точку на каждом этаже).
Трансформации групп (`translate`, `scale`, `rotate`, `matrix`) учитываются. Пример: `tools/data/sample_floor.svg`.

| Что | Как нарисовать |
|---|---|
| Масштаб (обязательно) | `<line>` или `<rect>` с `id="scale"` и `data-meters="N"` — реальная длина в метрах (у rect — длинная сторона) |
| Помещение | `<rect>`, `<polygon>` или `<path>` только из прямых отрезков; `id` — номер (или `data-id`), `data-name` (или `inkscape:label`), `data-category`: `office`, `meeting`, `common`, `service`, `corridor` |
| Дверь | `<line>` (или `<path>` из двух точек) с `data-door` или `class="door"`; `data-exterior="true"` для выхода на улицу. Середина двери должна лежать на контурах обоих помещений (до 0,3 м) |
| Лестница / лифт | `<circle>` с `data-connector="stairs"` или `"elevator"` и `data-group="stairs-A"` (одинаковый на всех этажах) |
| «Вы здесь» | `<circle id="you-are-here">`, необязательно |
| Границы этажа | `<rect id="bounds">`, необязательно |

Фигура без `data-category`, `data-name` и метки — не помещение (стены, подложка игнорируются).
Атрибуты `data-*` наследуются от групп: `<g data-category="office">` размечает всё внутри. Figma не
сохраняет `data-*`, но имена слоёв становятся `id`, поэтому группа с именем `office`, `corridor`, `doors`,
`stairs` или `elevator` работает так же.

```
python tools/svg_floor_to_json.py floor.svg --building k1 --level 2 --name "Этаж 2" --campus app/src/main/assets/campus.json
python tools/svg_floor_to_json.py floor.svg --building k1 --level 2 --dry-run   # только показать JSON
python tools/svg_floor_to_json.py --check                                      # самопроверка на примере
```
Этаж добавляется в `floors` корпуса или заменяет этаж с тем же `level`; остальной файл не меняется.
Внимание: `osm_to_campus.py` пересоздаёт `campus.json`; этажи сохраняются только у корпусов, для которых
в легенде указан файл `floors` (как `k4_floors.json`), так что SVG держите в репозитории.

## Сборка
Android Studio 2026.2+, AGP 9.2, Gradle 9.8, Kotlin 2.4, NDK r29, CMake 3.31.
NDK и CMake Gradle скачивает сам при первой сборке.
```
./gradlew assembleDebug
```
Если Google Maven отвечает 404 (так бывает в некоторых сетях при доступе по IPv4), запускайте с
`JAVA_TOOL_OPTIONS=-Djava.net.preferIPv6Addresses=true` или добавьте `-Djava.net.preferIPv6Addresses=true`
в `org.gradle.jvmargs` в `~/.gradle/gradle.properties`.

## Вариант без зависимостей (`lite/`)
`lite/` — то же приложение с минимумом зависимостей: без AndroidX, Compose, AGP и Google Maven.
Отличается только интерфейс (View + Canvas вместо Compose); C++-ядро, слой `shared/`, данные и версия
общие. Ядро собирается одной командой `clang++` из NDK, без CMake; libc++ линкуется статически,
так что в APK одна `libunigismap.so` на ABI.

Нужны пакеты Debian/Ubuntu, Gradle (Kotlin с Maven Central), JDK 17+ и Android NDK:
```
sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign zip
ANDROID_NDK_HOME=/path/to/ndk lite/build-apk.sh   # → lite/build/apk/unigismap-lite-*.apk
```
На Windows то же самое делает `lite/build-apk.ps1` с инструментами Android SDK (aapt, d8, zipalign,
apksigner из build-tools) и NDK, установленными через Android Studio:
```
powershell -ExecutionPolicy Bypass -File lite\build-apk.ps1
```
`ABIS="arm64-v8a"` ограничивает набор архитектур. Pre-release-сборки делает workflow
`.github/workflows/lite-prerelease.yml` по тегу `lite-v*` (NDK на раннерах GitHub предустановлен).
