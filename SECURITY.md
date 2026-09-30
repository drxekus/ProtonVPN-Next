# Безопасность | Security Policy

## Русский

### Поддерживаемые версии
Исправления безопасности выходят только для последнего состояния ветки `protonvpn-next-dev` этого репозитория.

### Как сообщить об уязвимости
- **Приватно (предпочтительно):** вкладка **Security → Report a vulnerability** в этом репозитории ([GitHub Private Vulnerability Reporting](https://github.com/drxekus/ProtonVPN-Next/security/advisories/new)).
- **Если уязвимость не опасна при публикации:** [Issues](https://github.com/drxekus/ProtonVPN-Next/issues).

Опишите версию или коммит, устройство и версию Android, шаги воспроизведения и что именно под угрозой (данные входа, токены, IP, трафик). Не прикладывайте свои токены, ключи и пароли.

### Что входит в область
- Утечка данных мимо VPN или третьим сторонам.
- Ошибки проверки TLS и сертификатов.
- Обход «Постоянного VPN» или kill switch.
- Уязвимости в нашем патче к ядру amnezia-box (`scripts/patches/`).

Уязвимости самого сервиса Proton направляйте в Proton, а уязвимости ядра amnezia-box/sing-box без нашего патча — в соответствующие проекты.

## English

**Supported versions:** only the latest state of the `protonvpn-next-dev` branch.

**Reporting:** use **Security → Report a vulnerability** in this repository (private), or [Issues](https://github.com/drxekus/ProtonVPN-Next/issues) for non-sensitive problems. Include the version or commit, device, Android version, reproduction steps and impact. Never attach your tokens, keys or passwords.

**In scope:** data leaks outside the VPN or to third parties, TLS/certificate validation, Always-on VPN or kill-switch bypasses, and our amnezia-box patches. Report issues in Proton's service to Proton, and issues in unpatched amnezia-box/sing-box upstream.
