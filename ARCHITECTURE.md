# VOID: Slate + Block Architecture

## Philosophy: Lego-Style Modular Design

Traditional Clean Architecture creates coupling through shared layers. The **Slate + Block** pattern inverts this: the core is deliberately minimal, and features are self-contained universes that simply *announce* their existence.

```
┌─────────────────────────────────────────────────────────────────┐
│                          APP SHELL                               │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                      SLATE (Core)                           ││
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           ││
│  │  │ Router  │ │ DI Hub  │ │ Events  │ │ Crypto  │           ││
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘           ││
│  └───────┼──────────┼──────────┼──────────┼────────────────────┘│
│          │          │          │          │                      │
│  ┌───────┴──────────┴──────────┴──────────┴────────────────────┐│
│  │                    BLOCK REGISTRY                            ││
│  └──────────────────────────────────────────────────────────────┘│
│          │          │          │          │          │           │
│     ┌────┴───┐ ┌────┴───┐ ┌────┴───┐ ┌────┴───┐ ┌────┴───┐      │
│     │Identity│ │ Rhythm │ │Messaging│ │Contacts│ │ Decoy  │      │
│     │ Block  │ │ Block  │ │ Block  │ │ Block  │ │ Block  │      │
│     └────────┘ └────────┘ └────────┘ └────────┘ └────────┘      │
│        BLOCK      BLOCK      BLOCK      BLOCK      BLOCK         │
└─────────────────────────────────────────────────────────────────┘
```

## Core Principles

### 1. Blocks Are Universes
Each block contains EVERYTHING it needs:
- UI (Compose screens)
- State management (MVI)
- Domain logic
- Data/Repository
- Tests
- Its own Gradle module

A block NEVER imports another block directly.

### 2. Slate Is A Contract, Not Implementation
The slate provides:
- Interfaces (contracts) blocks must implement
- A registry where blocks announce themselves
- Shared infrastructure (crypto, storage encryption)
- Navigation routing
- Event bus for cross-block communication

### 3. Blocks Communicate Through Events
Blocks don't call each other. They emit events that other blocks can observe:
```kotlin
// Messaging block doesn't know about Contacts block
eventBus.emit(MessageReceived(senderId = "ghost.paper.forty"))

// Contacts block observes and updates its "last seen" independently
eventBus.observe<MessageReceived> { updateLastSeen(it.senderId) }
```

### 4. Feature Flags Are First-Class
Every block can be toggled at:
- Compile time (exclude Gradle module)
- Runtime (feature flag check)

```kotlin
@Block(
    id = "decoy",
    enabledByDefault = true,
    flag = "feature.decoy.enabled"
)
```

---

## Project Structure

