# CrossServerRTP Shared Modules

This directory contains shared libraries used by the CrossServerRTP plugins.

These modules are used at build-time only and are NOT installed on servers.

---

## Modules

### shared/protocol

Defines the messaging protocol between Velocity and Paper:
- Channel identifiers
- Message types
- Binary codec
- World type definitions

This ensures both plugins agree on packet structure and versioning.

---

### shared/api

Optional API module for future integrations.

This module exists so other plugins can interact with CrossServerRTP
without needing to understand the internal messaging protocol.

Currently unused by default.

---

## Important Notes

- Shared modules are shaded into the Velocity and Paper plugin jars
- Do NOT install shared jars into server plugin directories
- Changes to protocol require coordinated plugin releases

---

## Development Rules

- Never hardcode protocol bytes or channel names outside shared/protocol
- All protocol changes must be versioned
- Library modules must remain lightweight and dependency-free
