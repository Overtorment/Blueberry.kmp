# Vendor click confirmation

Date: 2026-08-17  
Status: approved (conversation)

## Goal

Give a visual check that `:shared` can call all four vendor KMP libraries. The existing **Click me!** button shows that check.

## Non-goals

- New screens, navigation, or theming
- Network, sync, or wallet work
- Change vendor library APIs
- Change the button label

## Behaviour

**Click me!** still toggles the same panel.

When the panel is open:

- Keep the Compose Multiplatform logo.
- Do not show `Compose: $greeting`.
- Show four lines, in this order, from live library calls:

  1. `headers: checkpoint 665280`
  2. `bip324: mainnet port 8333`
  3. `bip157: NODE_COMPACT_FILTERS 64`
  4. `bip158: hex 00 size 1`

The numbers come from the same APIs as `VendorLibrariesTest`:

| Line | Source |
| --- | --- |
| 665280 | `MAINNET_HEADER_CONSENSUS.checkpoint.height` |
| 8333 | `Networks.mainnet.defaultPort` |
| 64 | `NODE_COMPACT_FILTERS` |
| 1 | `hexToBytes("00").size` |

If a call throws, that line shows `name: error` plus the exception message. The other lines still show.

## Units

**`vendorLibraryStatus(): List<String>`** in `:shared` commonMain.

- One function. No UI.
- Returns the four lines above.
- The Compose `App` calls this function and draws each string as `Text`.

## Testing

A commonTest checks the four exact strings. Reuse or extend `VendorLibrariesTest`. Do not mock the libraries.

## Out of scope for this change

Desktop and iOS use the same `App()`. They get the same panel. No extra platform UI.
