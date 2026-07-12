# BBS Synchronized

A BBS (FS) addon that keeps custom models (`config/bbs/assets/models`) synchronized between a server
and every player on it.

## Features

- **Automatic distribution on join** — when a player joins, the server sends a manifest of all its
  model files. The client compares sizes and SHA-1 hashes against its local files and downloads
  only what's missing or different, then hot-reloads models and textures. No packs, no restarts.
- **`/bbs model reload`** — if new models were added after players joined (dropped into the server
  folder or uploaded by another player), anyone can pull them in with this command.
- **`/bbs model upload`** — players can push their *new* models (ones the server doesn't have yet)
  to the server. Everyone is then told they can run `/bbs model reload` to receive them. Files
  that already exist on the server are never overwritten by uploads.
- **Asynchronous transfers** — hashing, disk IO and streaming all run off the game threads through
  a fully asynchronous pipeline. Uploads and downloads are tracked per file with size + SHA-1
  verification, written to temp files and moved into place atomically, and recover gracefully:
  stalled or abandoned transfers time out and are cleaned up, even with large files.


> **Note:** the server is authoritative when downloading — if you have a local model with the same
> path as a server model but different contents, the server version will overwrite yours on
> join/reload.


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
client. Server models live in the server's `config/bbs/assets/models` folder — anything you drop
there is distributed automatically.

| Command | Who    | What |
| --- |--------| --- |
| `/bbs model reload` | Anyone | Fetch models added since you joined |
| `/bbs model upload` | Anyone | Upload your new models to the server (never overwrites existing ones) |

The same actions are also available as buttons in BBS's utility overlay (default keybind **F6**),
under the "Server Sync" section — they run the exact same commands.
