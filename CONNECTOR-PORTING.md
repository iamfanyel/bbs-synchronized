# Running BBS Synchronized on NeoForge via Sinytra Connector

Sinytra Connector supports Minecraft **1.20.1** and **1.21.x** only. This mod currently targets
1.20.4 (because BBS FS does), so running it under Connector means porting both mods to one of
those versions first. This document is the compatibility audit and the exact port recipe, so the
port is a version bump — not a rewrite.

## Compatibility audit (already satisfied on the current codebase)

Connector translates Fabric mods at load time; the things that break mods under it are mixins into
vanilla/NeoForge-patched code, access wideners, Fabric loader internals, and exotic fabric.mod.json
features. This addon was audited against all of them:

| Connector requirement | Status |
| --- | --- |
| Intermediary-mapped jar (Connector requirement for processing) | ✅ standard loom `remapJar` output |
| No mixins into vanilla/NeoForge-patched code | ✅ one client mixin, and it targets a **BBS FS class** (`UIUtilityOverlayPanel`, for the F6 "Server Sync" buttons) — mixins between Fabric mods are transformed together by Connector; it's vanilla-targeting mixins that are risky |
| No access widener | ✅ none |
| No `net.fabricmc.loader` internals | ✅ zero references |
| Fabric API usage covered by Forgified Fabric API | ✅ only `fabric-networking-api-v1`, `fabric-lifecycle-events-v1`, `fabric-command-api-v2`, client networking/lifecycle — all core FFAPI modules |
| Mod id valid under (Neo)Forge rules (`^[a-z][a-z0-9_]{1,63}$`) | ✅ `bbs_synchronized` |
| `depends: "fabric-api"` resolvable | ✅ FFAPI provides the `fabric-api` mod id |
| Loader-agnostic behavior (threads, file IO, custom payload packets, Brigadier) | ✅ nothing loader-specific; `/bbs` root merges identically on NeoForge's dispatcher |
| Split client/common source sets | ✅ client classes only load through the `client` entrypoint |
| Java 17 bytecode | ✅ toolchain + `options.release` pinned to 17, so the jar runs on Java 17+ regardless of the JDK that built it |

No code changes are required for Connector itself.

## Runtime setup on a NeoForge server/client

1. NeoForge for the target Minecraft version
2. **Sinytra Connector**
3. **Forgified Fabric API** (replaces Fabric API — do *not* install regular Fabric API)
4. The ported BBS FS jar and this addon's ported jar (both plain Fabric jars; Connector loads them)

Clients can be Fabric or NeoForge+Connector independently of the server — the sync protocol is
plain custom payload packets and doesn't care about the loader on the other side.

## Port recipe: 1.20.1 (Connector LTS)

Everything this addon uses has an identical API surface in 1.20.1 — this port is configuration
only:

1. `gradle.properties`: `minecraft_version=1.20.1`, `yarn_mappings=1.20.1+build.10`,
   `fabric_version=0.92.6+1.20.1` (or latest 1.20.1 Fabric API).
2. `src/main/resources/fabric.mod.json`: `"minecraft": "~1.20.1"`.
3. Drop a 1.20.1 build of BBS FS into `libs/` and run `gradlew build`.

No Java changes: `new Identifier(...)`, the `Identifier`-based `ServerPlayNetworking`/
`ClientPlayNetworking` methods, `sendFeedback(Supplier, boolean)`, `handler.player`,
`isDisconnected()` and `PacketByteBufs` all exist unchanged in 1.20.1.

## Port recipe: 1.21.1

Two mechanical API migrations (vanilla/Fabric changes, unrelated to Connector):

1. Toolchain: `minecraft_version=1.21.1`, matching yarn + Fabric API, Java 21
   (`options.release = 21`, toolchain 21, mixin `compatibilityLevel JAVA_21`,
   `"java": ">=21"` and `"minecraft": "~1.21.1"` in fabric.mod.json).
2. `new Identifier(ns, path)` → `Identifier.of(ns, path)`. All identifiers live in one file
   (`network/SyncPackets.java`), so this is a single-file change.
3. Networking moved to typed payloads in 1.20.5+: each channel in `SyncPackets` becomes a
   `CustomPayload` record with a `PacketCodec`, registered via `PayloadTypeRegistry.playS2C()/
   playC2S()`; `registerGlobalReceiver(Identifier, handler)` → `(payload type, context)` handlers,
   `send(player, id, buf)` → `send(player, payload)`, and `canSend(player, id)` →
   `canSend(player, payloadId)`. The chunking, hashing, session and command logic
   (`sync/` package: `TransferSession`, `HashCache`, `SyncPaths`, `SyncExecutors`) is fully
   network-API-agnostic and stays untouched — the payload records carry the same fields the
   `PacketByteBuf`s carry today (keep chunks ≤ 28 KB; the C2S packet size limit is unchanged).

## The actual prerequisite

The addon compiles against BBS FS classes (`BBSMod`, `BBSModClient`, `ModelManager`,
`TextureManager`, `BBSResources`, `FormCategories`, `Link`, `UIUtilityOverlayPanel`), so
**BBS FS must be ported to the target version first** and its jar dropped into `libs/`. As long
as those public APIs keep their shape (they're core to BBS FS), the recipes above are the only
changes on this side. BBS FS itself is the riskier mod under Connector (it ships mixins, an
access widener and Sodium/Iris hooks) — test it under Connector before layering this addon on
top.
