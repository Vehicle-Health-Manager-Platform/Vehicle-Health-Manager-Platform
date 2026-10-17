"""Validate raw mounts before Compose can collapse duplicate target paths."""
from pathlib import Path
import re
import sys


def verify(compose: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    mounts = re.findall(
        r"^\s*-\s+(../../docs/sql/[^:\s]+):(/docker-entrypoint-initdb\.d/[^:\s]+):ro\s*$",
        compose.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    expected = {root / "docs/sql/init.sql"} | {
        path for path in (root / "docs/sql/migrations").glob("V*__*.sql")
        if not path.name.startswith("V001__")
    }
    sources, targets, versions = set(), set(), []
    for source, target in mounts:
        resolved = (root / "deploy/compose" / source).resolve()
        if resolved in sources or target in targets:
            raise ValueError(f"duplicate migration source or target: {source} -> {target}")
        if not resolved.is_file():
            raise ValueError(f"missing migration source: {source}")
        version = 1 if resolved.name == "init.sql" else int(re.match(r"V(\d+)__", resolved.name)[1])
        if not Path(target).name.startswith(f"{version:03d}-"):
            raise ValueError(f"migration version does not match target: {source} -> {target}")
        sources.add(resolved)
        targets.add(target)
        versions.append(version)
    if sources != expected or versions != list(range(1, len(expected) + 1)):
        raise ValueError("Compose must mount init.sql and every migration exactly once in version order")
    print(f"Compose migration mounts verified: {len(mounts)} unique ordered sources and targets")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "deploy/compose/compose.yml")
    except (ValueError, OSError) as error:
        sys.exit(f"Compose migration validation failed: {error}")
