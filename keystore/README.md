# Signing key

`notafraud.jks` signs every build (debug and release). Android only installs an update over
the existing app if it is signed with the same key, so this file must never change or be lost.
It lives in this private repo on purpose: the repo is the backup.

- alias: `notafraud`
- password: `notafraud-sideload` (store and key)

If this repo is ever made public, rotate nothing — just make it private again. A leaked key
only matters if someone could also get a malicious APK onto your phone.
