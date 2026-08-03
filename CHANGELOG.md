# Changelog

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