```
void/
├── build-logic/                    # Gradle convention plugins
│   └── convention/
│       ├── src/main/kotlin/
│       │   ├── VoidBlockPlugin.kt  # Plugin for all blocks
│       │   └── VoidSlatePlugin.kt  # Plugin for slate modules
│       └── build.gradle.kts
│
├── slate/                          # Core infrastructure
│   ├── core/                       # Contracts, interfaces, base classes
│   │   ├── src/main/kotlin/
│   │   │   └── com/void/slate/
│   │   │       ├── block/          # Block registration system
│   │   │       │   ├── Block.kt
│   │   │       │   ├── BlockManifest.kt
│   │   │       │   └── BlockRegistry.kt
│   │   │       ├── event/          # Event bus
│   │   │       │   ├── Event.kt
│   │   │       │   └── EventBus.kt
│   │   │       ├── navigation/     # Navigation contracts
│   │   │       │   ├── Route.kt
│   │   │       │   └── Navigator.kt
│   │   │       ├── state/          # MVI base
│   │   │       │   ├── State.kt
│   │   │       │   ├── Intent.kt
│   │   │       │   └── StateHolder.kt
│   │   │       └── crypto/         # Crypto contracts
│   │   │           └── CryptoProvider.kt
│   │   └── build.gradle.kts
│   │
│   ├── crypto/                     # Crypto implementation
│   │   └── src/main/kotlin/
│   │       └── com/void/slate/crypto/
│   │           ├── TinkCryptoProvider.kt
│   │           ├── SignalProtocol.kt
│   │           └── QuantumResistant.kt
│   │
│   ├── storage/                    # Encrypted storage
│   │   └── src/main/kotlin/
│   │       └── com/void/slate/storage/
│   │           ├── SecureStorage.kt
│   │           └── SqlCipherDatabase.kt
│   │
│   └── design/                     # Design system (Compose)
│       └── src/main/kotlin/
│           └── com/void/slate/design/
│               ├── theme/
│               ├── components/
│               └── tokens/
│
├── blocks/                         # Feature modules (Lego pieces)
│   ├── identity/                   # 3-word identity system
│   │   ├── src/
│   │   │   ├── main/kotlin/com/void/block/identity/
│   │   │   │   ├── IdentityBlock.kt       # Block manifest
│   │   │   │   ├── ui/                    # Compose UI
│   │   │   │   │   ├── IdentityScreen.kt
│   │   │   │   │   └── IdentityViewModel.kt
│   │   │   │   ├── domain/                # Business logic
│   │   │   │   │   ├── GenerateIdentity.kt
│   │   │   │   │   └── WordDictionary.kt
│   │   │   │   ├── data/                  # Data layer
│   │   │   │   │   └── IdentityRepository.kt
│   │   │   │   └── events/                # Events this block emits
│   │   │   │       └── IdentityEvents.kt
│   │   │   └── test/kotlin/               # Block's own tests
│   │   │       └── com/void/block/identity/
│   │   │           ├── GenerateIdentityTest.kt
│   │   │           └── IdentityScreenTest.kt
│   │   └── build.gradle.kts
│   │
│   ├── rhythm/                     # Rhythm key authentication
│   │   ├── src/main/kotlin/com/void/block/rhythm/
│   │   │   ├── RhythmBlock.kt
│   │   │   ├── ui/
│   │   │   ├── domain/
│   │   │   │   ├── RhythmCapture.kt
│   │   │   │   ├── RhythmMatcher.kt
│   │   │   │   └── RecoveryPhrase.kt
│   │   │   └── events/
│   │   └── build.gradle.kts
│   │
│   ├── messaging/                  # Core messaging
│   │   └── ...
│   │
│   ├── contacts/                   # Contact management
│   │   └── ...
│   │
│   ├── decoy/                      # Decoy mode
│   │   └── ...
│   │
│   ├── onboarding/                 # Onboarding flow
│   │   └── ...
│   │
│   └── settings/                   # Settings
│       └── ...
│
├── app/                            # App shell (just wiring)
│   ├── src/main/kotlin/com/void/
│   │   ├── VoidApp.kt              # Application class
│   │   ├── MainActivity.kt         # Single activity
│   │   ├── BlockLoader.kt          # Discovers and loads blocks
│   │   └── di/                     # DI wiring
│   │       └── AppModule.kt
│   └── build.gradle.kts
│
├── settings.gradle.kts
└── gradle.properties
```

---

## The Block System

### Block Manifest
Every block declares itself with a manifest:

```kotlin
// blocks/identity/src/main/kotlin/.../IdentityBlock.kt
@Block(id = "identity")
class IdentityBlock : BlockManifest {
    
    override val routes: List<Route> = listOf(
        Route.Screen("identity/generate"),
        Route.Screen("identity/display"),
    )
    
    override val events = BlockEvents(
        emits = listOf(IdentityCreated::class, IdentityRegenerated::class),
        observes = listOf(AppStarted::class)
    )
    
    override fun Module.install() {
        // Register this block's dependencies
        single { IdentityRepository(get()) }
        single { GenerateIdentity(get()) }
        viewModel { IdentityViewModel(get(), get()) }
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        composable("identity/generate") {
            IdentityScreen(
                viewModel = koinViewModel(),
                onComplete = { navigator.navigate("rhythm/setup") }
            )
        }
    }
}
```

