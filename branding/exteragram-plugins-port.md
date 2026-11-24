# Перенос движка плагинов exteraGram

exteraGram открыл исходники (github.com/exteraSquad/exteraGram, коммит b4d852f, «release exteraGram
13.2.2 source», 24 ноября 2026). Решение (24.11.2026): переносим их движок плагинов и Python SDK к
нам, Glassgram переходит на GPL v3 с их дополнительными условиями.

## Лицензия (сделано)

- `LICENSE` — GNU GPL v3; база Telegram — «GPL v2 or later».
- `ADDITIONAL_TERMS.md` — их условия для взятого у них кода (`com.exteragram.messenger.*`, SDK).
- Надпись «Движок плагинов основан на exteraGram · github.com/exteraSquad/exteraGram» в шапке
  настроек glassGram под версией (`GlassgramSettingsActivity`, `GlassgramBasedOnExteraGram`).
- В перенесённых файлах сохраняем их шапку с копирайтом; приложение не называем exteraGram.

## Что у них

- Kotlin, flavor `full`: `TMessagesProj/src/full/java/com/exteragram/messenger/plugins/`
  - ядро: `PluginsController.kt` (1482), `PythonPluginsEngine.kt` (2756), `Plugin.kt`,
    `PluginsConstants.kt`, `hooks/*` (PluginsHooks — интерфейс точек вызова из Telegram, HookFilter,
    Event/Xposed/Partial/MenuItem records), `xposed/*` (PyMethodHook, PyMethodReplacement),
    `utils/*` (ClassProxy, PyObjectUtils, MenuContextBuilder, PluginsWatchdog, SdkSignature,
    NativeCrashHandler), `models/*` (настройки), `pip/PipController`;
  - UI: `ui/*` (PluginsActivity, PluginSettingsActivity, компоненты) — на их
    BasePreferencesActivity / SettingsRegistry / UniversalFragment;
  - каталог `catalog/*` (Room + KSP), анализатор `analyzer/*` (закрытый нативный
    `libextera_analyzer.so` из их релизов), обновления SDK по воздуху `updates/*` (подписаны их ключом).
