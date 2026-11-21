"""Backwards-compatible shim: the reference model now lives in engine/model.py."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from engine.model import *  # noqa: F401,F403
