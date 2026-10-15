# UniGIS — прототип локальной карты помещения (Android)

Прототип Android-приложения для навигации внутри помещения. Внешние картографические
сервисы (OpenStreetMap и т.п.) не используются — план этажа задаётся векторно в коде
(в метрах) и рисуется на `Canvas` в Jetpack Compose.

## Что умеет
- План одноэтажного офиса 32 × 20 м: кабинеты, опенспейс, переговорная, холл, кухня, санузел, лестница, склад, коридор.
- Жесты: перемещение одним пальцем, масштаб и **вращение** двумя пальцами, двойной тап — приближение.
- Тап по помещению — выделение и карточка с типом и площадью.
- Поиск помещений (bottom sheet), плавный перелёт камеры к выбранному.
- Кнопки: компас (показывает поворот, по нажатию возвращает «север»), +/−, «весь этаж».
- Material Design 3: dynamic color (Android 12+), светлая/тёмная тема, edge-to-edge.
- Метка «Вы здесь» у входа.

## Структура
```
app/src/main/java/com/unigis/indoormap/
├── MainActivity.kt
├── map/
│   ├── FloorPlan.kt   — модель (Room, Door, FloorPlan) и демо-план этажа
│   ├── MapState.kt    — камера: масштаб/поворот/сдвиг, жесты, анимации
│   └── FloorMap.kt    — отрисовка плана и обработка жестов
└── ui/
    ├── MapScreen.kt   — экран M3: TopAppBar, FAB-ы, карточка, поиск
    └── theme/Theme.kt
```

Чтобы поменять помещение — отредактируйте `DemoFloorPlan` в `FloorPlan.kt`.

## Сборка
Требуется Android Studio (Ladybug+) / Android SDK 35, JDK 17+.
```
./gradlew assembleDebug
```
minSdk 26, targetSdk 35. AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01.

## Вариант без зависимостей (`lite/`)

Если Google Maven недоступен (AGP, AndroidX и Compose лежат только там), можно собрать
`lite/` — то же приложение на чистом Android framework (View + Canvas), оформленное
по Material 3 вручную. На Android 12+ берутся динамические цвета из системной палитры.

Нужны только пакеты Ubuntu/Debian, Gradle (для Kotlin с Maven Central) и JDK:
```
sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange apksigner zipalign zip
lite/build-apk.sh        # → lite/build/apk/unigis-lite.apk
```
APK подписан отладочным ключом `lite/debug.keystore` (пароль `android`), он лежит в
репозитории, чтобы обновления ставились поверх. Пакет `com.unigis.indoormap.lite`
устанавливается рядом с основной версией.
