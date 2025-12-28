# VOID - Slate + Block Architecture

A modern, modular Android architecture for the VOID secure messaging app.

## 🧱 The Lego Philosophy

```
┌──────────────────────────────────────────────────────────────────┐
│                        VOID APP                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                    SLATE (Core)                             │  │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐          │  │
│  │  │ Events  │ │  Crypto │ │ Storage │ │  Design │          │  │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘          │  │
│  └───────┼──────────┼──────────┼──────────┼───────────────────┘  │
│          │          │          │          │                       │
│  ┌───────┴──────────┴──────────┴──────────┴───────────────────┐  │
│  │                    BLOCK REGISTRY                           │  │
│  └─────────────────────────────────────────────────────────────┘  │
│          │          │          │          │          │            │
│     ┌────┴───┐ ┌────┴───┐ ┌────┴───┐ ┌────┴───┐ ┌────┴───┐       │
│     │Identity│ │ Rhythm │ │Messaging│ │Contacts│ │ Decoy  │       │
│     └────────┘ └────────┘ └────────┘ └────────┘ └────────┘       │
└──────────────────────────────────────────────────────────────────┘
```

## ✨ Key Principles

| Principle | How It Works |
|-----------|--------------|
| **Blocks are universes** | Each feature is completely self-contained with UI, state, domain, and data layers |
| **Slate is contracts** | Core provides interfaces, not implementations |
| **Events are connectors** | Blocks communicate via EventBus, never import each other |
| **Feature flags first** | Any block can be toggled at compile-time or runtime |

## 🗂 Project Structure

```
void/
├── build-logic/              # Gradle convention plugins
│   └── convention/
│       └── VoidBlockPlugin   # Enforces block isolation
│
├── slate/                    # Core infrastructure
│   ├── core/                 # Interfaces, base classes
│   ├── crypto/               # Encryption implementation
│   ├── storage/              # Secure storage
│   └── design/               # Design system
│
├── blocks/                   # Feature modules
│   ├── identity/             # 3-word identity
│   ├── rhythm/               # Rhythm key auth
│   ├── messaging/            # Core messaging
│   ├── contacts/             # Contact management
│   ├── decoy/                # Decoy mode
│   └── onboarding/           # Onboarding flow
│
└── app/                      # App shell (minimal wiring)
```

## 🔌 Adding a New Block

### 1. Create the module
```bash
mkdir -p blocks/groups/src/main/kotlin/com/void/block/groups
```

### 2. Add to settings.gradle.kts
```kotlin
include(":blocks:groups")
```

### 3. Create the BlockManifest
```kotlin
@Block(id = "groups", flag = "feature.groups.enabled")
class GroupsBlock : BlockManifest {
    override val id = "groups"
    override val routes = listOf(Route.Screen("groups/list"))
    
    override fun Module.install() {
        // Register dependencies
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        // Set up navigation
    }
}
```

### 4. Register in BlockLoader
```kotlin
private fun allBlocks() = listOf(
    // ...existing blocks
    GroupsBlock(),
)
```

## 🗑 Removing a Block

### Compile-time removal
Comment out in `settings.gradle.kts`:
```kotlin
// include(":blocks:groups")
```

### Runtime removal
Use feature flags:
```kotlin
featureFlags.setOverride("feature.groups.enabled", false)
```

## 📡 Cross-Block Communication

Blocks NEVER import each other. They communicate through events:

```kotlin
// Messaging block emits
eventBus.emit(MessageReceived(senderId = "ghost.paper.forty"))

// Contacts block observes
eventBus.observe<MessageReceived>().collect { event ->
    updateLastSeen(event.senderId)
}
```

## 🧪 Testing

Each block is tested in complete isolation:

```kotlin
class IdentityBlockTest {
    private val fakeStorage = FakeSecureStorage()
    private val fakeCrypto = FakeCryptoProvider()
    private val fakeEventBus = FakeEventBus()
    
    @Test
    fun `generates valid 3-word identity`() = runTest {
        val identity = generateIdentity()
        assertThat(identity.words).hasSize(3)
    }
}
```

## 🏗 Build Commands

```bash
# Build everything
./gradlew build

# Build specific block
./gradlew :blocks:identity:build

# Run all tests
./gradlew test

# Verify block isolation
./gradlew verifyBlockIsolation

# Remove a block and rebuild
# (just comment out in settings.gradle.kts, then:)
./gradlew clean build
```

## 🔒 Security Architecture

- **Crypto Provider**: Blocks use `CryptoProvider` interface, never raw crypto
- **Secure Storage**: All persistence through encrypted `SecureStorage`
- **No Cross-Block Data**: Blocks can't access each other's data
- **Event Bus Only**: Communication is auditable through events

## 📐 Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| **Koin over Hilt** | Simpler module system for dynamic loading |
| **MVI over MVVM** | Unidirectional flow, easier testing |
| **SharedFlow for events** | Hot stream, no replay needed |
| **Convention plugins** | Enforce rules at build time |

## 🎯 The Lego Guarantee

1. ✅ **Remove any block** → App still compiles and runs
2. ✅ **Add any block** → Just register it, no changes elsewhere  
3. ✅ **Test any block** → In complete isolation
4. ✅ **Swap implementations** → Replace slate modules freely
5. ✅ **Feature flags** → Toggle at runtime without code changes

---

Built with the Slate + Block architecture for VOID.
