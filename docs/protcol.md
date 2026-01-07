# CrossServerRTP — Messaging Protocol

This document defines the binary protocol used between the Velocity and
Paper plugins via Minecraft plugin messaging.

All protocol-related code lives in `shared/protocol`.

---

## Channel

The plugin messaging channel is defined in:

'Channel.java'


Conceptually:
- Namespace: `crossrtp`
- Channel: `main`

Both Velocity and Paper **must** use this channel definition.
No hardcoded strings are allowed elsewhere.

---

## Transport

- Transport: Minecraft Plugin Messaging
- Direction: Velocity → Paper
- Reliability: best-effort (sufficient for RTP use case)

Messages are sent only after the player is connected to the target backend.

---

## Message framing

All messages use a binary format encoded by `Codec`.

### General layout

+---------+--------------+-------------------+
| Field | Size | Description |
+---------+--------------+-------------------+
| version | 1 byte | Protocol version |
| type | 1 byte | Message type |
| payload | variable | Message data |
+---------+--------------+-------------------+


---

## Protocol version

Current version: **1**

- Version byte allows future expansion
- Backends should reject messages with unsupported versions

---

## Message types

Defined in `MessageType.java`.

### `RTP_REQUEST` (value: 1)

Requests a backend server to perform a random teleport for a player.

---

## RTP_REQUEST payload

+----------------+--------------+--------------------------+
| Field | Size | Description |
+----------------+--------------+--------------------------+
| uuid_msb | 8 bytes | Player UUID (MSB) |
| uuid_lsb | 8 bytes | Player UUID (LSB) |
| world_type | 1 byte | Target world type |
+----------------+--------------+--------------------------+


### WorldType values

Defined in `WorldType.java`:

| Value | Name       |
|------:|------------|
| 0     | OVERWORLD  |
| 1     | NETHER     |
| 2     | END        |

---

## Encoding rules

- All values are encoded in **big-endian** order
- UUID is split into two longs
- World type is a single byte ordinal
- No strings are transmitted in the protocol

---

## Decoding rules

On the backend:
1. Read and validate protocol version
2. Read message type
3. Decode payload based on message type
4. Reject malformed or unknown messages safely

Unknown message types should be ignored, not crash the server.

---

## Example (conceptual)

RTP request for a player in overworld:

01 01 [UUID_MSB (8 bytes)] [UUID_LSB (8 bytes)] 00


Where:
- `01` = protocol version
- `01` = RTP_REQUEST
- `00` = OVERWORLD

---

## Extending the protocol

New features should:
- Add a new `MessageType`
- Append payload fields (never reorder existing ones)
- Maintain backward compatibility where possible

Breaking changes require:
- New protocol version
- Coordinated Velocity + Paper update

---

## Security considerations

- Only the proxy should send RTP requests
- Backend should never trust player-provided data directly
- World type is validated against backend config
- Backend controls all teleport logic and safety checks

---

## Non-goals

The protocol intentionally does **not**:
- Support arbitrary coordinates
- Support player-provided world names
- Support teleport chaining or scripting

All authority resides on the backend server.