### Block Registry
The app shell discovers blocks at startup:

```kotlin
// app/src/main/kotlin/.../BlockLoader.kt
object BlockLoader {
    
    private val blocks = mutableListOf<BlockManifest>()
    
    fun discover(): List<BlockManifest> {
        // Auto-discovered via ServiceLoader or manual registration
        return listOf(
            IdentityBlock(),
            RhythmBlock(),
            MessagingBlock(),
            ContactsBlock(),
            DecoyBlock(),
            OnboardingBlock(),
            SettingsBlock(),
        ).filter { it.isEnabled() }
    }
    
    fun BlockManifest.isEnabled(): Boolean {
        val flag = this::class.findAnnotation<Block>()?.flag ?: return true
        return FeatureFlags.isEnabled(flag)
    }
}
```

---

## Event-Driven Communication

Blocks communicate through a typed event bus:

```kotlin
// slate/core/src/.../event/EventBus.kt
interface EventBus {
    suspend fun emit(event: Event)
    fun <T : Event> observe(type: KClass<T>): Flow<T>
}

// Usage in Messaging block:
class MessagingViewModel(
    private val eventBus: EventBus
) : StateHolder<MessagingState>() {
    
    fun onMessageReceived(message: EncryptedMessage) {
        // Emit event - any block can observe
        viewModelScope.launch {
            eventBus.emit(MessageReceived(
                senderId = message.senderId,
                timestamp = message.timestamp
            ))
        }
    }
}

// Contacts block observes without knowing about Messaging:
class ContactsViewModel(
    private val eventBus: EventBus
) : StateHolder<ContactsState>() {
    
    init {
        eventBus.observe<MessageReceived>()
            .onEach { event -> updateLastSeen(event.senderId) }
            .launchIn(viewModelScope)
    }
}
```

---

## Testing Strategy

### Block Isolation Testing
Each block is tested in complete isolation:

```kotlin
// blocks/identity/src/test/.../GenerateIdentityTest.kt
class GenerateIdentityTest {
    
    private val fakeStorage = FakeSecureStorage()
    private val fakeCrypto = FakeCryptoProvider()
    
    private val generateIdentity = GenerateIdentity(
        storage = fakeStorage,
        crypto = fakeCrypto
    )
    
    @Test
    fun `generates valid 3-word identity`() = runTest {
        val identity = generateIdentity()
        
        assertThat(identity.words).hasSize(3)
        assertThat(identity.words).allMatch { it in WordDictionary.words }
    }
    
    @Test
    fun `stores seed in secure storage`() = runTest {
        generateIdentity()
        
        assertThat(fakeStorage.contains("identity.seed")).isTrue()
    }
}
```

### Integration Testing (Block Combinations)
Test how blocks work together via events:

```kotlin
// app/src/test/.../BlockIntegrationTest.kt
class MessagingContactsIntegrationTest {
    
    private val eventBus = InMemoryEventBus()
    
    @Test
    fun `message received updates contact last seen`() = runTest {
        // Arrange
        val contactsViewModel = ContactsViewModel(eventBus)
        val messagingViewModel = MessagingViewModel(eventBus)
        
        // Act - Messaging emits event
        messagingViewModel.onMessageReceived(
            fakeMessage(senderId = "ghost.paper.forty")
        )
        
        // Assert - Contacts reacted
        assertThat(contactsViewModel.state.value.contacts)
            .anyMatch { it.id == "ghost.paper.forty" && it.lastSeen != null }
    }
}
```

### Remove a Block Test
Verify the app works without a block:

```kotlin
@Test
fun `app starts without decoy block`() = runTest {
    // Simulate compile-time removal
    val blocks = BlockLoader.discover().filter { it.id != "decoy" }
    
    val app = VoidApp(blocks)
    app.start()
    
    assertThat(app.isRunning).isTrue()
    assertThat(app.routes).doesNotContain("decoy/*")
}
```

---

