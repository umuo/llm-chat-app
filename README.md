# AgentChat

Android LLM chat app scaffold for `com.lacknb.agentchat`.

## Baseline

- Package name: `com.lacknb.agentchat`
- Build scripts: Kotlin DSL
- Architecture: multi-module
- API style: OpenAI-compatible Chat Completions
- Current SDK baseline: `compileSdk 33`, `targetSdk 33`, `minSdk 26`
- UI: Jetpack Compose + Material 3

## Modules

- `:app` - Android application shell and top-level navigation.
- `:core:designsystem` - Material 3 theme and shared design primitives.
- `:core:model` - shared provider/chat/agent models.
- `:core:network` - Chat Completions DTOs and streaming client contract.
- `:core:harness` - Agent Harness and Agent Policy contracts.
- `:core:provider` - local provider settings and Android Keystore-backed API key storage.
- `:feature:chat` - chat surface and Chat/Agent mode switch.
- `:feature:settings` - OpenAI-compatible provider configuration surface.

## Verified

The following module-level Kotlin compilation has been verified:

```bash
./gradlew --no-daemon \
  :core:model:compileDebugKotlin \
  :core:harness:compileDebugKotlin \
  :core:network:compileDebugKotlin \
  :core:provider:compileDebugKotlin \
  :core:designsystem:compileDebugKotlin \
  :feature:chat:compileDebugKotlin \
  :feature:settings:compileDebugKotlin
```

`assembleDebug` currently reaches app-level Android tasks, but the local Gradle/Android task execution becomes very slow or stalls in this environment. Core and feature source modules compile successfully.

## Current Vertical Slice

- Settings saves the OpenAI-compatible base URL, model, and API key.
- API keys are encrypted using Android Keystore AES-GCM before being persisted.
- Chat mode streams responses through the Chat Completions endpoint.
- Agent mode is visible in the UI; Agent Harness contracts are scaffolded for the next implementation step.
