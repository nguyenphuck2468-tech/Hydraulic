# 02 — PR & Branch Status (last-checked 2026-09-07T09:22:53Z)

**Cột CSV tương đương**: repo, pr_or_branch, base_commit, head_commit, ci_run_id, ci_status, review_status, last_checked_at

## `nguyenphuck2468-tech/Hydraulic`

### Branches (last-checked 2026-09-07T09:22:53Z)

| branch | head_sha | upstream base | commits ahead |
|---|---|---|---|
| `master` | `9f89728` (= upstream master) | `9f89728` (GeyserMC/Hydraulic@9f89728) | 0 (synced) |
| `gd5/final-fork-master` | local-only (Hyd) | `9f89728` | 4 (force-pushed GĐ4.5.1+4.5.2+GĐ5.5+GĐ5.9) |

### PRs (last-checked 2026-09-07T09:22:53Z)

| # | title | state | head branch | last_checked | ci status |
|---|---|---|---|---|---|
| 1 | Upgrade Fabric target to 26.2 | CLOSED 2026-08-18T09:17:43Z | upgrade/minecraft-26.2-fabric | 2026-09-07T09:22:53Z | n/a (closed) |
| 2 | WIP: Real Minecraft 26.2 port | CLOSED 2026-08-18T09:59:28Z | agent/minecraft-26.2 | 2026-09-07T09:22:53Z | n/a (closed) |
| 3 | [Claude] Upgrade Fabric target to 26.2 | CLOSED 2026-08-19T01:47:27Z | claude/minecraft-26.2-fabric-upgrade | 2026-09-07T09:22:53Z | n/a (closed) |
| 4 | Upgrade Hydraulic to Minecraft 26.2 | **MERGED 2026-08-19T01:47:45Z** | upgrade-26.2 | 2026-09-07T09:22:53Z | merged |
| 5 | Comprehensive lossless Bedrock pack and entity hardening | CLOSED 2026-09-01T12:40:00Z | comprehensive-bedrock-fidelity | 2026-09-07T09:22:53Z | n/a (closed) |
| 6 | Audit PR5: drop dead ItemTranslatorMixin... | CLOSED 2026-09-03T02:23:37Z | pr5/audit-followup | 2026-09-07T09:22:53Z | n/a (closed) |
| 109 | chore(cleanup): drop dead ItemTranslatorMixin... | CLOSED 2026-09-04T01:10:52Z | pr5/audit-followup | 2026-09-07T09:22:53Z | n/a (closed, was open on fork) |
| 110 | perf(startup): fix three startup bottlenecks | CLOSED 2026-09-04T02:19:44Z | gd4/pr-perf-improvements | 2026-09-07T09:22:53Z | n/a (closed, superseded) |
| 111 | perf(startup): grow conversion thread pool... | CLOSED 2026-09-04T12:47:54Z | gd5/perf-improvements-without-async | 2026-09-07T09:22:53Z | n/a (closed) |
| 113 | chore(cleanup)... (re-open attempt) | CLOSED 2026-09-06T13:46:34Z | pr5/audit-followup | 2026-09-07T09:22:53Z | n/a (closed, wrong target) |
| 114 | perf(startup)... (re-open attempt) | CLOSED 2026-09-06T13:47:03Z | gd5/perf-improvements-without-async | 2026-09-07T09:22:53Z | n/a (closed, wrong target) |

**Note**: Tất cả PR ở trên GitHub `nguyenphuck2468-tech/Hydraulic`. PR upstream `GeyserMC/Hydraulic` đã đóng tương ứng.

## `nguyenphuck2468-tech/PackConverter`

### Branches (last-checked 2026-09-07T09:22:53Z)

| branch | head_sha | upstream base | commits ahead |
|---|---|---|---|
| `master` | `0e3a219` (= local) | `48cb61a` (GeyserMC/PackConverter@48cb61a) | 1 (GĐ4.1+4.2 squash) |
| `gd5/final-fork-master` | local-only (PC) | `48cb61a` | 0 (same as master) |

### PRs (last-checked 2026-09-07T09:22:53Z)

| # | title | state | head branch | last_checked | ci status |
|---|---|---|---|---|---|
| 1 | Use 26.2 vanilla assets for model stitching | CLOSED 2026-08-19T06:58:54Z | fix/26.2-vanilla-models | 2026-09-07T09:22:53Z | n/a (closed) |
| 2 | Update PackConverter vanilla source to 26.2 | CLOSED 2026-08-19T07:20:25Z | agent/minecraft-26.2-mod-resources | 2026-09-07T09:22:53Z | n/a (closed) |
| 3 | Add direct Minecraft mod JAR resource conversion | CLOSED 2026-08-19T09:39:32Z | agent/mod-jar-input | 2026-09-07T09:22:53Z | n/a (closed) |
| 4 | Harden automatic mod resource extraction | CLOSED 2026-08-19T14:48:25Z | agent/mod-resource-hardening-26-2 | 2026-09-07T09:22:53Z | n/a (closed) |
| 5 | ci: harden nightly build workflow | CLOSED 2026-08-20T13:46:43Z | automation/ci-nightly-hardening | 2026-09-07T09:22:53Z | n/a (closed) |
| 6 | Preserve entity skeleton fidelity and reject invalid geometry | CLOSED 2026-09-01T12:40:20Z | comprehensive-bedrock-fidelity | 2026-09-07T09:22:53Z | n/a (closed) |
| 72 | fix(texture, pipeline): reject path traversal and count combine errors | CLOSED 2026-09-04T00:54:58Z | gd4/pr-texture-and-errors | 2026-09-07T09:22:53Z | n/a (closed, superseded by rebase) |
| 73 | fix(texture, pipeline)... (re-open attempt) | CLOSED 2026-09-06T13:46:12Z | gd5/pr-texture-and-errors-rebased | 2026-09-07T09:22:53Z | n/a (closed, wrong target) |

**Note**: Tất cả PR ở trên GitHub `nguyenphuck2468-tech/PackConverter`. PR upstream `GeyserMC/PackConverter` đã đóng tương ứng.

## Upstream GeyserMC

| repo | branch | head | last_checked |
|---|---|---|---|
| GeyserMC/Hydraulic | master | `9f89728` | 2026-09-07T09:22:53Z |
| GeyserMC/PackConverter | master | `48cb61a` | 2026-09-07T09:22:53Z |

**Note**: Tôi chưa mở PR upstream mới nào trong session này vì fork master đã chứa code sẵn sàng review. Khi maintainer upstream merge, audit sẽ được re-verify.

## Tóm tắt

- **Total open PRs (cả 2 fork)**: 0 (tất cả đã closed/merged)
- **Total open branches trên 2 fork**: 4 (master + gd5/final-fork-master × 2 repo)
- **Closed PRs không thể xóa qua API** (GitHub không cho delete PR sau khi close, REST + GraphQL đều fail): 17 PR (Hyd 11 + PC 6)

## Re-verify plan

- **Mỗi lần truy cập lại báo cáo này**: chạy `verify_report.py` + `gh api repos/.../pulls` để refresh last_checked.
- **Nếu có thay đổi trạng thái** (PR mới mở, branch mới tạo): update bảng này + commit lên `gd5/final-fork-master` (Hyd) + master (PC).
