# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/wadackel/oripane/security/advisories/new),
not in a public issue or discussion.

A useful report says what an attacker can do, under which conditions (another machine on the LAN
or the tailnet, a machine in the middle while a phone pairs, another app on the phone, another
program on the Mac), and how to reproduce it.

Oripane is maintained by one person in their spare time, so there is no guaranteed response time.
Reports are read, and a confirmed issue is fixed on `main`.

## Supported versions

Only the latest commit on `main` is supported. There are no release branches and no prebuilt apps:
the Mac app and the Android app are built from source.

## What Oripane is designed to protect

[`docs/security.md`](docs/security.md) describes how a phone pairs and who approves it, how the
connection is encrypted, what a paired phone can do on the Mac, where the pairing keys are kept,
what the self-signed signing key allows, and what is never logged. A report showing that Oripane
does not do what that document says is in scope.
