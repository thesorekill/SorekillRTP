# CrossServerRTP — Developer Notes

This document explains internal design decisions, assumptions, and rules
for contributors and future maintainers.

This project is intentionally split into **two plugins** plus shared libraries.

---

## High-level architecture

### Components

- **Velocity plugin**
  - Owns the `/rtp` command
  - Decides *where* an RTP should occur
  - Handles blocked-origin logic (e.g., hub → survival)
  - Sends RTP requests to backend servers via plugin messaging

- **Paper/Folia plugin**
  - Owns world access and teleport logic
  - Finds safe random locations
  - Performs teleports
  - Enforces death → overworld RTP behavior

- **Shared libraries**
  - `shared/protocol`: binary message format and codec
  - `shared/api`: optional future-facing API for other plugins

---

## Why this is split across two plugins

Minecraft server software enforces a hard boundary:

- **Only the proxy** can move players between servers
- **Only backend servers** can safely inspect worlds and blocks

Because of this:
- Cross-server routing *must* happen on Velocity
- Safe random teleport *must* happen on Paper/Folia

Trying to combine these into one plugin would either:
- break cross-server RTP, or
- require unsafe hacks (BungeeCord-style reflection or fake teleports)

---

## Command philosophy

### `/rtp` (no arguments)

- Always available to players (permission-gated)
- If executed on an allowed server:
  - RTP occurs on that same server’s overworld
- If executed on a blocked server (hub/lobby):
  - Player is routed to a configured default server
  - RTP occurs there instead

This ensures:
- `/rtp` works everywhere
- Teleports never occur on hub/lobby servers

---

## Death handling rules

Death handling is intentionally **backend-only**.

When a player dies on a backend server:
1. Respawn is forced to the overworld
2. An RTP is executed one tick after respawn
3. No proxy involvement occurs

This guarantees:
> “If they die, they should always RTP in the overworld of the server they are on.”

This logic does **not** move players between servers.

---

## Messaging transport

### Plugin messaging (current approach)

- Uses Minecraft’s built-in plugin messaging channels
- No external dependencies (Redis, databases, etc.)
- Messages are sent:
  - Velocity → specific backend server

This is sufficient because:
- RTP requests are small
- Ordering is simple
- Requests are transient (no persistence required)

If future reliability or queuing is needed, this can be replaced with Redis
without changing the public API.

---

## Folia compatibility

All backend logic must respect Folia constraints:
- No synchronous cross-thread world access
- No direct Bukkit scheduler usage

The `Sched` abstraction exists to:
- Hide Paper vs Folia scheduling differences
- Centralize thread-safety logic
- Keep teleport logic readable

**Rule:**  
> Never call `Bukkit.getScheduler()` directly outside `Sched`.

---

## Shared protocol rules

- All plugin messages must:
  - Go through `shared/protocol/Codec`
  - Use the channel defined in `Channel.java`
- No hardcoded channel names or byte layouts elsewhere
- Versioned messages allow backward-compatible expansion

---

## Versioning strategy

- Protocol messages are versioned
- Backends should ignore unknown message types
- New message types must be additive

Breaking protocol changes require:
- Version bump
- Coordinated Velocity + Paper release

---

## Common pitfalls

### Missing shared classes at runtime
If you see:
*NoClassDefFoundError: net.chumbucket.crossrtp.protocol.*


The plugin jar was not shaded correctly.
The `shared/protocol` classes must be included in:
- Velocity plugin jar
- Paper plugin jar

Do **not** install library jars separately.

---

## Development workflow

Recommended workflow:
1. Modify `shared/protocol` first
2. Update Velocity sender logic
3. Update Paper receiver logic
4. Test locally with:
   - Velocity proxy
   - At least one Paper backend

Avoid testing protocol changes against mismatched plugin versions.

---

## Design goals (non-negotiable)

- No RTP on hub servers
- No unsafe teleports
- No cross-thread world access
- No duplication of protocol logic
- Clear separation of concerns
