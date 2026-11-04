"""Small in-process TTL + LRU cache (Phase 1: no Redis). Only ever holds non-customer data (embeddings of query text,
intent classifications of normalised text, public product knowledge answers)."""
from __future__ import annotations

import time
from collections import OrderedDict
from typing import Generic, Hashable, TypeVar

V = TypeVar("V")


class TTLCache(Generic[V]):
    def __init__(self, ttl_s: float, maxsize: int = 2048):
        self._ttl = ttl_s
        self._max = maxsize
        self._data: OrderedDict[Hashable, tuple[float, V]] = OrderedDict()
        self.hits = 0
        self.misses = 0

    def get(self, key: Hashable) -> V | None:
        entry = self._data.get(key)
        if entry is None or entry[0] < time.monotonic():
            self._data.pop(key, None)
            self.misses += 1
            return None
        self._data.move_to_end(key)
        self.hits += 1
        return entry[1]

    def set(self, key: Hashable, value: V) -> None:
        if self._ttl <= 0:
            return
        self._data[key] = (time.monotonic() + self._ttl, value)
        self._data.move_to_end(key)
        while len(self._data) > self._max:
            self._data.popitem(last=False)

    def __len__(self) -> int:
        return len(self._data)
