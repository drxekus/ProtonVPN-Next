# Чем этот форк отличается от оригинала

Форк проекта ProtonVPN-Next (база — коммит `e4b08fa`, версия `12.0.0-alpha2st3-38`). Все изменения проверены на реальном телефоне и покрыты юнит-тестами (193 теста проходят).

[English version below](#english)

## Стабильность соединения

**Проблема:** приложение показывало «Подключено» с зелёным замком, хотя трафик не шёл; в фоне VPN сам отключался после короткого обрыва сети (смена вышки) и не поднимался, пока не откроешь приложение; иногда соединение «подвисало» на 15–25 секунд.

- **«Самый быстрый» сервер — действительно ближайший из работающих.** Серверы на обслуживании не получают нагрузку от API Proton, она оставалась равной 0 — и именно они выбирались «самыми быстрыми». Новый общий `ServerSelector` берёт только серверы с онлайн-узлами и ключом WireGuard, серверы без данных о нагрузке ставит в конец, Secure Core и Tor — только если больше ничего нет. Одна логика для главного экрана, плитки, виджетов, автоподключения, стран, профилей и ротации IP (раньше — десять копий).
- **Сортировка по `Score` от Proton, как в официальном клиенте.** Раньше серверы сортировались только по нагрузке, и «самыми быстрыми» оказывались полупустые серверы на другом континенте. `Score` учитывает и нагрузку, и расстояние. Proton считает его по адресу, с которого пришёл запрос, а через туннель или зеркало API это неправильное место. Поэтому приложение, как и официальный клиент, передаёт заголовок `x-pm-netzone` с сетью пользователя (реальный IP с обнулённым последним октетом; Proton и так видит этот IP без VPN).
- **Честный статус «Подключено».** Он появляется только после рукопожатия WireGuard (движок переключён на уровень логов `debug` — на `info` рукопожатия не видны) и, в режимах «Сбалансированный» и «Агрессивный», после настоящего HTTPS-запроса через новую VPN-сеть. Раньше при неудачной проверке приложение всё равно писало «Подключено», а монитор сети был зарегистрирован с `NOT_VPN` и VPN-сеть вообще не видел.
- **Автоматический перебор.** Если сервер не отвечает, пробуются другие его узлы и порты, затем — для «самого быстрого», страны или города — следующий сервер в тех же пределах. Порт «Авто» начинает с последнего сработавшего, а не выбирается случайно.
- **VPN-сервис больше не сдаётся:**
  - при перезапуске системой и при старте через «Постоянный VPN» (Always-on) восстанавливает последний туннель из снимка на диске, вместо того чтобы сразу остановиться;
  - при потере сети ждёт её и переподключается с нарастающей паузой (раньше — остановка, если не включён скрытый «kill switch», который можно было включить только через AI);
  - повторные подключения сохраняют раздельное туннелирование;
  - если рукопожатия нет 12 секунд (три попытки) — туннель перезапускается. Из 23 таких зависаний в журналах 16 прошли сразу после перезапуска, чаще всего на той же сети: умирал сам UDP-поток, новый сокет работал мгновенно;
  - при реальной смене сети (Wi-Fi ↔ мобильная, новый адрес) туннель перезапускается через 1,5 секунды, если за это время от сервера ничего не пришло. По журналам перепривязка сокета сама не восстановила связь ни разу из 22 случаев, и соединение висело до срабатывания 25-секундного сторожа;
  - на время проверки и восстановления удерживается короткая блокировка сна (не дольше 60 секунд, снимается сразу после подтверждения). Без неё таймеры спящего телефона опаздывали: перезапуск с задержкой 1 секунда стартовал через 51 секунду;
  - исправлена гонка, из-за которой туннель мог провисеть «Подключено» без сети всю ночь: ответ на рукопожатие от старого движка «подтверждал» новый, ещё не запущенный, и для нового движка не включался сторож зависаний;
  - исправлена гонка при запуске туннеля: примерно каждый шестой свежий запуск VPN-сервиса падал с `no available network interface` и подключался только со второй попытки. Сеть сообщается движку из двух потоков, и запуск иногда не дожидался, пока движок узнает о ней. Теперь сообщения идут по очереди (на телефоне — 0 падений из 37 запусков против 3 из 19);
  - **честный статус при обрыве:** пока у телефона нет сети или сервер не отвечает на рукопожатие (примерно 7 секунд), приложение и уведомление пишут «Восстановление…», а не «Подключено». Обратно «Подключено» — только после ответа сервера;
  - после смены сети туннель перезапускается, если рукопожатие на новом пути не прошло за 7 секунд. Поступление данных само по себе ничего не доказывает: из 30 таких случаев в журналах 15 всё равно кончились перезапуском, только позже;
  - если VPN-процесс убит (энергосбережение прошивки, сбой), основной процесс узнаёт об этом через привязку к сервису и поднимает туннель заново — вместо вечного ложного «Подключено»;
  - VPN-процесс может разбудить приложение и попросить свежую конфигурацию (например, если сертификат истёк);
  - после обновления приложения туннель поднимается сам. Установка обновления убивает VPN-процесс, а Realme/ColorOS не перезапускает «Постоянный VPN», и с блокировкой без VPN телефон оставался без сети, пока не откроешь приложение.
  - после смахивания приложения из последних туннель тоже поднимается сам. ColorOS при этом убивает все процессы приложения, включая VPN со службой переднего плана, и не перезапускает её. Пока туннель должен работать, служба держит системный будильник; если он находит процесс убитым, туннель восстанавливается из снимка (на телефоне — через 1–2 минуты). Ежеминутная проверка не будит телефон, будит только страховочная раз в 15 минут сна. Кнопка «Отключить» будильник снимает. Чтобы простоя не было совсем, закрепите приложение в списке последних;
- **«Последний использованный» — это последний выбор пользователя, а не последний сервер.** Быстрое подключение в этом режиме, плитка, автоподключение и восстановление сервисом брали первый сервер из «Недавних». Туда попадал и сервер, выбранный автоматикой, и он закреплялся навсегда: однажды выбранный старой сортировкой Южный Судан возвращался при каждом подключении. Теперь запоминается выбор пользователя (самый быстрый, страна, город, сервер или цель профиля), и для него каждый раз заново выбирается лучший сервер.
- **Smart Routing не считается «самым быстрым».** У «виртуальных» стран Proton (сервер стоит в одной стране, а IP выдаёт другую) часто низкая нагрузка и хорошая оценка. Для общего «самого быстрого» они теперь идут после обычных серверов; выбрать их явно можно по-прежнему. В журнале подключения видна пятёрка лидеров с оценкой и нагрузкой.
- **Перебор серверов не ходит по кругу.** Когда VPN-процесс будил приложение, перебор начинался заново, и одни и те же порты одного сервера пробовались часами. Теперь перебор продолжается с места остановки (и начинается заново только через 5 минут, когда условия могли измениться), а выбранный вручную сервер, который совсем не отвечает, после 4 попыток меняется на лучший сервер в той же стране.
- **«Проверка…» не зависает.** Если проверочный запрос через туннель не прошёл, а переключиться было не на что, экран навсегда оставался в «Проверке…», хотя трафик шёл. Теперь проверка повторяется каждые 15 секунд, пока не пройдёт.
- **Капча при входе открывается и с включённым VPN.** При включённом «Обходе блокировки API» и любом активном VPN на телефоне страница проверки «я не робот» уходила на адрес API и показывала `404 Path not found` вместо капчи. Теперь при прямом подключении адрес меняется только у запросов к самому API.
- **Смена сети (вышки).** Движку сообщается только основная физическая сеть, как в официальном клиенте sing-box. Смена сети или IPv4-адреса на том же интерфейсе (`ccmni0` → новый адрес) теперь тоже считается сменой. Патч к amnezia-box (`scripts/patches/awgbox-awg-rebind.patch`): AWG-туннель при смене сети сразу пересоздаёт UDP-сокет и отправляет keepalive — в апстриме это делал только обычный WireGuard, поэтому соединение «висело», пока не сработают таймеры.
- **Меньше фоновой нагрузки:** уведомление обновляется раз в 5 секунд, а не каждую секунду (прошивки считали это активностью в фоне).
- **Мелочи:** повторное нажатие на сервер при неработающем туннеле больше не игнорируется; состояние «Подключение…» после ошибки сбрасывается; «Connect & Go» ждёт проверенного туннеля; прокси ByeDPI для обхода блокировки API запускается при старте приложения (раньше — только после открытия настроек); профили передают все параметры обфускации (S3/S4, I2–I5).

## Обход блокировок

- **Автоподбор обфускации («лестница»).** Если рукопожатие осталось без ответа, следующая попытка меняет и обфускацию: без неё → стандартный профиль → средний мусор (4–6 пакетов по 40–120 байт) → сильный (8–12 пакетов по 100–700 байт) → «живой QUIC» (I1 с заголовком QUIC Initial и случайными идентификатором и содержимым для каждого рукопожатия; движок поддерживает тег `<r N>`). Серверы Proton — обычный WireGuard, поэтому все варианты оставляют рукопожатие как есть (S1–S4 = 0, H1–H4 = 1–4) и меняют только пакеты перед ним.
- **Учится на своей сети.** Результаты хранятся отдельно для каждой сети (код оператора или хэш шлюза Wi-Fi, только на телефоне) и забываются с периодом полураспада 14 дней. Новое подключение берёт то, что сработало в этой сети последним; после неудачи следующий вариант выбирается сэмплированием Томпсона. Раз в неделю приложение снова пробует вариант пользователя — вдруг блокировку сняли.
- **Разные установки выглядят по-разному.** Размеры мусора и записанный QUIC-образец выбираются один раз для каждой установки: раньше все копии приложения слали побайтно одинаковый I1.
- Автоподбор работает с режимом «Без дополнительной защиты» и стандартным профилем AWG и выключается в настройках обфускации; свой профиль и цепочку прокси не трогает.
- **Обрывы связи не портят статистику.** Неудача засчитывается методу обфускации, только если Android видит интернет на самой сети (он проверяет это вне VPN), и не в первую минуту после смены сети. Раньше неудачи на мёртвой сети учились как блокировка: все варианты, кроме привычного, набрали по 80+ неудач и ни одного успеха, потому что их пробовали только при мёртвой сети. Накопленная так статистика сбрасывается один раз. В журнале событий теперь видно, был ли у сети интернет при каждой неудаче.
- Скилл Claude Code `tune-obfuscation` снимает с телефона журнал и статистику лестницы и помогает подобрать варианты для следующих версий.

## Приватность и безопасность

- **Реальный IP больше не уходит на серверы автора мода.** Главный экран при каждом открытии узнавал реальный IP в обход VPN через развёртывания автора (Cloudflare, Deno, Vercel, «event»-хост) — во всех сборках, включая privacy. Теперь IP и страна берутся у самого Proton (`vpn/v1/location`), без обхода туннеля.
- **Проверка TLS.** `MirrorTrustManager` принимал любую цепочку сертификатов, которую отвергла система, а большинство хостов не было закреплено пинами. Теперь принимается только то, чему доверяет Android, или сертификат, чей листовой ключ совпадает с опубликованными пинами Proton (альтернативная маршрутизация). `PinVerifier` проверяет только листовой сертификат — раньше подходил любой из цепочки.
- **«Обход блокировки API».** Удалены стратегии Netlify, Cloudflare, Deno и «событие»: это прокси автора, через которые проходили логин, коды 2FA и токены сессии (TLS заканчивался на них). Сохранённый выбор автоматически переводится на зеркала Proton. Остались: зеркала Proton, свой SOCKS5/HTTP-прокси, ByeDPI.
- Фоновая загрузка конфигурации «event bypass» с шести зеркал автора отключена во всех сборках.
- `allowBackup=false`: токены сессии не попадают в облачную резервную копию Android.
- **Ни одна сборка форка не обращается к инфраструктуре автора оригинала.** У варианта `standard` выключены OTA-обновления (сервер автора, APK без проверки хэша) и Sentry (проект автора) — так же, как у `privacy`. Ссылки в приложении («О приложении», окно анти-тампера) ведут на этот форк, а не на Telegram, GitLab и сайт автора. Политика конфиденциальности в приложении заменена описанием того, что приложение делает на самом деле.
- **AI-ассистент удалён.** Он отправлял внешним AI-провайдерам список установленных приложений и настройки и мог менять настройки, в том числе kill switch и DNS. Сохранённые ключи `ai_*` удаляются при запуске.

## Интерфейс

- **Поиск на экране стран.** Поле сверху ищет по названию страны, её коду (RS), городу и имени сервера (RS#23). Названия совпадают с начала слова, поэтому короткий запрос не тянет лишнего. Нажатие на результат подключает: к лучшему серверу страны или города либо к выбранному серверу.
- **Простые списки.** Страны, города и серверы — строки с разделителями вместо отдельных «стеклянных» плашек; полоса нагрузки убрана, процент остался; «три точки» заменены стрелкой вглубь. Нажатие на строку по-прежнему подключает.
- **Экраны настроек без шапки.** Убран крупный блок под заголовком (круг с иконкой, повтор заголовка, описание) — описания есть в кнопках ⓘ. Убраны декоративные фоновые градиенты.
- **Только светлая и тёмная тема.** Остальные темы удалены. Сохранённый выбор переносится в ближайшую: светлые по основе — в светлую, остальные — в тёмную, «системная» — по текущему режиму телефона.
- **Вид в духе приложения ChatGPT.** Чёрный фон, графитовые карточки без обводок и текстур, один синий акцент вместо фиолетового Proton; кнопки, вкладки, поиск, статус и нижняя панель — «таблетки»; заголовки разделов серые, обычным регистром. Светлая тема — те же нейтральные серые. При подключении сверху появляется полупрозрачный зелёный фон.
- **Сдержанные анимации** (по правилам скилла Emil Kowalski): лёгкое сжатие при нажатии (160 мс, сильный ease-out), выбранная вкладка плавно переезжает (220 мс), статус сменяется затуханием (200 мс). Частые действия почти не анимируются, ничего не длится дольше 300 мс.
- У каждого пункта настроек — кнопка ⓘ с описанием, что он делает на самом деле, когда его включать и что стоит по умолчанию (русский и английский).
- Удалён переключатель «Ожидать завершения проверки»: проверка теперь всегда честная.
- Нажатие на страну или город снова подключает к лучшему серверу в ней («три точки» — список городов и серверов). Раньше экран уходил на главную раньше, чем успевал прочитать список серверов, и подключение отменялось. То же исправлено для профилей.
- Предупреждение «VPN-сервер не отвечает» снимается при отключении.
- **Shizuku для раздельного туннелирования.** При «Блокировать соединения без VPN» исключённые приложения остаются без интернета. Если установлен Shizuku, экран раздельного туннелирования сам записывает их в системный список исключений блокировки (`always_on_vpn_lockdown_whitelist`). Права Shizuku используются ровно для двух команд — чтения и записи этой настройки — и только по нажатию кнопки; команда передаётся без shell, имена пакетов проверяются.
- В описании раздельного туннелирования сказано, что при «Блокировать соединения без VPN» Android отрезает исключённым приложениям интернет. В README — как дать им прямой доступ через системный список исключений блокировки.
- Исправлены ошибки в текстах: описание экрана загрузки серверов, подпись поля длительности паузы, подзаголовки Kill Switch и «Выйти», поле DNS обещало поддержку IPv6.

## Сборка и диагностика

- `scripts/build-awgbox-lib.sh` работает и на Windows (Git Bash), а ядро пересобирается автоматически при изменении набора патчей.
- Рекомендуемый вариант — **stablePrivacy** (без Sentry и OTA): `./gradlew :app:assembleStablePrivacyDebug -PEXPECTED_SIGNATURE=<SHA-256 вашего ключа>`.
- Отладочная сборка ведёт журнал событий VPN (смены сети, рукопожатия, восстановления; без IP-адресов): `adb shell run-as ru.protonmod.next.privacy cat files/vpn-events.log`.

## Что осталось как в оригинале

- Нативная библиотека анти-тампера (`libnext`) работает как раньше, изменены только её ссылки. Для своей сборки передайте `-PEXPECTED_SIGNATURE`, иначе появится предупреждение о неофициальной сборке.
- Мёртвый код оригинала (адреса прокси автора, источники «event»-конфига и OTA) частично остался в исходниках, но нигде не вызывается.
- На прошивках с агрессивным энергосбережением (Realme/ColorOS, Xiaomi и др.) для работы в фоне нужно разрешить автозапуск, снять ограничения батареи и включить «Постоянный VPN» с блокировкой соединений без VPN.

---

<a name="english"></a>
# How this fork differs from the original

A fork of ProtonVPN-Next at commit `e4b08fa`. Tested on a real phone; 193 unit tests pass.

**Connection stability**
- "Fastest" only picks servers that are online and have a WireGuard key. Servers under maintenance kept a load of 0 and used to win. One shared `ServerSelector` replaces ten copies of that logic.
- Servers are ranked by Proton's `Score` (load and distance), like the official client, instead of load alone, which made half-empty servers on another continent "fastest". The app sends `x-pm-netzone` (the user's network, last IPv4 octet zeroed) so the score is computed for the user rather than for the tunnel or API mirror.
- "Connected" is shown only after a real WireGuard handshake and, in Balanced/Aggressive mode, an HTTPS round trip through the new VPN network. The old check reported success even when it failed, and its network monitor could not see VPN networks at all.
- A silent server is replaced automatically: first its other nodes and ports, then the next server in the chosen scope (fastest, country or city). The "Auto" port starts from the last one that worked.
- The VPN service restores the last tunnel on sticky restart and Always-on start. It waits for a network and retries with backoff instead of stopping, preserves split tunnelling on retries, and restarts a stalled tunnel after 12 s (three handshake tries) without an answer: 16 of 23 stalls in field logs came back right after a restart, mostly on the same network, because only the old UDP flow was dead. The app notices a killed VPN process through a service binding and brings the tunnel back.
- When the network really changes (Wi-Fi ↔ cellular, a new address), the tunnel restarts after 1.5 s unless the server answered in that time: in field logs the socket rebind alone never recovered the link (0 of 22). A short wake lock (60 s max, released once verified) keeps recovery timers from running late on a sleeping phone. Fixed a race where a handshake answer from the old engine "verified" a new one that had not started yet, leaving it without a stall detector: "Connected" with no traffic all night.
- On a network change, only the default physical network is published. A new network or address on the same interface also counts as a change. A patch to amnezia-box makes the AWG endpoint rebind its socket and send a keepalive immediately, which upstream did only for plain WireGuard.
- The tunnel comes back by itself after an app update; ColorOS did not restart Always-on VPN, leaving the phone offline under lockdown.
- It also comes back after the app is swiped away from recents: ColorOS kills every process of the app, the VPN's foreground service included, and never restarts it. While a tunnel should be up, the service keeps an alarm armed; if it finds the process gone, the tunnel is restored from its snapshot (1–2 min on a phone). The one-minute check does not wake the phone; only a fallback wakes it after 15 minutes of sleep. Disconnecting disarms it. Locking the app in recents avoids the gap entirely.
- Fixed a start-up race: about one fresh VPN service start in six failed with `no available network interface` and connected only on the retry. Two threads published the first network, and the start sometimes went ahead before the engine knew it. Publishing is now serialized (0 failures in 37 starts on a phone, down from 3 in 19).
- While the phone has no network or the server stops answering handshakes (about 7 s), the app and the notification say "Restoring…" instead of "Connected"; "Connected" comes back only with an answered handshake. After a network change the tunnel restarts if a handshake on the new path goes unanswered for 7 s: incoming data alone proved nothing (15 of 30 such cases in field logs still needed a restart, later).
- "Last used" quick connect, the tile and auto-connect follow the user's last choice (fastest, country, city, server or profile target) instead of the most recent server, which pinned automatic picks forever. Smart Routing locations rank after real ones for a generic "fastest".
- Failover no longer starts over each time the VPN process wakes the app (it retried the same ports for hours); an exact server that does not answer is replaced by the best one in its country after 4 attempts.
- "Verifying…" no longer sticks: when the probe through the tunnel failed and failover had nothing to switch to, the screen stayed there for good while traffic flowed. The probe now repeats every 15 s until it passes.
- The human verification (captcha) page at login opens again with the API bypass on while a VPN is active: going direct, the client used to send every Proton host, verify.proton.me included, to the API host, which answered 404 "Path not found". Only API and proxy hosts are rewritten now.
- The notification refreshes every 5 s instead of every second.

**Censorship circumvention**
- Automatic obfuscation ladder: after a handshake gets no answer, the next attempt also changes the obfuscation (none → standard → medium junk → strong junk → "live QUIC" I1 with a random connection ID and payload per handshake). All variants keep the handshake Proton-compatible (S1–S4 = 0, H1–H4 = 1–4).
- It learns per network (operator code or Wi-Fi hash, on the device only) by Thompson sampling over results with a 14-day half-life, reuses what last worked, and retries the user's own setting weekly.
- Junk sizes and the recorded QUIC sample are drawn per install, so installs no longer send identical I1 packets.
- A failure counts against an obfuscation variant only when Android validated internet on the network itself (probed outside the VPN) and not in the first minute after a network change. Failures on dead networks used to teach the ladder that every variant but the habitual one was blocked (80+ failures, no success each, as they were only tried while the network was dead); those statistics are reset once. The event log notes whether the network had internet at each failure.
- A Claude Code skill, `tune-obfuscation`, pulls the ladder's data over adb to tune the variants.

**Privacy and security**
- The real IP is no longer sent to the mod author's servers, and the socket no longer bypasses the VPN. Location now comes from Proton's own `vpn/v1/location` API.
- TLS: any chain the system rejected used to be accepted. Now a chain passes only if the system trusts it or its leaf key matches one of Proton's pins.
- The author-run API proxies (Netlify, Cloudflare, Deno and "event") are removed, because they saw login data and session tokens. The event-bypass background fetch is disabled.
- `allowBackup=false`.
- No build of this fork contacts the original author's infrastructure: `standard` also has OTA and Sentry disabled; in-app links point to this fork; the in-app privacy policy describes what the app really does.
- The AI assistant is removed, along with its stored keys.

**UI**
- Search on the countries screen by country name, code, city and server name ("RS#23"); names match from the start of a word. Lists are plain rows with dividers; the load bar is gone, the percent stays; a chevron replaces the three dots.
- Settings screens lose the big header block (icon circle, repeated title, description; the ⓘ buttons keep the descriptions) and the decorative background gradients.
- Only the light and dark themes are left; a saved removed theme maps to the palette it was based on, "system" to the phone's current mode.
- A look after the ChatGPT app: black background, graphite surfaces without outlines or textures, one blue accent instead of Proton's purple, pill-shaped buttons, tabs, search, status and bottom bar, grey section titles in normal case; the light theme uses the same neutral greys. A translucent green wash appears at the top while connected.
- Quiet motion (after Emil Kowalski's design-engineering rules): a slight press scale (160 ms, strong ease-out), the selected tab glides (220 ms), the status crossfades (200 ms); frequent actions are barely animated and nothing runs longer than 300 ms.
- Every setting has an ⓘ explanation in English and Russian. Several wrong labels are fixed.
- With Shizuku installed, the split tunnelling screen writes the excluded apps into Android's lockdown exception list (two commands, on a tap only).
- Tapping a country or city connects to its best server again (the screen used to navigate away and cancel the connection); the same fix applies to profiles. The "server not responding" warning clears on disconnect.
- The split tunnelling note explains that Android's "Block connections without VPN" cuts excluded apps off; the README shows the lockdown allowlist workaround.

**Build and diagnostics**
- The AWGBox script works on Windows and rebuilds the core when the set of patches changes.
- Debug builds keep a VPN event log in `files/vpn-events.log`.
