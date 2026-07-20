# BBS Synchronized

A BBS (FS) addon that keeps custom models synchronized between a server and every player on it.

## Features

- **Automatic distribution on join** — when a player joins, the server sends a manifest of all its
  model files. The client compares sizes and SHA-1 hashes against its local files and downloads
  **only files it's missing** — a local model that differs from the server's copy is never
  rewritten automatically. Then it hot-reloads models and textures. No packs, no restarts.
- **`/bbs model download`** — full, server-authoritative sync on demand: fetches new models *and*
  updates local files whose contents differ from the server's copies.
- **`/bbs model upload`** — players can push their *new* models (ones the server doesn't have yet)
  to the server. Everyone is then told they can run `/bbs model download` to receive them.
- **`/bbs model upload --force`** — also resends files that already exist in the server store with
  different contents, overwriting the store's copies (for updating models you've changed).
- **Asynchronous transfers** — hashing, disk IO and streaming all run off the game threads through
  a fully asynchronous pipeline. Uploads and downloads are tracked per file with size + SHA-1
  verification, written to temp files and moved into place atomically, and recover gracefully:
  stalled or abandoned transfers time out and are cleaned up, even with large files.


> **Note:** the automatic join sync is additive only — your local files are never rewritten when
> joining. Running `/bbs model download` explicitly is what updates local files that differ from
> the server's copies.


## Building

1. Build BBS (or grab its jar) and drop it into the `libs/` folder:

   ```
   git clone https://github.com/Wemppy4/bbs-fs
   cd bbs-fs
   gradlew build          # produces build/libs/bbs-2.3.1-1.20.4.jar
   ```

2. Build the addon:

   ```
   gradlew build          # produces build/libs/bbs_synchronized-1.0.0-1.20.4.jar
   ```

## NeoForge (Sinytra Connector)

The addon is written to be Connector-clean so you can use this addon alongside Sinytra Connector in order to use it on Neoforge or Forge.

## Installing

Put the jar (plus BBS and Fabric API) into the `mods` folder of **both** the server and every
client. Distributed models live in the server's `config/bbs/sync_models` folder (its own store,
separate from any player's BBS assets) — anything you drop there is distributed automatically.
On dedicated servers, files from the old `config/bbs/assets/models` location are copied into the
store once, automatically. Because the store is separate, the **host** of the server syncs
exactly like every other player: uploads land in the store, and the host receives them with
`/bbs model download` too.

| Command | Who    | What |
| --- |--------| --- |
| `/bbs model download` | Anyone | Fetch models added since you joined |
| `/bbs model upload` | Anyone | Upload your new models to the server (never overwrites existing ones) |
| `/bbs model upload --force` | Anyone | Also overwrite server copies of models you've changed |

The same actions are also available as buttons in BBS's utility overlay (default keybind **F6**),
under the "Server Sync" section — they run the exact same commands.
