"""Check local Markdown links and the editable diagrams behind the README."""

from pathlib import Path
import re
import struct
import subprocess
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET


def heading_ids(markdown):
    headings = re.findall(r"^#{1,6}\s+(.+)$", markdown.read_text(), re.MULTILINE)
    return {re.sub(r"[^\w -]", "", text.lower()).replace(" ", "-") for text in headings}


def check_links(markdown):
    for destination in re.findall(r"\]\(([^)\s]+)\)", markdown.read_text()):
        url = urlsplit(destination)
        if url.scheme or url.netloc:
            continue
        target = markdown.parent / unquote(url.path) if url.path else markdown
        if not target.exists():
            raise ValueError(f"{markdown}: missing {destination}")
        if url.fragment and target.suffix == ".md" and unquote(url.fragment) not in heading_ids(target):
            raise ValueError(f"{markdown}: unknown heading in {destination}")


def check_diagram(html):
    source = html.read_text()
    svg = ET.fromstring(source[source.index("<svg"):source.index("</svg>") + len("</svg>")])
    _, _, width, height = map(float, svg.attrib["viewBox"].split())
    with html.with_suffix(".png").open("rb") as png:
        header = png.read(24)
    if not header.startswith(b"\x89PNG\r\n\x1a\n") or header[12:16] != b"IHDR":
        raise ValueError(f"{html}: invalid PNG")
    if struct.unpack(">II", header[16:24]) != (2 * width, 2 * height):
        raise ValueError(f"{html}: PNG dimensions must match the 2x SVG export")


files = subprocess.check_output(["git", "ls-files", "-z", "*.md"]).decode().split("\0")
for name in filter(None, files):
    check_links(Path(name))
for diagram in Path("docs/diagrams").glob("*.html"):
    check_diagram(diagram)
