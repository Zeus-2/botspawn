# Botspawn

Server-side Fabric mod for Minecraft 26.2 which lets donors spawn their own Carpet fake players using LuckPerms/Fabric permission checks.

## Requirements

- Minecraft 26.2+ (built against 26.2; later releases need testing)
- Java 25
- Fabric Loader 0.19.3+
- Fabric API
- Carpet 26.2
- LuckPerms for Fabric to enable `/botspawn` and `/botkill`; `/botlist` remains available without it.

`fabric-permissions-api` 0.7.0 is bundled into the Botspawn jar.

Carpet's `allowSpawningOfflinePlayers` rule must remain enabled for generated names such as `ZeusBot1`. Carpet 26.2 currently defaults this rule to `true`.

## Commands

### List bots

```text
/botlist
```

Anyone, including players without BotSpawn permissions, can view all currently
connected Carpet bots. Each entry shows the bot name, gamemode and its saved
owner, or `unassigned` for bots created outside BotSpawn.

### Kill a bot

```text
/botkill <name>
```

For example, `/botkill ZeusBot2` selects that specific bot. Players can only
kill bots owned by them. Give
`avience.botspawn.killothers` to staff who should be able to kill any connected
Carpet fake player, including bots restored after a restart. Everyone using
`/botkill` needs `avience.botspawn.kill`.

Botkill processes a normal death; Carpet disconnects the bot automatically.
Items follow normal server death rules: they drop with `keepInventory` off, but
are retained with `keepInventory` on. Lava, the void and other mods can also
affect drops.

Ownership is saved to `botspawn-owners.txt` in the server's world folder and recovered after restart
when the same name and UUID reconnect as a Carpet fake player. If Carpet does
not restore a bot, it remains listed in the ownership file for possible later
reconnection and does not occupy a bot slot. BotSpawn does not automatically
spawn bots after a restart.

### Donator / normal player

```text
/botspawn <survival|spectator>
```

The bot name is automatically allocated from the player's exact IGN casing:

```text
Zeus -> ZeusBot1 -> ZeusBot2 -> ZeusBot3 ...
```

The lowest free numbered slot is used. A generated name which belongs to a whitelisted player is skipped.

### Staff

```text
/botspawn <survival|spectator> <name>
```

The custom-name form requires `avience.botspawn.staff`.

Staff custom names:

- are still restricted to valid Java usernames (`A-Z`, `a-z`, `0-9`, `_`, 3-16 characters);
- may deliberately use a name found on the Minecraft whitelist;
- may not duplicate a player/fake player which is currently connected or currently spawning.

If staff spawn a whitelisted identity, Botspawn intentionally does **not** add the LuckPerms `bot` group to that identity, because doing so would permanently modify the real player's LuckPerms account.

## Permissions

To kill bots you own:

```text
avience.botspawn.kill
```

Every user needs:

```text
avience.botspawn.use
```

They also need one limit permission:

```text
avience.botspawn.limit.1
avience.botspawn.limit.2
avience.botspawn.limit.3
avience.botspawn.limit.5
avience.botspawn.limit.10
avience.botspawn.limit.unlimited
```

If more than one is granted, the highest limit wins.

Staff custom naming:

```text
avience.botspawn.staff
```

Staff override for `/botkill <name>`:

```text
avience.botspawn.killothers
```

This override is intentionally outside `avience.botspawn.kill.*`, because
permission providers may treat `avience.botspawn.kill` as a wildcard for
nodes beneath it. The old `avience.botspawn.kill.others` and
`avience.botspawn.admin.kill` nodes are unused.

Staff are still subject to their bot-limit permission. Give staff `avience.botspawn.limit.unlimited` if they should have no cap.

## LuckPerms examples

Two-bot donor group:

```text
/lp group donor permission set avience.botspawn.use true
/lp group donor permission set avience.botspawn.limit.2 true
```

Staff group:

```text
/lp group staff permission set avience.botspawn.use true
/lp group staff permission set avience.botspawn.staff true
/lp group staff permission set avience.botspawn.limit.unlimited true
```

Permission checks are made through Fabric Permissions API rather than by executing `/lp` commands. They should therefore appear in LuckPerms verbose output.

