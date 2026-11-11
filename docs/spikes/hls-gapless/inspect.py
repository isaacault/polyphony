"""Print gapless facts (edit lists, Opus pre-skip, sample totals) for each HLS rendition in out/.

Usage: python3 -I inspect.py out
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from inspect_lib import main  # noqa: E402

main(sys.argv[1])
