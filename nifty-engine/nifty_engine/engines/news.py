"""News and event engine, kept separate from the technical model.

News arrives as scored items (from a feed/NLP service or entered manually).
Its contribution is a bounded, time-decaying impact in -1..+1 that the
fusion step adds as a capped log-odds shift, so a single headline can never
override market structure. Scheduled events (RBI, Fed, CPI, Budget) block
new trades inside their windows.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime, timedelta

from ..models import DirectionForecast


@dataclass
class NewsItem:
    headline: str
    source: str
    published_at: datetime
    direction: float        # -1 (bearish for NIFTY) .. +1 (bullish)
    severity: float         # 0..1 expected magnitude of market impact
    relevance: float        # 0..1 how directly it concerns Indian equities
    reliability: float      # 0..1 source trust


@dataclass
class EventWindow:
    name: str
    at: datetime
    block_before_min: int = 30
    block_after_min: int = 30

    def active(self, now: datetime) -> bool:
        return self.at - timedelta(minutes=self.block_before_min) <= now <= self.at + timedelta(minutes=self.block_after_min)


class NewsEngine:
    def __init__(self, half_life_min: float = 45.0, max_age_min: float = 360.0):
        self.half_life_min = half_life_min
        self.max_age_min = max_age_min
        self.items: list[NewsItem] = []
        self.events: list[EventWindow] = []

    def add(self, item: NewsItem) -> None:
        self.items.append(item)

    def add_event(self, event: EventWindow) -> None:
        self.events.append(event)

    def impact(self, now: datetime) -> float:
        total = 0.0
        for it in self.items:
            age = (now - it.published_at).total_seconds() / 60.0
            if age < 0 or age > self.max_age_min:
                continue
            decay = 0.5 ** (age / self.half_life_min)
            total += it.direction * it.severity * it.relevance * it.reliability * decay
        return math.tanh(total)

    def active_events(self, now: datetime) -> list[EventWindow]:
        return [e for e in self.events if e.active(now)]

    def prune(self, now: datetime) -> None:
        cutoff = now - timedelta(minutes=self.max_age_min)
        self.items = [i for i in self.items if i.published_at >= cutoff]
        self.events = [e for e in self.events if e.at + timedelta(minutes=e.block_after_min) >= now]


def fuse_news(forecast: DirectionForecast, impact: float, max_shift: float = 0.35) -> DirectionForecast:
    """Shift UP vs DOWN log-odds by at most `max_shift`, leaving the SIDEWAYS mass unchanged."""
    if abs(impact) < 1e-6:
        return forecast
    up, down = max(forecast.up, 1e-6), max(forecast.down, 1e-6)
    directional = up + down
    shift = max(-max_shift, min(max_shift, impact * max_shift))
    logit = math.log(up / down) + 2 * shift
    new_up = directional / (1 + math.exp(-logit))
    return DirectionForecast(forecast.horizon_minutes, new_up, forecast.sideways, directional - new_up, forecast.calibrated)
