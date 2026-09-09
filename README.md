# Modbus Tool

Modbus Tool is a Kotlin Multiplatform desktop client for inspecting and controlling Modbus devices from macOS, Windows, and Linux. It supports Modbus RTU over serial ports and MBAP-framed Modbus over TCP or UDP.

## Features

- Function codes 01, 02, 03, 04, 05, 06, 15, and 16
- One-shot requests and repeated read polling
- Serial RTU settings for port, baud rate, parity, stop bits, unit ID, and timeout
- TCP and UDP endpoints with hostname resolution and response validation
- Unsigned, signed, hexadecimal, 32-bit integer, and float register views
- High-word-first and low-word-first 32-bit ordering
- Raw TX/RX traffic log and zero-based/reference address display
- Automatic restoration of the last workspace

## Requirements

- JDK 17 or newer; installer tasks require a full JDK distribution containing `jpackage`
- macOS, Windows, or Debian-based Linux for platform-native packaging

The Gradle wrapper downloads all other build dependencies.

## Build and run

```shell
./gradlew build allTests
./gradlew :composeApp:run
```

On Windows, replace `./gradlew` with `gradlew.bat`.

Create an unsigned installer on the matching host operating system:

```shell
./gradlew :composeApp:packageDmg  # macOS
gradlew.bat :composeApp:packageMsi  # Windows
./gradlew :composeApp:packageDeb  # Debian/Ubuntu Linux
```

## Publishing a release

Create and push a stable semantic-version tag such as `v1.2.3`. In GitHub Actions, open **Build release**, choose **Run workflow**, and enter that tag. The workflow builds separate macOS ARM64 and Intel DMGs, a Windows x64 MSI, and a Linux x64 DEB. It then creates a GitHub Release, generates release notes from merged changes, attaches SHA-256 checksums, and uploads every installer. These unsigned packages are experimental until platform signing is configured.

## Using the client

1. Choose Serial RTU, TCP, or UDP and enter the connection settings.
2. Connect, select a function, and enter the zero-based address encoded on the wire. The adjacent reference label shows the equivalent 0xxxx, 1xxxx, 3xxxx, or 4xxxx address.
3. For multiple writes, separate values with commas, semicolons, or new lines. Coil values accept `0/1`, `false/true`, or `off/on`.
4. Send once, or start polling for a read function. Polling waits for each exchange to finish before starting the configured interval.

UDP uses the same MBAP header and PDU as Modbus TCP, with one complete ADU per datagram. Serial mode supports RTU framing only; ASCII and broadcast requests are not supported.

## Security

Standard Modbus TCP, UDP, and RTU do not provide authentication or encryption. Use the tool only on trusted industrial networks or through an appropriately secured tunnel. Connection settings are stored locally, while traffic history is never persisted.