- Python SDK `plugins-pysdk/src` версии **1.4.6.5** (наш — упрощённый 1.4.5.2): base_plugin,
  hook_utils, client_utils, android_utils, file_utils, intents, log_utils, markdown_utils,
  plugin_settings, ui/*, extera_utils/* (+lifecycle), elyxcore (движок `.elyx`), dev_server.
- Хуки: свой `hook-core` (Xposed API + частичные хуки через генерацию dex) поверх LSPlant и
  shadowhook; нативная часть — статическая `libexterahook.a`, вшитая в их `libtmessages` через
  CMake/prefab (`Tools/maven`, `TMessagesProj/jni/CMakeLists.txt`).

## Решения

- Хуки остаются на нашем AliuHook (тот же Xposed API). Их `hook-core` не берём: это переделка
  нативной сборки. Частичные хуки (`HookHandle`, partial hooks SDK 1.4.6.x) не поддерживаются —
  понятная ошибка плагину.
- Не берём: закрытый нативный анализатор, обновления SDK из их канала (подписаны их ключом).
- Kotlin включён в TMessagesProj (`org.jetbrains.kotlin.android`, jvmTarget 1.8, core-ktx).

## Зависимости их ядра вне пакета плагинов (нужны переходники)

`com.exteragram.messenger.utils.AppUtils`, `preferences.utils.SettingsRegistry`,
`utils.text.LocaleUtils`, `utils.network.ExteraHttpClient`, `utils.chats.ChatUtils`, свойства
конфига `com.exteragram.messenger` (`preferences`, `editor`, `pinnedPlugins`,
`sdkUpdateScheduleTimestamp`), `com.exteragram.hook.HookHandle`; из их UI:
InstallPluginBottomSheet, PythonSdkUpdateAlert, SafeModeBottomSheet, PluginFileViewer,
PluginsActivity, PluginSettingsActivity; `updates.SignedArtifact/TelegramUpdateSource`,
`catalog.PluginRepositoryLinks`, `analyzer.AnalyzerUpdater`.

## Наш движок сейчас

Java: `org/telegram/messenger/plugins/*` (PluginsController, PluginsBridge, PluginXposedHook,
PluginActionGuard, GlassgramClassProxy, …), совместимость `com/exteragram/messenger/plugins/*`
(Java-заглушки их API), Python `TMessagesProj/src/main/python/_glassgram_*.py` + SDK-модули.
Вызовы из приложения: ChatActivity, LaunchActivity, ConnectionsManager, SendMessagesHelper,
MessagesController, ApplicationLoader; экраны GlassgramPlugins*Activity, GlassgramPluginInstaller,
GlassgramPluginCatalog.

## Этапы

0. Лицензия — сделано.
1. Kotlin в основном модуле — сделано (сборка #518).
2. Ядро их движка (Kotlin) и SDK 1.4.6.5 вместо нашего, с переходниками; AliuHook — сделано.
3. Точки вызова Telegram → их `PluginsHooks` — сделано через нашу прослойку.
4. Наши экраны на их контроллере — сделано.

## Как устроено теперь

- `TMessagesProj/src/main/java/com/exteragram/messenger/plugins/` — их ядро (PluginsController,
  PythonPluginsEngine, hooks, xposed, models, pip, utils). Изменённые нами файлы помечены
  «Modified for glassGram» в шапке: PythonPluginsEngine (без обновлений SDK по воздуху, один архив
  `sdk-universal.zip`), PluginsController (без анализатора; `hasEventHook`, `getPluginsHooking`),
  PyMethodHook (время хуков на главном потоке для записи рывков), PluginsWatchdog (`executingPlugin`).
- Их интерфейс `ui/*`: PluginSettingsActivity перенесён на `UniversalFragment` (без перевода
  настроек; на главной странице плагина — шапка, переключатель, ошибка, удаление, как было у нас);
  PluginsActivity / PluginsInfoActivity — это наши GlassgramPluginsActivity / GlassgramPluginEngineActivity;
  InstallPluginBottomSheet показывает наше окно установки (GlassgramPluginInstaller.showInstallSheet),
  с нашим анализом кода (`_glassgram_analysis`, `_glassgram_scan`) и проверкой по каталогу.
- Переходники к их приложению: `ExteraConfig.kt` (поля @JvmField, файл настроек `exteraconfig`; включён ли
  движок — наша настройка), `utils/AppUtils.java` (их), `ChatUtils.getPathToMessage`, `SettingsRegistry`,
  `LocaleUtils`, `ExteraHttpClient`, `IntentsController` (вызывается из LaunchActivity.handleIntent),
  `com.exteragram.hook.HookHandle` (частичных хуков нет), `BuildVars.BUILD_VERSION = 67600`,
  `Utilities.pluginsQueue`, `UItem.settingItem`, события NotificationCenter `plugin*`.
- `org.telegram.messenger.plugins.PluginsController` — прослойка для приложения: включение движка,
  список плагинов (PluginInfo), меню, хуки запросов/апдейтов/отправки, перенос плагинов старого
  движка (plugins_state.json → настройки их движка), выключение упавшего плагина (onCrash).
- `PluginActionGuard` (подтверждение опасных запросов плагинов) остался; кто из плагинов отправил
  запрос, ищет `_glassgram_guard.py`.
- SDK: `plugins-pysdk/` (их, 1.4.6.5); сборка — `TMessagesProj/gradle/glassgram-python-sdk.gradle`
  (Python 3.11). Наши изменения в SDK: ElyxCore не роняет SDK при сбое (`_sdk_version`), логи плагинов
  идут и в наш экран логов (`log_utils`).
- Dev-сервер SDK: `localabstract:exteragram_devserver`, как у exteraGram.
