# UniGIS — навигация по кампусу НГТУ (Android)

Приложение для навигации внутри корпусов университета и между ними. Работает полностью
офлайн: данные кампуса лежат в APK (`app/src/main/assets/campus.json`), вся логика выполняется
в нативном C++-ядре. Jetpack Compose отвечает только за интерфейс и отрисовку.

## Архитектура
```
app/src/main/
├── cpp/                         — C++20 ядро (NDK), без внешних зависимостей
│   ├── include/unigis/
│   │   ├── geometry.hpp         — Vec2, Rect, Polygon, Frame (системы координат)
│   │   ├── json.hpp             — минимальный JSON-парсер
│   │   ├── campus.hpp           — модель: кампус → корпуса → этажи → помещения/двери/лестницы
│   │   ├── camera.hpp           — камера карты: масштаб/поворот/сдвиг, жесты, анимации
│   │   ├── search.hpp           — поиск помещений (кириллица, ё=е, ранжирование)
│   │   └── router.hpp           — граф пешеходных путей + A*: по этажам, лестницам, лифтам и улице
│   ├── src/*.cpp
│   ├── jni/jni_bridge.cpp       — JNI: данные передаются плоскими массивами
│   └── tests/core_test.cpp      — тесты ядра
├── java/com/unigis/indoormap/
│   ├── core/NativeCore.kt       — external-функции
│   ├── core/Campus.kt           — декодирование нативных данных в UI-модели, маршруты
│   ├── map/FloorPlan.kt         — UI-модели (Room, Door, FloorPlan)
│   ├── map/MapState.kt          — состояние камеры для Compose (математика в C++)
│   ├── map/FloorMap.kt          — отрисовка (Compose Canvas)
│   └── ui/                      — экран, тема (Material 3)
└── assets/campus.json           — данные кампуса (генерируется, см. ниже)
```

## Данные кампуса
- **Контуры зданий, этажность, входы, дорожки** взяты из OpenStreetMap
  (© участники OpenStreetMap, лицензия ODbL; указание авторства обязательно). Берутся только
  учебные корпуса (пр. К. Маркса, 20, к1–к8); общежития и прочие здания исключены.
  Корпуса 7 в OSM с таким адресом нет.
- **Поэтажных планов НГТУ в открытом доступе нет**: на nstu.ru есть только схематичная
  карта кампуса (`/campus`), в OSM разметки помещений нет. Планы в 2ГИС защищены
  лицензией, копировать их нельзя. Пока в данных есть демо-корпус (`tools/data/demo_building.json`,
  `"placeholder": true`) с двумя этажами и лестницей. Настоящие планы нужно отрисовать
  самостоятельно, например по поэтажным планам эвакуации или с разрешения университета.

Пересборка `campus.json`:
```
curl -o nstu.osm "https://api.openstreetmap.org/api/0.6/map?bbox=82.8960,54.9810,82.9150,54.9910"
python tools/osm_to_campus.py nstu.osm
```
Формат этажа: помещения (`rect` или `polygon` в метрах), двери (`a`, `b`, `exterior`), лестницы и лифты
(`connectors` с общим `group` на разных этажах). Подробности в `campus.hpp`.

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
Внимание: `osm_to_campus.py` пересоздаёт `campus.json` с пустыми `floors` у корпусов из OSM, так что
после пересборки этажи нужно добавить снова (держите SVG в репозитории).

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
`lite/` — тот же интерфейс на чистом Android framework (View + Canvas), без AndroidX, Compose,
AGP и Google Maven. Логика (данные кампуса, поиск, камера, маршруты) берётся из того же C++-ядра
`app/src/main/cpp` через тот же JNI-мост (`NativeCore.kt` подключается из `app/`). Ядро собирается
одной командой `clang++` из NDK, без CMake; libc++ линкуется статически, так что в APK одна
`libunigis.so` на ABI.

Нужны пакеты Debian/Ubuntu, Gradle (Kotlin с Maven Central), JDK 17+ и Android NDK:
```
sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign zip
ANDROID_NDK_HOME=/path/to/ndk lite/build-apk.sh   # → lite/build/apk/unigis-lite-*.apk
```
`ABIS="arm64-v8a"` ограничивает набор архитектур. Pre-release-сборки делает workflow
`.github/workflows/lite-prerelease.yml` по тегу `lite-v*` (NDK на раннерах GitHub предустановлен).