## Bot LuckPerms group

After a non-whitelisted fake player with an offline UUID finishes joining, Botspawn adds its UUID to the LuckPerms group:

```text
bot
```

Create the `bot` group in LuckPerms first (`/lp creategroup bot`). Botspawn
checks whether the bot already belongs to it and only adds a missing
membership. It logs an error if the group is absent. Botspawn does not modify
real-player UUIDs or whitelisted accounts; staff-created bots with such
identities are excluded. This is applied using the LuckPerms API; there is no
spawn/kill/respawn cycle.

## Build

From the project directory with JDK 25 selected:

```text
./gradlew build
```

On Windows:

```text
gradlew.bat build
```

The jar is written to `build/libs/`.

The current jar is `botspawn-1.0.8.jar`. Startup logs print the loaded version
and both kill permission names. Replace the old jar and restart after building.

`build` runs dependency-free registry and ownership-file checks. These cover
reused names, repeated death requests, disconnect cleanup, restart recovery,
removals and corrupt-file rejection. An in-game test with Fabric, Carpet and
LuckPerms is still needed.

## Changes in 1.0.8

- Adds permission-free `/botlist` for all connected Carpet bots and owners.
- Restricts both spawn forms to survival or spectator.
- Writes a world-local ownership file on completed spawn, `/botkill` and normal
  disconnection; records are replaced atomically so a write cannot leave a
  partially written file. Reloads matching names and UUIDs after restart.
- Preserves damaged ownership files instead of silently overwriting them.

## Changes in 1.0.7

- Ties ownership to the exact connected entity, preventing an old name record
  from granting control over a replacement bot.
- Rejects repeated death requests while Carpet is disconnecting the bot and
  retains its limit slot until it leaves.
- Checks the prepared UUID before assigning ownership after asynchronous spawn.
- Handles non-player command sources and synchronous LuckPerms load failures.
- Loads the bot group from LuckPerms storage; distinguishes permanent global
  membership from temporary/contextual assignments and reports save failures
  to the owner.
- Prints the loaded version and kill permission names at startup.

## Changes in 1.0.6

- Checks for an existing `bot` group membership before assigning it to a
  newly spawned bot and logs when the group does not exist.

## Changes in 1.0.5

- Renames the staff override to `avience.botspawn.killothers`.

## Changes in 1.0.4

- Requires `avience.botspawn.kill` for `/botkill` and moves the staff override
  to `avience.botspawn.admin.kill` so the ordinary kill permission cannot grant
  the override through parent permission inheritance.

## Changes in 1.0.3

- Lets Carpet handle inventory and disconnect on death; removes the forced
  inventory drop from `/botkill`.

## Changes in 1.0.2

- Adds `/botkill <name>` for bots owned by the caller.
- Adds a separate staff permission for killing other or restored Carpet bots.
- Processes the fake player's death; Carpet disconnects it.

## Changes in 1.0.1

- Declares Loader 0.19.3+ and Minecraft 26.2+; later Minecraft/Carpet versions are not guaranteed compatible.
- LuckPerms is optional at Loader resolution, matching the initializer's disable-and-log behavior.
- Matches Carpet's profile UUID lookup before preloading LuckPerms data.
- Prevents expired async callbacks from acting on replacement requests.
- Rechecks permissions, limits, and whitelist immediately before Carpet creation.
- Keeps pending slots reserved while Carpet is still loading a profile.
- Fixes double-counted bots in success messages and clears runtime state on server stop/start.
- Skips occupied numbered names independently of the owner's simultaneous bot limit.
- Gives a specific error for owner names too long to append Bot1.
- Does not attach the bot group to registered online UUIDs or whitelisted identities.

An ongoing Carpet profile lookup retains its bot slot until it finishes or the
server restarts. If saving the ownership file fails, BotSpawn logs the error
and the current session's ownership still works; it cannot promise recovery
after a crash until the filesystem problem is fixed.

Validation: Java syntax parsing passed and the registry and file storage passed
22 regression checks. LuckPerms method signatures
were checked against the supplied jar. Full Gradle compilation could not run
because downloading Gradle was blocked; this environment also lacks Java 25.
Fabric/Carpet integration and item drops need an in-game test.
