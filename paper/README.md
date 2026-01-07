# CrossServerRTP (Paper / Folia)

This module is the backend component of CrossServerRTP.

It is responsible for:
- Performing safe random teleports (RTP) in the overworld
- Handling death behavior (death → respawn → RTP in overworld)
- Receiving RTP requests from the Velocity proxy via plugin messaging

This plugin does NOT handle cross-server routing.

---

## Installation

1. Build the project from the repository root:
   mvn -f parent-pom.xml clean package

2. Copy the generated jar from:
   paper/target/

   Into each backend server where RTP should function:
   plugins/

3. Restart the backend server.

---

## Configuration

Config file location:
plugins/CrossRTP/config.yml

All configuration options are documented in:
docs/config.md

---

## Runtime Behavior

### RTP Requests
The Paper plugin does not register any player commands.

Instead, it listens for RTP requests sent from the Velocity proxy.  
When an RTP request is received:

- The server overworld is selected
- A safe random location is calculated
- The player is teleported

All safety checks are enforced server-side.

---

### Death Handling

When a player dies on a backend server:

1. Respawn is forced to the overworld
2. One tick after respawn, an RTP is performed
3. The player remains on the same backend server

This guarantees that players always re-enter the overworld safely.

---

## Notes

- This plugin shades the shared protocol classes into its jar.
- Shared library jars are NOT installed on servers.
- Designed to be Paper and Folia safe.

---

## Troubleshooting

If RTP requests are received but no teleport occurs:
- Verify the overworld exists or is configured correctly
- Check that radius and attempt settings allow safe locations
- Ensure no other plugins override teleport or respawn behavior

If you see NoClassDefFoundError related to protocol classes:
- Ensure the maven-shade-plugin is correctly configured
