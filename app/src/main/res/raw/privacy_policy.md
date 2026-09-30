# Политика конфиденциальности | Privacy Policy

*Редакция форка drxekus/ProtonVPN-Next, 30.09.2026*

## Русский

### 1. Кто мы
Proton VPN-Next — неофициальный клиент Proton VPN с открытым исходным кодом. Это форк, и он **не связан** ни с Proton AG, ни с автором оригинального мода. У форка нет серверов, аналитики и системы обновлений: разработчики форка не получают от приложения никаких данных.

### 2. Куда приложение обращается

| Куда | Что передаётся | Когда |
|---|---|---|
| API Proton (`vpn-api.proton.me` и др.) | вход по протоколу SRP (сам пароль не передаётся), токены сессии, запросы списка серверов, нагрузки и сертификата, определение местоположения | всегда: без этого VPN не работает. Proton видит ваш IP, как и с официальным клиентом |
| Серверы VPN Proton | зашифрованный туннель WireGuard / AmneziaWG | при подключении |
| Провайдеры зашифрованного DNS (Cloudflare, Google, Quad9, Mullvad, dns0 или выбранный вами) | имена хостов Proton, которые приложение ищет для **своих** запросов | вне VPN; ваш трафик через туннель сюда не идёт |
| `api.protonvpn.ch`, `connectivitycheck.gstatic.com` | проверочный HTTPS-запрос **через туннель** (видят только IP VPN) | после подключения, в режимах проверки «Сбалансированный» и «Агрессивный» |
| `1.1.1.1`, `8.8.8.8` (порт 443) | только установка TCP-соединения, без данных | предварительная проверка сети перед подключением |
| Зеркала Proton через DNS (1.1.1.1, 8.8.8.8) | запросы альтернативной маршрутизации Proton, как в официальном клиенте | только при «Обходе блокировки API» со стратегией «Зеркала Proton» |
| Ваш прокси | зашифрованный трафик к API Proton | только со стратегией «Свой прокси» |
| Источники списков блокировки NetShield | загрузка публичных списков | только при включённом NetShield |
| Сеть Tor | трафик через Tor | только в режиме Tor |

### 3. Чего приложение не делает
- Не отправляет отчёты о сбоях, аналитику и телеметрию (Sentry отключён во всех сборках форка).
- Не проверяет и не скачивает обновления.
- Не обращается к серверам автора оригинального мода и к любым серверам форка.
- Не видит и не записывает содержимое вашего VPN-трафика.

### 4. Что хранится на устройстве
Токены сессии, ключи WireGuard, кэш серверов, настройки, статистика трафика (если включена). Всё лежит во внутреннем хранилище приложения и не попадает в облачную резервную копию Android. Экспорт настроек через «Резервное копирование» делаете только вы; данные входа туда не попадают. Отладочные сборки дополнительно ведут локальный журнал событий VPN без IP-адресов.

### 5. Отказ от ответственности
Программа распространяется по лицензии GPL-3.0 «как есть», без каких-либо гарантий. Вы используете её на свой риск и сами отвечаете за соблюдение законов своей страны.

---

## English

### 1. Who we are
Proton VPN-Next is an unofficial, open-source Proton VPN client. This is a fork that is **not affiliated** with Proton AG or with the original mod's author. The fork has no servers, analytics or update system: its maintainers receive no data from the app.

### 2. What the app connects to
- **Proton API** (`vpn-api.proton.me` etc.): login via SRP (the password itself is never sent), session tokens, server list, loads, certificate and location requests. Proton sees your IP, as with the official client.
- **Proton VPN servers:** the encrypted WireGuard / AmneziaWG tunnel.
- **Encrypted DNS providers** (Cloudflare, Google, Quad9, Mullvad, dns0, or the one you choose): the Proton hostnames the app resolves for its own requests. Your tunnel traffic never goes here.
- **`api.protonvpn.ch`, `connectivitycheck.gstatic.com`:** one HTTPS check **through the tunnel** after connecting, in Balanced and Aggressive verification modes. These services only see the VPN IP.
- **`1.1.1.1`, `8.8.8.8` port 443:** a TCP connect with no data, as a network pre-check.
- **Only when you enable them:**
  - Proton mirrors via DNS (API bypass);
  - your own proxy;
  - NetShield block-list sources;
  - Tor.

### 3. What the app does not do
- No crash reports, analytics or telemetry: Sentry is disabled in every build of this fork.
- No update checks or downloads.
- No requests to the original author's servers or to any server run by this fork.
- No access to, or logging of, your VPN traffic.

### 4. What is stored on the device
Session tokens, WireGuard keys, server cache, settings and optional traffic statistics are kept in the app's private storage and are excluded from Android cloud backup. Debug builds also keep a local VPN event log without IP addresses.

### 5. Disclaimer
Distributed under GPL-3.0 "as is", without any warranty. You use it at your own risk and are responsible for complying with local laws.
