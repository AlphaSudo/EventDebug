# eventlens-spi

Stable plugin contract for EventLens v3.

## Rules

- This module is a dependency leaf.
- It must not depend on runtime modules (`eventlens-core`, source plugins, stream plugins).
- Keep only interfaces, records, enums, and compatibility helpers.

## SPI Versioning

- Adding a `default` method to an interface is backward compatible.
- Adding a new interface is backward compatible.
- Adding a required method to an interface requires an SPI major bump.
- Changing a method signature requires an SPI major bump.
- Removing a method requires an SPI major bump.

Compatibility checks are centralized in `SpiVersions`.
