# VexConnect for Android

Android SDK for wallets that connect to Vexanium dApps over VexConnect.

A dApp shows a `vexconnect://` QR code or link. The wallet scans it, the user approves the session, and from then on the dApp can send transaction requests that the wallet signs and pushes to chain. Messages travel through a WebSocket relay and are end-to-end encrypted, so the relay only routes them and cannot read them.

## Requirements

- Android minSdk 21
- Java 17
- Kotlin coroutines in your app

The library declares the `INTERNET` permission, so you don't need to add it yourself.

## Installation

Add JitPack to your repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependency to your app module. Replace the version with the latest release tag:

```kotlin
dependencies {
    implementation("com.github.NodeSpark-Labs:vexconnect-android:v1.0.3")
}
```

## Usage

### 1. Parse the URI

Pass the scanned QR text or the incoming link to `parseUri`. It accepts a `vexconnect://` URI, or an `https://` App Link that carries one in its `uri` query parameter. It returns `null` if the URI is invalid.

```kotlin
val uri = VexConnect.parseUri(scannedText) ?: return showError("Not a VexConnect link")

// Show these in your approval screen
uri.dappName
uri.dappUrl
uri.dappIcon
```

### 2. Create a bridge and ask the user

`createBridge` generates the wallet's key pair, derives the session key and connects to the relay. Pass the account the user wants to connect and its public key.

```kotlin
val bridge = VexConnect.createBridge(
    uri       = uri,
    account   = "myaccount123",
    publicKey = "PUB_K1_...",
)

// User taps Approve
bridge.approve()

// Or user taps Reject; this also closes the connection
bridge.reject("User rejected")
```

### 3. Handle requests

Collect `bridge.events`. Each `TransactionRequest` carries one or more Antelope actions. Show them to the user, sign and push the transaction with your own signing code, then send the result back.

```kotlin
lifecycleScope.launch {
    bridge.events.collect { event ->
        when (event) {
            is VexConnectEvent.TransactionRequest -> {
                val request = event.request
                // request.actions: List<AntelopeAction> (account, name, authorization, data)
                try {
                    val result = signAndPush(request.actions) // your wallet code
                    bridge.respondSuccess(request.requestId, result.txId, result.blockNum)
                } catch (e: Exception) {
                    bridge.respondError(request.requestId, e.message ?: "Transaction failed")
                }
            }
            is VexConnectEvent.Disconnected -> {
                // The dApp ended the session or the relay closed
            }
        }
    }
}
```

If the user declines a request, answer with `respondError` so the dApp isn't left waiting.

### 4. Disconnect

```kotlin
bridge.disconnect()
```

This tells the dApp the session has ended and closes the connection.

### Reconnecting

The relay connection reconnects on its own with exponential backoff. `bridge.isReconnecting` is a `StateFlow<Boolean>` you can use to show a status in the UI. When the network comes back or the app returns to the foreground, call `bridge.reconnectNow()` to skip the wait.

### Restoring a session after restart

To keep a session across app restarts, save these values when the session is approved:

```kotlin
bridge.pendingSession  // sessionId, dappName, dappUrl, dappIcon
bridge.relayUrl
bridge.key             // derived session key
bridge.account
bridge.publicKey
```

Restore it later with `createBridgeRestored`:

```kotlin
val bridge = VexConnect.createBridgeRestored(
    sessionId  = saved.sessionId,
    relayUrl   = saved.relayUrl,
    dappName   = saved.dappName,
    dappUrl    = saved.dappUrl,
    dappIcon   = saved.dappIcon,
    derivedKey = saved.key,
    account    = saved.account,
    publicKey  = saved.publicKey,
)
```

`bridge.key` decrypts every message in the session. Store it encrypted, for example with a key held in the Android Keystore, and delete it when the session ends.

## Security

- The dApp and the wallet each create a one-time X25519 key pair. The session key is derived with ECDH and HKDF-SHA256.
- Every message is encrypted with AES-256-GCM. The relay sees only the message type and session ID.
- The SDK never touches the wallet's private keys. Signing transactions stays in your wallet code.

## License

Copyright (c) 2026 PT Nodespark Digital Tech.

You may use the unmodified SDK in your apps, including commercial wallets and dApps. You may not modify it, rebrand it, or sell it on its own. See [LICENSE](LICENSE) for the full terms. For other uses, contact info@nodespark.org.
