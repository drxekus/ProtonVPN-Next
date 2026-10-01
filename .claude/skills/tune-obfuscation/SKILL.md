---
name: tune-obfuscation
description: Pull the VPN event log and the obfuscation ladder's per-network results from a phone over adb (debug build) and suggest changes to the ladder's variants for the next release. Use when users report that a network blocks the tunnel or after a field test.
---

# Tune the obfuscation ladder from field data

The app picks AmneziaWG obfuscation per network with `ObfuscationLadder` / `ObfuscationAdvisor`
(`app/src/main/java/ru/protonmod/next/vpn/`). This skill reads what it learned on a real phone and
proposes better variants.

## 1. Collect (debug build, phone connected over adb)

The package of debug builds is `ru.protonmod.next.privacy`; `run-as` does not work on release builds.

```bash
adb shell run-as ru.protonmod.next.privacy cat files/vpn-events.log
adb shell run-as ru.protonmod.next.privacy cat files/vpn-events.log.1
adb shell run-as ru.protonmod.next.privacy cat shared_prefs/obfuscation_ladder.xml
adb logcat -d | grep -E "ObfuscationAdvisor|Obfuscation ladder|Endpoint .* failed|AmneziaVpnManager"
```

`obfuscation_ladder.xml` holds one JSON entry per network (`net:cell:<operator code>` or
`net:wifi:<hash>`): per variant `s` (successes), `f` (failures), `t` (last update, ms), decayed
with a 14-day half-life; `last` is the variant that last worked.

## 2. Analyze

- Per network: which variants fail and which work; whether failures cluster in time windows
  (blocks that come and go) or are permanent.
- From the event log: streaks of `engine: handshake did not complete`, the `verified` that ends
  them, and which variant was active (logcat `Obfuscation ladder: <variant>`).
- Whether failures follow the server (other networks work with the same server) or the network.

## 3. Propose

Change `ObfuscationLadder.paramsFor` ranges, add or drop a variant, or adjust the prior. Hard limits,
because Proton runs plain WireGuard:

- S1–S4 must stay 0 and H1–H4 must stay 1–4; only junk (Jc, Jmin, Jmax) and I1–I5 may change.
- Junk packets at most 1200 bytes (1280-byte path MTU minus headers).
- I1–I5 may use the engine's tags only: `<b 0xHEX>`, `<r N>`, `<rc N>`, `<rd N>`, `<t>`.

Add a unit test in `ObfuscationLadderTest` for any new variant. Never put a device model, an
operator name or code, or a location into commits, docs or release notes.
