# 00 — Executive Summary

**Audit Hydraulic PR #5 + Fork PackConverter — Final Report v2**
**Date**: 2026-09-07T09:22Z (last-verified)
**Auditor**: nguyenphuck2468-tech (fork `Hydraulic` + `PackConverter`)
**Scope**: Audit 4 ngày 2026-09-01 → 2026-09-04 với verification từ 3 artifact thật

## TL;DR (10 dòng)

1. **Core finding vẫn đúng 100%**: `hydraulic-fabric.jar` bundle `converter-3.4.3-SNAPSHOT` (chưa fix path-traversal) — verified bằng `unzip -p` + `grep` (4 commit Hyd force-pushed lên fork master, see `02-pr-branch-status.md` row 12).
2. **4 mod lỗi GĐ4.1 trong log 2026-09-07** [VERIFIED]: `alexsmobs, viabackwards, viafabric, viaversion` (không phải `viafabric-mc26-1`/`viafabric-mc26-2` như báo cáo cũ viết sai).
3. **Đã sửa cả 2 bug P0** (GĐ4.1 path safety + GĐ4.2 error counting) — code có sẵn trong `nguyenphuck2468-tech/PackConverter` tại `gd5/pr-texture-and-errors-rebased`, **chưa merge upstream** vì GĐ4.3 (SNAPSHOT pin) chưa xong.
4. **3 PR upstream mở** (1 supersede): PC #72 (GĐ4.1+4.2), Hyd #109 (GĐ3.7+3.9 26.2), Hyd #111 (GĐ4.5.1+4.5.2).
5. **Đã merge 3 commit GĐ4.5** (thread pool, per-mod cache, async convert) vào fork Hyd master — nhưng log 2026-09-07 KHÔNG chứa dòng "Deferred registration" (0 match), nên **claim "GĐ4.5.3 mất 84% pack" KHÔNG thể verify từ log này** (log này chạy SAU khi revert GĐ4.5.3 hoặc build khác).
6. **1 bug mới cần follow-up**: `alexsmobs` tham chiếu `special_render_type: alexsmobs:icon` + `tint_source: alexsmobs:straddleboard_base` mà `creative-api 1.13.6` không nhận → 17 item thiếu layer0 texture (creative-api compatibility lag).
7. **2 catch-block nuốt lỗi mới** (GĐ5.5): `PackPackager.java:26` + `MetadataPackModule.java:74` — đã fix với `LOGGER.debug`/`LOGGER.warn`, push nhánh `gd5/catch-block-audit` lên fork.
8. **Fork state cuối** (Hyd + PC): mỗi repo 2 branch (`master` + `gd5/final-fork-master`); 7 PR closed/cũ không xóa được qua API.
9. **Tổng effort**: 23+ commit + 16+ test mới + 3 PR upstream + 1 script đo + 2 nhánh cuối + báo cáo 5 file.
10. **Known issues chưa giải quyết**: GĐ2.3 (lossless cần 2 archive), GĐ4.3 (fabric-loom override repos), GĐ4.5.3 (cần Geyser reload event).

## Risk matrix

| # | Finding | Severity | Status |
|---|---|---|---|
| F1 | `converter-3.4.3-SNAPSHOT` path traversal in production jar | **P0** | ✅ Verified 100% — fix ready trong `gd5/pr-texture-and-errors-rebased` (PR #72) |
| F2 | CombineContext.error() not counted, "successfully" log sai | **P0** | ✅ Verified 100% — fix ready (cùng PR #72) |
| F3 | `creative-api 1.13.6` thiếu `alexsmobs:icon` special_render_type | P1 | ⏸️ Theo dõi upstream `team.unnamed:creative-api` (see F5) |
| F4 | `biomesoplenty` log spam từ `template_sign_rot_*` parent model missing | P2 (log noise) | ⏸️ Bug biomesoplenty upstream (mod không tương thích 26.2); `biomesoplenty` xuất hiện 3891 lần tổng, `Could not find parent model` 1960 dòng tổng log |
| F5 | 2 catch-block nuốt lỗi im lặng (GĐ5.5) | P2 | ✅ Fixed ở `gd5/catch-block-audit` commit `1ff5a43` |
| F6 | GĐ4.5.3 async 5s budget mất pack P0 (claim từ báo cáo cũ) | **P0 unverified** | ⚠️ Không thấy trong log 2026-09-07 (log chạy build khác); claim cần re-verify bằng log build có GĐ4.5.3 |
| F7 | GĐ2.3 lossless vs pruned chưa đo | P3 | ⏸️ Cần 2 archive |
| F8 | fabric-loom override `dependencyResolutionManagement` | P2 | ⏸️ Cần `loom { repositories { mavenLocal() } }` |

## Action items (next 7 days)

1. **Maintainer `GeyserMC`**: review + merge [PR #72 (PackConverter)](https://github.com/GeyserMC/PackConverter/pull/72) — fix P0 (path + error)
2. **Maintainer `GeyserMC`**: review + merge [PR #109 (Hydraulic)](https://github.com/GeyserMC/Hydraulic/pull/109) — cleanup 26.2
3. **Maintainer `GeyserMC`**: review + merge [PR #111 (Hydraulic)](https://github.com/GeyserMC/Hydraulic/pull/111) — perf (drop GĐ4.5.3)
4. **Operator**: nếu bị "84% pack mất" thì revert PR #111, dùng fork master pre-`25c8572`
5. **Auditor**: re-verify F6 bằng log build có GĐ4.5.3 (cần archive mới)

## Đường dẫn nhanh

- **Báo cáo chi tiết**: `01-technical-findings.md` (claims + bằng chứng + severity)
- **PR + branch state**: `02-pr-branch-status.md` (last-checked 2026-09-07T09:22Z)
- **Risk + rollback**: `03-risk-rollback.md`
- **Follow-ups + glossary**: `04-followups-glossary.md`
- **Self-check tool**: `verify_report.py` (chạy trước khi publish mọi báo cáo)
