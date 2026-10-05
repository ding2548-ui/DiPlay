# Wireless startup diagnostics

When a wireless connection stalls, reproduce it once and export DiPlay's diagnostic report while it is still connecting. Startup observations are emitted immediately after Bonjour starts, every ten seconds, and at session activation or teardown. They observe the connection without changing its address family, handshake, timeouts or retries.

The report includes:

| Field or event | Meaning |
| --- | --- |
| `authenticated`, `wifiConfigs`, `startRequests` | Completed Bluetooth authentication and sent Wi-Fi/start-session messages. Sending does not prove the iPhone accepted the configuration. |
| `ipv4Usable`, `ipv6LinkLocal`, `ipv6Scoped` | Addresses currently available on the hotspot interface; address literals are omitted. |
| `wireless endpoint` | Address count, chosen family, actual listener port, channel and security sent in the wireless start request. |
| `p2pGroup`, `sameGroup`, `reportedP2pClients` | Android's current P2P group and client-list observations. A callback timeout or inaccessible API is recorded explicitly. |
| `bonjourAdded`, `bonjourResolved`, `bonjourAddressMismatch` | Discovered CarPlay control services, resolved endpoints, and endpoints without an address matching the selected listener family. Zeros indicate no observed service events; they do not prove no multicast packets arrived. |
| `control probe stage` | Attempt to connect to the iPhone's control endpoint, established TCP, and sent `/connect` request. |
| `control probe failed after` | Last completed probe stage and exception class for each failed attempt. Exception messages and endpoint identities are omitted. |
| `connectProbe2xx`, `lastProbe` | Successful HTTP response count and latest probe outcome. |
| `airplay TCP accepted`, `tcpAccepted` | Incoming TCP reached DiPlay's AirPlay listener. This alone does not prove CarPlay negotiation succeeded or identify the peer as the selected iPhone. |
| `airplay control request/response` | Fixed method/route category, byte counts and response status for protocol negotiation. Payloads, headers, query strings and unknown path values are omitted. Frequent feedback/command traffic is excluded. |
| `iap2 availability` | Decoded wired/wireless/theme availability flags, without transport identifiers. Malformed metadata is logged without changing the existing reply behavior. |
| `sessionActive`, `waitingFor` | Whether AirPlay established a session and the next startup milestone still missing. |

## Interpreting an incomplete connection

- Authentication and start messages, with zero Bonjour resolution and zero AirPlay TCP: inspect Wi-Fi association, multicast discovery and selected address-family reachability. These logs do not distinguish those causes conclusively.
- Resolved control endpoint, followed by `CONNECTING` failure: inspect TCP reachability and source-interface/address-family binding.
- `REQUEST_SENT`, followed by a response timeout: TCP worked, but the control endpoint did not return a usable response in time.
- Incoming AirPlay TCP, followed by an authentication or SETUP error: use the safe request/response milestones to identify the failing protocol exchange.

After a requested Bluetooth handoff, the 45-second watchdog requires a rendered
video frame before preserving a session whose tunneled iAP2 channel never became
ready. Session establishment alone can also occur with a black screen and does
not prevent timeout recovery. A proven video fallback releases the Bluetooth
bootstrap and reports `STEP handoff/fallback`, explicitly recording that tunneled
iAP2 is unavailable; `STEP handoff/complete` remains reserved for the normal
tunnel-ready path. The fallback does not confirm the connection in saved history
without the existing authenticated-tunnel proof.

## Association limits

Wireless CarPlay's local Wi-Fi transport can coexist with cellular internet. A missing Wi-Fi indicator/checkmark is not proof of failed association, and manually joining the hotspot is not required for normal CarPlay.

The public Android P2P client list may omit legacy Wi-Fi stations. Therefore the report explicitly keeps `association=unknown` and `legacyClients=not_exposed`, even when the list is empty. Manual and local-only hotspots report `association=not_exposed`. Conclusive association or raw multicast diagnosis may still need device-side AP diagnostics or a packet capture supplied separately; this logger does not claim to capture packets.

All new report events use counts, fixed categories and exception classes. The existing credential/payload redaction remains enabled.
