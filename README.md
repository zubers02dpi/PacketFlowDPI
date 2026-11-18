# PacketFlowDPI

**Свой VPN-клиент для Android TV и телефона: открытый каркас и закрытый движок
обхода DPI.**

## Что делает

Приложение поднимает собственный `VpnService`, читает пакеты из tun-устройства
и завершает TCP/UDP-соединения в userspace. Приложение на устройстве думает,
что говорит с сервером напрямую, а на самом деле трафик проходит через наш
релей. Это позволяет обрабатывать первый пакет каждой сессии (первую
TLS-запись) до того, как она уйдёт в сеть.

Интерфейс один и тот же на телевизоре и на телефоне: большая кнопка
включения, настройки с выбором цвета, журнал и шкала активности. На ТВ
управление пультом (D-pad), на телефоне — касанием; раскладка подстраивается
под экран.

## Что открыто, а что нет

| Часть | Статус |
|---|---|
| UI: кнопка включения, настройки, палитра, журнал, шкала | открыто, MIT |
| `VpnService`, tun-цикл, TCP-автомат, UDP-релей, пакеты и чексуммы | открыто, MIT |
| Разбор TLS `ClientHello` и поиск SNI | открыто, MIT |
| Движок десинка первого пакета (`Strategy.plan`, `Desync.writeFirst`) | **закрыто, не входит в репозиторий** |

В этой сборке `Strategy.plan()` возвращает payload одним куском, а
`Desync.writeFirst()` просто пишет его в сокет. То есть **открытая версия не
обходит DPI** — это честный каркас, на который можно поставить свой движок.
Параметры, порядок отправки и сама логика обхода в открытую версию не входят.

## Требования

- JDK 17;
- Android SDK: `build-tools` и любая платформа (`platforms/android-*`);
- Android 11+ (minSdk 30) — телевизор, приставка или телефон;
- для установки — `adb`.

## Сборка

### Windows

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_SDK_ROOT = "$env:LOCALAPPDATA\Android\Sdk"

.\build.ps1
```

### Linux / macOS

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk

bash build.sh
```

Оба скрипта собирают без Gradle: `aapt2 -> javac -> d8 -> zip -> zipalign ->
apksigner`. При первом запуске создаётся `debug.keystore` (он в `.gitignore`).
Результат — `PacketFlowDPI.apk`.

## Сборка на GitHub

В репозитории есть GitHub Actions (`.github/workflows/build.yml`). Он
собирается при каждом push в `main`/`master`, при pull request и вручную
(`Actions -> build -> Run workflow`). Готовый APK лежит в артефактах запуска:
`PacketFlowDPI-apk`.

## Установка

```bash
adb install -r PacketFlowDPI.apk
```

Или сразу собрать и установить:

```powershell
.\build.ps1 -Install
```

```bash
bash build.sh --install
```

## Как пользоваться

1. Запустите приложение.
2. Нажмите большую кнопку в центре — Android спросит разрешение на VPN.
3. «⚙ НАСТРОЙКИ» — цвет интерфейса, состояние движка и журнал.
4. Выключение — та же кнопка.

Одновременно может быть активен только один VPN: если включён другой клиент
(например, ByeByeDPI), сначала выключите его.

Для отладки можно запустить приложение сразу с включением VPN:

```bash
adb shell am start -n dev.packetflow.dpi/.MainActivity --ez start true
```

## Свой движок

Точки подключения находятся в двух классах:

- `Strategy.plan(byte[] d, int len)` — решает, на какие куски разбить первый
  payload и в каком порядке их отдать;
- `Desync.writeFirst(...)` — пишет эти куски в сокет.

`TcpFlow` уже буферизует первую TLS-запись целиком и передаёт её в
`Desync.writeFirst`, поэтому движку не нужно самому ждать окончания записи.
Замените тела этих методов своей реализацией — остальной каркас менять не
нужно.

## Структура

```
app/
  AndroidManifest.xml
  res/                     строки, тема, баннер
  java/dev/packetflow/dpi/
    MainActivity.java      UI для ТВ и телефона
    TunService.java        VpnService, tun-цикл, TCP/UDP-диспетчер
    TcpFlow.java           userspace TCP + буферизация первой записи
    Pk.java                IPv4/TCP/UDP пакеты и чексуммы
    Tls.java               минимальный разбор ClientHello (SNI)
    Strategy.java          ОТКРЫТАЯ ЗАГЛУШКА движка
    Desync.java            ОТКРЫТАЯ ЗАГЛУШКА движка
.github/workflows/build.yml  сборка APK на GitHub
build.ps1                  сборка без Gradle (Windows)
build.sh                   сборка без Gradle (Linux/macOS)
```

## Лицензия

Открытая часть распространяется по лицензии **MIT** — см. [LICENSE](LICENSE).
Закрытый движок обхода DPI не входит в репозиторий и лицензией MIT не
покрывается — см. [NOTICE](NOTICE).

## Важно

Проект предназначен для собственных сетей, тестирования и образовательных
целей. Используйте его только там, где это законно и не нарушает правила
вашего провайдера или сервиса.
