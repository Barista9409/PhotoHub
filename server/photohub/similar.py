"""F2 similar detection: group photos whose pHash is within `threshold` bits.

Candidate search uses the pigeonhole principle: split the 64-bit hash into threshold+1
bands; two hashes within `threshold` bits must agree exactly on at least one band.
That keeps 100k photos to a few seconds instead of 10^10 comparisons.

Groups are only *suggestions* (spec: never merged automatically). Resolving a group moves
the photos not kept into trash/ (spec F6 management screen) - never deletes them.
"""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from sqlalchemy import select

from .config import Settings
from .db import Database, Media, SimilarGroup, SimilarMember, User, log_event, set_location, touch_media, utcnow
from .fileops import move_verified, unique_in
from .hashing import hamming
from .xmp import sidecar_path


def _bands(h: int, n: int) -> list[tuple[int, int]]:
    width = 64 // n
    out = []
    for i in range(n):
        shift = i * width
        w = width if i < n - 1 else 64 - shift
        out.append((i, (h >> shift) & ((1 << w) - 1)))
    return out


def find_groups(items: list[tuple[int, str]], threshold: int) -> list[set[int]]:
    n = min(threshold + 1, 16)
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)
    ints = {mid: int(ph, 16) for mid, ph in items}
    for mid, h in ints.items():
        for b in _bands(h, n):
            buckets[b].append(mid)
    parent = {mid: mid for mid in ints}

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for ids in buckets.values():
        if len(ids) < 2 or len(ids) > 500:  # huge bucket = flat images; skip as noise
            continue
        for i, a in enumerate(ids):
            for b in ids[i + 1:]:
                if find(a) != find(b) and bin(ints[a] ^ ints[b]).count("1") <= threshold:
                    parent[find(a)] = find(b)
    groups: dict[int, set[int]] = defaultdict(set)
    for mid in ints:
        groups[find(mid)].add(mid)
    return [g for g in groups.values() if len(g) > 1]


def refresh(db: Database, settings: Settings) -> int:
    """Create open groups for new similar sets. Media already in a group are left alone."""
    created = 0
    with db.session() as s:
        # groups never mix spaces: shared photos with shared, each person's with their own
        by_owner: dict[int | None, list[tuple[int, str]]] = defaultdict(list)
        for m in s.execute(select(Media).where(Media.kind == "photo", Media.status == "active",
                                               Media.phash.is_not(None))).scalars():
            by_owner[m.owner_id].append((m.id, m.phash))
        grouped = set(s.execute(select(SimilarMember.media_id)).scalars())
        found = [(owner, g) for owner, items in by_owner.items()
                 for g in find_groups(items, settings.similar_threshold)]
        for owner, g in found:
            if g & grouped:
                continue
            grp = SimilarGroup(status="open", owner_id=owner)
            s.add(grp)
            s.flush()
            for mid in sorted(g):
                s.add(SimilarMember(group_id=grp.id, media_id=mid, keep=None))
            created += 1
        if created:
            log_event(s, "info", "similar_refresh", f"{created} new similar groups")
    return created


class Forbidden(PermissionError):
    pass


def may_resolve(user: User | None, grp: SimilarGroup) -> bool:
    """Shared-space groups: admins only. Personal-space groups: that person only."""
    if user is None:  # CLI on the NAS
        return True
    return user.is_admin if grp.owner_id is None else grp.owner_id == user.id


def resolve(db: Database, settings: Settings, group_id: int, keep_ids: set[int], *, apply: bool,
            user: User | None = None) -> list[str]:
    """Keep `keep_ids`; move the others to trash/<date>/<space>/... (with their sidecars)."""
    actions = []
    with db.session() as s:
        grp = s.get(SimilarGroup, group_id)
        if grp is None or grp.status != "open":
            raise ValueError("group not open")
        if not may_resolve(user, grp):
            raise Forbidden("not allowed to organise this group")
        owner = s.get(User, grp.owner_id) if grp.owner_id is not None else None
        space_root = Path(owner.personal_root) if owner and owner.personal_root else settings.library
        space_name = owner.name if owner else "shared"
        members = list(s.execute(select(SimilarMember).where(SimilarMember.group_id == group_id)).scalars())
        if not keep_ids or not keep_ids <= {m.media_id for m in members}:
            raise ValueError("keep at least one photo of this group")
        day = f"{utcnow():%Y-%m-%d}"
        for mem in members:
            mem.keep = mem.media_id in keep_ids
            if mem.keep:
                continue
            m = s.get(Media, mem.media_id)
            src = Path(m.nas_path)
            try:
                rel = src.relative_to(space_root)
            except ValueError:
                rel = Path(src.name)
            dst = unique_in((settings.trash / day / space_name / rel).parent, src.name)
            actions.append(f"move {src} -> {dst}")
            if apply and src.exists():
                move_verified(src, dst, m.file_hash)
                sc = sidecar_path(src)
                if sc.exists():
                    move_verified(sc, dst.with_name(dst.name + ".xmp"))
                m.status = "trashed"
                m.nas_path = str(dst)
                set_location(s, m.id, "nas", "removed", source_ref=str(dst))
                touch_media(s, m)
                log_event(s, "info", "trash_move", f"similar group {group_id}: {src} -> {dst}")
        if apply:
            grp.status = "resolved"
        else:
            s.rollback()
    return actions
