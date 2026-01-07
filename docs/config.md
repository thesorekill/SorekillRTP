# CrossServerRTP — Configuration Guide

This project consists of **two plugins**:

- **Velocity plugin** (`velocity/`): provides `/rtp` on the proxy and routes requests to the correct backend server.
- **Paper/Folia plugin** (`paper/`): performs the actual random teleport on the backend and enforces the death → overworld RTP rule.

> You do **not** install the `shared/*` library jars on your servers. They are used for building only.

---

## Velocity Configuration

**File:** `plugins/crossrtp/config.yml` (on the Velocity proxy)

This config controls:
- Which servers are considered “blocked origins” (e.g., hub)
- The default server to RTP on when a player runs `/rtp` from a blocked origin

### Example

```yml
# Servers where RTP should NOT be executed.
# If a player runs /rtp while on one of these servers, they will be routed to default_rtp_server.
blocked_origin_servers:
  - hub
  - lobby
  - auth

# The backend server to use when RTP is requested from a blocked origin server.
default_rtp_server: survival

messages:
  not_on_server: "&cYou must be on a server to use /rtp."
  no_such_server: "&cThat RTP server does not exist."
  failed_connect: "&cCould not connect you to the RTP server."
  sending: "&aTeleporting..."
