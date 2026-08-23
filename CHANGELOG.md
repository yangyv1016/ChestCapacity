# Changelog

## 1.1.5 - 2026-08-24

### Security

- Prevent concurrent viewers from withdrawing the same item from independent GUI snapshots by sharing one server-side inventory per chest page.
- Keep a shared page registered until its final viewer closes it, preserving live edits during page changes and concurrent access.

### Fixed

- Correct comparator input-side detection for direct reads and reads through one solid block.
- Use Paper's required `0`/`15` redstone event gate while keeping the actual analogue strength in the comparator block entity.
- Propagate virtual comparator changes through the comparator's directional output path so downstream redstone receives the calculated signal.

### Validation

- Verify direct comparator output on Paper 1.21.11 build 132: one full stack produces comparator output 1 and downstream redstone power 1.

## 1.1.4 - 2026-08-03

### Performance

- Run the virtual-storage I/O sweep at the configured transfer interval instead of scanning every server tick.
- Track occupied slots with a `BitSet`, avoiding full scans of large sparse warehouses.
- Cache used-slot and comparator fullness metrics and update them incrementally.
- Reuse resolved chest blocks and parsed storage locations in hot paths.
- Build one hopper-minecart source index per I/O cycle instead of running a nearby-entity query for every chest.
- Avoid unnecessary hologram and comparator neighbour refreshes for idle chests.
- Reuse a stable chest-key scan snapshot until the store topology changes.

### Reliability

- Clone GUI inventory items to keep cached storage metrics isolated from GUI mutations.
- Add unit coverage for sparse storage, stack merging, resize overflow and cached comparator state.
