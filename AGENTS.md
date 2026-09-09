# Repository Guidelines

## Project Structure & Module Organization

This Kotlin Multiplatform project contains two modules. `shared/src/commonMain/kotlin/` holds protocol models, validation, frame codecs, and register conversion. `composeApp/src/desktopMain/kotlin/` contains the Compose Desktop UI, JVM socket transports, jSerialComm integration, and preferences. Tests mirror production code under `commonTest` and `desktopTest`. Keep generated files under module `build/` directories and packaged resources under `composeApp/src/desktopMain/composeResources/`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper once project scaffolding is present:

- `./gradlew build` — compile all configured targets and run verification tasks.
- `./gradlew allTests` — run shared codec and JVM transport/state tests.
- `./gradlew :composeApp:run` — launch the desktop application locally, if that task is configured.
- `./gradlew :composeApp:packageDmg` or `packageMsi` — create an unsigned installer on the matching OS.
- `./gradlew clean` — remove generated build output when diagnosing stale artifacts.

Do not rely on a system-installed Gradle version. On Windows, use `gradlew.bat` with the same task names.

## Coding Style & Naming Conventions

Follow standard Kotlin style with four-space indentation, trailing commas in multiline declarations, and explicit visibility for public APIs. Use `UpperCamelCase` for types and composables, `lowerCamelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Name transport implementations by role, for example `TcpModbusTransport`. Keep protocol parsing deterministic and separate from UI and operating-system I/O. Run `./gradlew check` before submitting changes.

## Testing Guidelines

Use `kotlin.test` for shared unit tests. Name test files after the subject (`ModbusFrameTest.kt`) and tests by behavior, such as `rejectsFrameWithInvalidCrc`. Cover frame encoding/decoding, exception responses, timeouts, byte ordering, and malformed input. Isolate UART and socket code behind interfaces so tests can use fakes without hardware or network access.

## Commit & Pull Request Guidelines

No Git history is available yet. Use short, imperative commit subjects, optionally with a scope: `feat(tcp): add connection timeout`. Keep commits focused. Pull requests should describe behavior changes, list tested targets and commands, link relevant issues, and include screenshots for UI changes. Call out any hardware assumptions or platform-specific limitations.

## Security & Configuration

Do not commit device paths, network credentials, or captured production traffic. Keep generated build directories and local IDE settings out of version control.
