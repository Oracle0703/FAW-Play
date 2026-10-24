# Autunn Play

An independently maintained Android head-unit app based on DiPlay / xcertplay, providing wired and wireless CarPlay on compatible systems. Vehicle-specific dashboard, HUD and steering-wheel integrations depend on the head unit and firmware.

This project has no affiliation with or official authorization from China FAW, Hongqi or Apple. Vehicle names identify compatibility only. CarPlay is a trademark of Apple Inc.

Current source version: `0.3.2-AUTUNN20` (151), a client refactoring build for vehicle acceptance. It separates connection, display, media, settings, authorization and update responsibilities, and improves Android 9 recovery and output isolation. Firmware 3212 and 3222 require separate physical acceptance. The Android application ID remains `com.shihab.diplay`; upgrades use the existing release signing identity and retain activation, settings and pairing data. Previously issued activation codes remain valid under their original terms.

See the [current Chinese guide](README.zh-CN.md), [client architecture](CLIENT_ARCHITECTURE.md), [build guide](docs/BUILD.md), [release notes](docs/RELEASE-NOTES-NEXT.md) and [third-party notices](docs/THIRD_PARTY_NOTICES.md). Preserve LICENSE and all applicable upstream notices with the source. Release signing keys, production license configuration and accessory credentials are supplied privately at build time and must not be included in source distributions.

The original DiPlay README is preserved as [upstream history](docs/UPSTREAM-DIPLAY-README.md); its download channels, versions and test claims do not describe this release.
