# Proton VPN-Next — форк со стабильным соединением и без утечек

Неофициальный Android-клиент Proton VPN на ядре AmneziaWG (sing-box / amnezia-box) с обфускацией трафика и обходом блокировок.

Это **независимый форк**. Он исправляет обрывы и «ложные подключения» оригинала и убирает все обращения приложения к инфраструктуре автора оригинального мода.

> [!WARNING]
> Проект **не связан** ни с Proton AG, ни с автором оригинального мода. Используйте на свой риск. Для поддержки сервиса рекомендуем платную подписку Proton VPN.

[English](#english)

---

## Зачем нужен этот форк

При разборе оригинального приложения нашлось три группы проблем:

1. **Ложный статус «Подключено».** Приложение показывало зелёный замок, хотя трафик через туннель не шёл.
2. **VPN не восстанавливался сам.** После короткого обрыва сети (смена вышки) или после того, как система выгрузила процесс, VPN отключался и не поднимался, пока не откроешь приложение.
3. **Данные уходили на серверы автора мода**, включая ваш реальный IP в обход VPN.

Все три исправлены. Изменения проверены на реальном телефоне, 192 юнит-теста проходят.

| | Оригинал | Этот форк |
|---|---|---|
| Статус «Подключено» | сразу после запуска движка, даже если сервер не отвечает | только после рукопожатия WireGuard и реального HTTPS-запроса через туннель |
| «Самый быстрый» сервер | часто выбирал выключенный сервер (нагрузка 0) | только работающие серверы; неотвечающий сервер или порт меняется автоматически |
| Обрыв сети, смена вышки | VPN останавливался | ждёт сеть и переподключается; сокет туннеля пересоздаётся сразу |
| Процесс VPN убит системой | вечное ложное «Подключено» | приложение замечает это и поднимает туннель заново |
| «Постоянный VPN» Android | сервис сразу останавливался сам | восстанавливает последний туннель |
| Реальный IP | уходил на серверы автора в обход VPN | запрашивается только у Proton |
| Проверка TLS | принималась любая цепочка, отвергнутая системой | как в системе, плюс опубликованные пины Proton |
| Прокси автора для API Proton | Netlify, Cloudflare, Deno, «событие» | удалены |
| Sentry, OTA-обновления | включены в стандартной сборке | выключены во всех сборках |
| AI-ассистент | отправлял список приложений внешним AI | удалён |
| Описания настроек | одна строка | у каждой настройки кнопка ⓘ с подробным объяснением |

Полный список изменений — в [FORK_CHANGES.md](FORK_CHANGES.md).

---

## Анализ безопасности оригинального приложения

Ниже то, что показывает исходный код оригинала на момент форка (коммит `e4b08fa`). Это описание того, что код **делал и позволял**, а не доказательство злого умысла.

### Что уходило на серверы автора

- **Ваш реальный IP.** Главный экран при каждом запуске и после каждого отключения спрашивал «какой у меня IP» у развёртываний автора (Cloudflare Workers, Deno, Vercel, адрес из удалённого «event»-конфига). Сокет при этом был привязан к мобильной сети или Wi-Fi, то есть **в обход VPN**. Через несколько секунд после подключения тот же запрос шёл уже через туннель. Автор получал пару «реальный IP + IP выхода VPN» и мог связать их. Так было во всех сборках, включая privacy, и отключить это было нельзя.
- **Вход в аккаунт — если был включён «Обход блокировки API» со стратегией Netlify, Cloudflare, Deno или «событие».** При включении обхода по умолчанию выбиралась Netlify. Все запросы к API Proton шли через прокси автора, и TLS расшифровывался на их стороне. Через них проходили:
  - логин и данные входа по SRP. Сам пароль по SRP не передаётся, но перехваченного обмена достаточно, чтобы **перебирать пароль офлайн**. Сложный пароль это не пробьёт, слабый — может;
  - коды двухфакторной аутентификации;
  - **токены сессии (access и refresh)** — полноценный вход в аккаунт в пределах прав сессии. Refresh-токен продлевает сессию, пока её не отзовут;
  - хэш `ANDROID_ID` устройства.

  Опубликованная в репозитории конфигурация Netlify (`netlify.toml`) — простая пересылка на `vpn-api.proton.me` без логирования. Но что было развёрнуто на самом деле, проверить нельзя: прокси Cloudflare и Deno — это исполняемый код, а адрес для стратегии «событие» приложение получало удалённо и могло менять без обновления. Технически перехват был возможен в любой момент; доказательств, что он происходил, в коде нет.
- **Sentry** (стандартная сборка): модель устройства, версия Android, язык, ID установки, ошибки с шагами подключения и ID серверов. Отчёты о сбоях были включены по умолчанию, хотя политика конфиденциальности оригинала называла их добровольными.
- **Анти-тампер** сообщал в Sentry о наличии определённых приложений (инструментов модификации), подписи APK и содержимом `/proc/self/maps`.
- **OTA-обновления** (стандартная сборка): проверка при каждом запуске (IP и модель телефона в User-Agent). APK скачивался по ссылке из ответа сервера **без проверки хэша**. Обновление могло содержать любой код.
- **Удалённый «event»-конфиг**: ежедневная загрузка с шести зеркал автора (IP и User-Agent), даже если обход API выключен.

### Уязвимость, не связанная с автором

`MirrorTrustManager` принимал **любую** цепочку сертификатов, которую отвергла система, а пинами были закреплены лишь некоторые хосты. Перехватить такие соединения мог кто угодно на пути: публичный Wi-Fi, провайдер, корпоративный прокси. В форке исправлено.

### Чего в коде не было

- **Использования телефона как прокси или узла сети.** Входящих подключений извне нет: локальный прокси ByeDPI слушает только `127.0.0.1`, конфигурация движка собирается на устройстве, внешнего API управления нет. Нативная библиотека делает HTTP-запросы только к `vpn-api.proton.me`.
- **Доступа к содержимому VPN-трафика.** Он зашифрован до серверов Proton, приватный ключ WireGuard не покидает телефон.

Оговорка: канал OTA позволял автору в любой момент выпустить версию с другим поведением.

### Что было возможно при перехвате токенов

С токеном сессии можно действовать в аккаунте Proton от вашего имени в пределах прав сессии. Например, регистрировать собственные ключи WireGuard и пользоваться вашей подпиской VPN с чужих устройств (в пределах лимита устройств тарифа). Это возможность, а не установленный факт. Проверить можно только по списку сессий в вашем аккаунте.

### Если вы пользовались оригиналом

1. **Отзовите сессии:** [account.proton.me](https://account.proton.me) → Настройки → Безопасность → «Сессии». Завершите все незнакомые и лишние. Особенно это важно, если был включён обход API через Netlify, Cloudflare, Deno или «событие».
2. **Смените пароль Proton**, если он простой или используется где-то ещё, и **включите 2FA**.
3. **Удалите оригинальное приложение.** Иначе через него может прийти OTA-обновление, и оно продолжит отправлять ваш IP при запуске.

---

## Установка

Готовый APK лежит на странице [Releases](https://github.com/drxekus/ProtonVPN-Next/releases), рядом указан его SHA-256. Проверить хэш: `sha256sum ProtonVPN-Next-fork-*.apk` (Linux/macOS) или `certutil -hashfile <файл> SHA256` (Windows).

- Релиз подписан ключом этого форка. Пакет — `ru.protonmod.next.privacy`, как у privacy-сборки оригинала. Если она установлена, её нужно сначала удалить (Android не обновит приложение с чужой подписью). Это и к лучшему, см. «Если вы пользовались оригиналом».
- Обычная сборка оригинала (`ru.protonmod.next`) с релизом форка не конфликтует, но её тоже стоит удалить.
- Только arm64 (64-битные телефоны), Android 10+.
- **Если собираете сами, рекомендуемый вариант — `stablePrivacy`** (пакет `ru.protonmod.next.privacy`).
- В этом форке у **обоих** вариантов (`standard` и `privacy`) выключены Sentry и OTA. Они отличаются только ID пакета.

### Сборка

Требуется: JDK 21, Android SDK (`platforms;android-37.1`, `build-tools;37.0.0`, `ndk;29.0.14206865`, `cmake;3.22.1`), Go 1.25.5, git, python3, bash. Удобнее всего собирать на Linux; на Windows работает через Git Bash.

```bash
# SHA-256 вашего ключа подписи (без него приложение покажет предупреждение о неофициальной сборке)
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256

./gradlew :app:assembleStablePrivacyDebug -PEXPECTED_SIGNATURE="AA:BB:…"
```

При первой сборке Gradle сам соберёт ядро amnezia-box (`scripts/build-awgbox-lib.sh`) вместе с патчами форка из `scripts/patches/`. Подробности — в [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md).

### Настройки телефона для стабильной работы в фоне

Прошивки с агрессивным энергосбережением (Realme/ColorOS, Xiaomi, Huawei и др.) убивают фоновые процессы. Для этого приложения:

1. **Автозапуск:** разрешить.
2. **Батарея:** без ограничений / разрешить фоновую активность.
3. **Настройки → VPN → шестерёнка у приложения:** включить **«Постоянный VPN»** и **«Блокировать соединения без VPN»**. Тогда при любом обрыве трафик других приложений не утечёт мимо VPN, а приложение поднимет туннель само.
4. На Realme/ColorOS можно закрепить приложение в «Недавних» (замок на карточке), чтобы смахивание не убивало VPN.

### Раздельное туннелирование вместе с «Блокировать соединения без VPN»

Когда включена блокировка, Android сам отрезает любой трафик мимо VPN. Поэтому исключённые приложения, IP-адреса и домены остаются без интернета. Приложение VPN это обойти не может: так устроен Android.

Для **приложений** в Android есть системный список исключений блокировки. В обычных настройках его нет, но его можно задать через Shizuku или ADB. Приложения из этого списка ходят в интернет напрямую, а остальные по-прежнему защищены блокировкой. Для IP-адресов и доменов такого списка нет.

**Через Shizuku (проще):** если установлен и запущен [Shizuku](https://shizuku.rikka.app/), откройте «Раздельное туннелирование» в режиме исключения, нажмите «Разрешить в Shizuku», затем «Записать список» и перезагрузите телефон. Приложение записывает туда ровно список исключённых приложений и использует права Shizuku только для чтения и записи этой одной настройки, только по нажатию кнопки.

**Через ADB:**

1. Узнайте имена пакетов: `adb shell pm list packages | grep -i bank`.
2. Задайте список через запятую, без пробелов:
   ```bash
   adb shell settings put secure always_on_vpn_lockdown_whitelist com.example.bank,com.example.maps
   ```
3. Перезагрузите телефон: Android читает список при запуске.
4. В приложении добавьте эти же приложения в исключения раздельного туннелирования.

Проверка: `adb shell settings get secure always_on_vpn_lockdown_whitelist`. Если в настройках Android выключить и снова включить «Постоянный VPN» или блокировку, система сотрёт список, и команду придётся повторить. Убрать список: `adb shell settings delete secure always_on_vpn_lockdown_whitelist`, затем перезагрузка. Работу на конкретной прошивке нужно проверить: производитель мог изменить это поведение.

### Автоподбор обфускации

Если сеть молча отбрасывает рукопожатия WireGuard, приложение при следующей попытке меняет не только сервер, но и обфускацию: стандартный профиль → больше и крупнее мусорных пакетов → первый пакет в виде начала QUIC-соединения, новый для каждого рукопожатия. Что сработало, запоминается отдельно для каждой сети (код оператора или хэш Wi-Fi, только на телефоне) и в следующий раз пробуется первым. Выбор — сэмплирование Томпсона по успехам и неудачам, которые со временем забываются; раз в неделю приложение снова пробует вариант полегче. Меняются только пакеты перед рукопожатием (серверы Proton — обычный WireGuard), поэтому скорость туннеля не страдает. Настройки → Протокол → шестерёнка → «Автоподбор обфускации»; свой профиль AWG и цепочку прокси он не трогает.

Для разработчиков: скилл Claude Code [`.claude/skills/tune-obfuscation`](.claude/skills/tune-obfuscation/SKILL.md) снимает с телефона журнал и статистику лестницы через adb и предлагает новые варианты.

### Диагностика

Отладочная сборка ведёт журнал событий VPN: смены сети, рукопожатия, восстановления, без IP-адресов.

```bash
adb shell run-as ru.protonmod.next.privacy cat files/vpn-events.log
```

---

## Лицензия и происхождение

GPL-3.0, см. [LICENSE](LICENSE). Форк основан на проекте ProtonVPN-Next (автор SMH01). Уведомления об авторских правах в исходных файлах сохранены, как требует лицензия. Ссылку «forked from» вверху страницы GitHub добавляет автоматически.

Сообщить о проблеме или уязвимости: [Issues](https://github.com/drxekus/ProtonVPN-Next/issues), см. [SECURITY.md](SECURITY.md).

---

<a name="english"></a>
# Proton VPN-Next — fork with a stable connection and no data leaks

An unofficial Android client for Proton VPN built on the AmneziaWG core (sing-box / amnezia-box), with traffic obfuscation and censorship circumvention. This is an **independent fork**. It fixes the original's dropped and "fake" connections and removes every request the app made to the original mod author's infrastructure. It is not affiliated with Proton AG or with the original author; use at your own risk.

## Why this fork

The original app had three groups of problems:

1. It showed "Connected" while no traffic went through the tunnel.
2. The VPN did not recover after a short network loss (cell handover) or after the system killed its process.
3. It sent data to the author's servers, including your real IP address, bypassing the VPN.

All three are fixed. See the table above and [FORK_CHANGES.md](FORK_CHANGES.md#english).

## Security analysis of the original (commit `e4b08fa`)

This section describes what the original code did and allowed. It is not proof of malicious intent.

- **Real IP:** on every launch and after every disconnect, the app asked the author's deployments (Cloudflare, Deno, Vercel, a remotely configured "event" host) for your IP address. The socket was bound to the physical network, so the request bypassed the VPN. The VPN exit IP followed after connecting. This happened in all builds and could not be turned off.
- **Account:** with "API bypass" set to Netlify, Cloudflare, Deno or "event" (Netlify was the default), all Proton API traffic went through the author's proxies, where TLS was terminated. That traffic included:
  - the login and the SRP exchange, which allows offline password guessing;
  - 2FA codes;
  - access and refresh tokens;
  - an `ANDROID_ID` hash.

  The published `netlify.toml` is a plain pass-through with no logging. What was actually deployed cannot be verified: the Cloudflare and Deno proxies run code, and the "event" host was delivered remotely.
- **Sentry, anti-tamper reports, OTA updates** (the APK was downloaded without a hash check) and a daily remote config fetch from six author mirrors. Separately from the author, a TLS trust manager accepted any certificate chain the system rejected.
- **Not found in the code:** use of the phone as a proxy or network node, and access to VPN traffic content. The OTA channel, however, could have shipped any code later.
- **If you used the original:**
  1. Revoke your Proton sessions (account.proton.me → Security → Sessions).
  2. Change a weak or reused password and enable 2FA.
  3. Uninstall the original app.

## Install

Download the APK from [Releases](https://github.com/drxekus/ProtonVPN-Next/releases) and check its SHA-256. It is signed with this fork's key and uses the package `ru.protonmod.next.privacy`, so uninstall the original's privacy build first. It requires arm64 and Android 10+. To build `stablePrivacy` yourself:

```bash
./gradlew :app:assembleStablePrivacyDebug -PEXPECTED_SIGNATURE="<your signing cert SHA-256>"
```

You need JDK 21, SDK platform 37.1, build-tools 37.0.0, NDK 29.0.14206865, CMake 3.22.1, Go 1.25.5 and python3. In this fork, both flavors have Sentry and OTA disabled.

For reliable background operation on aggressive OEM ROMs:
- allow auto-start;
- remove battery restrictions;
- enable Android's "Always-on VPN" with "Block connections without VPN".

**Split tunnelling with "Block connections without VPN".** With blocking on, Android drops all traffic that bypasses the VPN, so excluded apps, IPs and domains have no internet. For apps, Android has a hidden lockdown allowlist. With [Shizuku](https://shizuku.rikka.app/) running, the split tunnelling screen writes it for you ("Allow in Shizuku", then "Write list", then reboot). Otherwise:
1. Run `adb shell settings put secure always_on_vpn_lockdown_whitelist pkg1,pkg2`.
2. Reboot.
3. Exclude the same apps in the app's split tunnelling.

Toggling Always-on or blocking in Android settings clears the list. There is no such list for IPs and domains.

**Automatic obfuscation.** When a network silently drops WireGuard handshakes, the next attempt also changes the obfuscation (standard → more and larger junk → a fresh QUIC-like first packet). What works is remembered per network and chosen first, by Thompson sampling over decaying results. Only pre-handshake packets change, as Proton runs plain WireGuard.

License: GPL-3.0. Based on ProtonVPN-Next by SMH01, with copyright notices retained. Report issues at [Issues](https://github.com/drxekus/ProtonVPN-Next/issues).
