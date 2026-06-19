# AgentChat iOS

SwiftUI rewrite of the Android AgentChat app.

## Features

- Native SwiftUI chat workspace with menu access to conversation history, Memory, Prompts, Tool Center, and Settings.
- OpenAI-compatible Chat Completions streaming with `URLSession`.
- API keys stored in iOS Keychain through the Security framework.
- Provider settings mirror the current Android app: chat model, embedding model, rerank model, retrieval mode, temperature, top-p, top-k, and max tokens.
- Local memories, prompts, tool settings, attachments, conversation history, and conversation state persisted locally.
- Chat and Agent modes with visible plan/action/result timeline, file/image attachment controls, web-search toggle, and model parameter sheet.

## Open

Open `ios/AgentChat/AgentChat.xcodeproj` in Xcode, choose an iOS simulator, then run the `AgentChat` scheme.

Command-line build:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild \
  -project ios/AgentChat/AgentChat.xcodeproj \
  -scheme AgentChat \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/AgentChatDerivedData \
  build
```
