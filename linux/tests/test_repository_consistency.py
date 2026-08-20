import re
from pathlib import Path

import yaml

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = (
    REPOSITORY_ROOT / "README.md",
    REPOSITORY_ROOT / "README_EN.md",
    REPOSITORY_ROOT / "linux" / "README.md",
    REPOSITORY_ROOT / "linux" / "README_EN.md",
)


def test_linux_names_links_fences_and_yaml_remain_consistent() -> None:
    legacy_name = "vp" + "s"
    for document in DOCUMENTS:
        content = document.read_text(encoding="utf-8")
        assert legacy_name not in content.lower()
        assert content.count("```") % 2 == 0

        for match in re.finditer(r"!?\[[^\]]*\]\(([^)]+)\)", content):
            destination = match.group(1).split("#", 1)[0].split("?", 1)[0]
            if not destination or "://" in destination or destination.startswith("mailto:"):
                continue
            target = (document.parent / destination).resolve()
            assert target.exists(), f"{document}: missing local link {destination}"

    for yaml_file in (
        REPOSITORY_ROOT / ".github" / "workflows" / "linux-service.yml",
        REPOSITORY_ROOT / "linux" / "docker-compose.yml",
    ):
        payload = yaml.safe_load(yaml_file.read_text(encoding="utf-8"))
        assert isinstance(payload, dict)
        assert payload
