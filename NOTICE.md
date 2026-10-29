# Project origins

This new implementation of Leverage continues the work started by MacKey-255 in:

- https://github.com/MacKey-255/LEVERAGE-Launcher
- https://github.com/MacKey-255/LEVERAGE-Web

The current architecture and code are being rewritten to bring the launcher and service together in
one product. This notice visibly preserves the project's origins; the GitHub fork relationship will
be established when creating the remote repository through GitHub's **Fork** feature.

## Optional external component

Leverage can download and run yushijinhun's unmodified `authlib-injector`:

- https://github.com/yushijinhun/authlib-injector

The file is obtained from the official site, validated with SHA-256 and kept outside the project's
source code. `authlib-injector` is distributed under AGPLv3 with the author's specific exception for
loading it as a Java Agent.
