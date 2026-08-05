# Contributing

Thanks for helping improve outcome-metrics.

## Development

- JDK 21+
- Maven 3.9+ (wrapper provided)

```bash
./mvnw -B verify
```

## Pull requests

1. Open an issue first for larger API/behavior changes.
2. Keep PRs focused; include tests for new behavior.
3. Do not add deprecated compatibility shims for removed APIs — bump the major instead.
4. Use clear commit messages (Conventional Commits welcome: `feat:`, `fix:`, `docs:`).
5. Sign off commits with a DCO trailer:

```
Signed-off-by: Your Name <you@example.com>
```

(`git commit -s`)

## Code style

- Match existing formatting and Javadoc level.
- Nullness: prefer explicit checks / JSpecify where already used.
- No RH/product-specific package names or config keys.

## Reporting security issues

See [SECURITY.md](SECURITY.md).