## Adding a New Feature (Block)

### Step 1: Create the Block Module

```bash
# From project root
./gradlew :blocks:newBlock --name=groups
```

This generates:
```
blocks/groups/
├── src/
│   ├── main/kotlin/com/void/block/groups/
│   │   ├── GroupsBlock.kt
│   │   ├── ui/
│   │   ├── domain/
│   │   ├── data/
│   │   └── events/
│   └── test/kotlin/
└── build.gradle.kts
```

### Step 2: Define the Block Manifest

```kotlin
@Block(
    id = "groups",
    flag = "feature.groups.enabled",
    enabledByDefault = false  // Feature flag controlled
)
class GroupsBlock : BlockManifest {
    
    override val routes = listOf(
        Route.Screen("groups/list"),
        Route.Screen("groups/{groupId}"),
        Route.Screen("groups/create"),
    )
    
    override val events = BlockEvents(
        emits = listOf(GroupCreated::class, GroupMessageSent::class),
        observes = listOf(ContactAdded::class)  // React to contact additions
    )
    
    override fun Module.install() {
        single { GroupsRepository(get()) }
        viewModel { GroupsViewModel(get(), get()) }
    }
    
    @Composable
    override fun NavGraphBuilder.routes(navigator: Navigator) {
        composable("groups/list") { GroupListScreen() }
        composable("groups/{groupId}") { GroupChatScreen(it.arguments?.getString("groupId")) }
        composable("groups/create") { CreateGroupScreen() }
    }
}
```

### Step 3: Register in settings.gradle.kts

```kotlin
// settings.gradle.kts
include(":blocks:groups")  // Just add this line
```

### Step 4: The App Automatically Discovers It

```kotlin
// BlockLoader automatically finds GroupsBlock via ServiceLoader
// OR add explicitly:
fun discover() = listOf(
    // ... existing blocks
    GroupsBlock(),  // Add here
)
```

---

## Removing a Feature

### Compile-Time Removal
Comment out in `settings.gradle.kts`:
```kotlin
// include(":blocks:groups")  // Commented = removed
```

Build. Done. No other changes needed.

### Runtime Removal
```kotlin
// FeatureFlags.kt
object FeatureFlags {
    fun isEnabled(flag: String): Boolean = when (flag) {
        "feature.groups.enabled" -> false  // Disabled at runtime
        else -> true
    }
}
```

The block exists in APK but never loads.

---

## Dependency Rules (Enforced by Gradle)

```kotlin
// build-logic/convention/src/.../VoidBlockPlugin.kt
class VoidBlockPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.dependencies {
            // Blocks can ONLY depend on slate/core
            implementation(project(":slate:core"))
            implementation(project(":slate:design"))
            
            // Blocks CANNOT depend on other blocks
            // This is enforced - Gradle will fail if attempted
        }
        
        target.tasks.register("verifyNoCrossBlockDeps") {
            doLast {
                val deps = configurations.getByName("implementation").dependencies
                val blockDeps = deps.filter { it.name.startsWith(":blocks:") }
                if (blockDeps.isNotEmpty()) {
                    throw GradleException("Blocks cannot depend on other blocks: $blockDeps")
                }
            }
        }
    }
}
```

---

## Summary: The Lego Analogy

| Lego Concept | Slate + Block |
|--------------|---------------|
| **Baseplate** | `slate/core` - The foundation everything connects to |
| **Brick** | A Block (feature module) - Self-contained unit |
| **Studs** | Events - How bricks connect without fusing |
| **Instructions** | BlockManifest - How to install a brick |
| **Box** | App shell - Just holds the pieces together |

### Key Guarantees

1. **Remove any block** → App still compiles and runs
2. **Add any block** → Just register it, no changes elsewhere
3. **Test any block** → In complete isolation
4. **Swap implementations** → Replace slate modules without touching blocks
5. **Feature flags** → Toggle at runtime without code changes

This architecture makes VOID **infinitely extensible** while keeping the core **deliberately minimal**.
