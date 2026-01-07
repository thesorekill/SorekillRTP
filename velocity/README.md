# CrossServerRTP (Velocity)

This module is the proxy component of CrossServerRTP.

It is responsible for:
- Providing the /rtp command
- Determining where RTP should occur
- Redirecting players from blocked servers (hub/lobby)
- Sending RTP requests to backend servers

This plugin does NOT perform teleport safety checks.

---

## Installation

1. Build the project from the repository root:
   mvn -f parent-pom.xml clean package

2. Copy the generated jar from:
   velocity/target/

   Into your Velocity proxy:
   plugins/

3. Restart Velocity.

---

## Configuration

Config file location:
plugins/crossrtp/config.yml

Full configuration documentation:
docs/config.md

---

## Command Behavior

### /rtp

When a player runs /rtp:

- If the player is on an allowed server:
  RTP occurs on that server’s overworld

- If the player is on a blocked server (hub/lobby):
  The player is connected to the default RTP server
  RTP then occurs on that server’s overworld

Teleportation never occurs on blocked servers.

---

## Notes

- This plugin sends RTP requests using plugin messaging
- It requires the Paper plugin to be installed on target servers
- Shared protocol classes are shaded into this jar

---

## Troubleshooting

If /rtp connects players but does not teleport:
- Confirm the Paper plugin is installed on the destination server
- Confirm plugin messaging channel is registered
- Verify the backend server name matches Velocity config
