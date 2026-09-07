# 03 — Risk & Rollback Log

**Nguyên tắc**: Mọi thao tác phá hủy (force-push, xóa branch, rebase) phải có: (1) backup đã tạo ở đâu (tag/commit hash), (2) lệnh rollback cụ thể, (3) người/điều kiện được phép revert.

## Risk Register

| # | Action | Date | Risk | Mitigation | Backup | Rollback command |
|---|---|---|---|---|---|---|
| R1 | `git push --force origin gd5/rebase-fork-master:master` (Hyd) | 2026-09-04T13:50Z | Mất lịch sử commit PR #5 (3 commit GĐ4.5.1+4.5.2+4.5.3) | Backup: KHÔNG tạo backup tag trước khi force-push. Phụ thuộc upstream `9f89728` làm reference cho recovery. | Upstream master `9f89728` | `git fetch upstream && git reset --hard 9f89728 && git push --force origin master` |
| R2 | `git push --force origin gd5/final-fork-master:master` (PC) | 2026-09-04T14:00Z | Mất `2f962cc` (rebase cũ với GĐ3.6a 5 commit) | Upstream `48cb61a` làm ref | Upstream master `48cb61a` | `git fetch upstream && git reset --hard 48cb61a && git push --force origin master` |
| R3 | `git push --delete origin/<branch>` × 14 (Hyd) + × 3 (PC) | 2026-09-07T07:30Z | Mất branch refs | Branch không có PR mở (tất cả PR đã closed) | N/A (branch không có giá trị) | N/A — không thể khôi phục branch đã xóa, nhưng code đã có trong các branch còn lại |
| R4 | `gh pr close 73 / 113 / 114` | 2026-09-06T13:46-47Z | Mất PR (đã close) | N/A — PR đã supersede, code vẫn trong branch | N/A — PR đã close không thể reopen, nhưng code trong branch còn nguyên vẹn | N/A |

## Các thao tác force-push đã thực hiện (chronological)

### 1. Hyd fork master — 2026-09-04T13:50Z
- **Action**: `git push --force origin gd5/rebase-fork-master:master`
- **Before**: fork master ở `5e8d620` (commit GĐ4.5 merge với GĐ4.5.3)
- **After**: fork master ở `b59018a` (rebase lên `9f89728` + 4 commit: GĐ4.5.1+4.5.2+GĐ5.5+GĐ5.9)
- **Backup**: KHÔNG tạo backup tag trước khi force-push. Phụ thuộc upstream `9f89728` làm reference cho recovery.
- **Rollback**: `git fetch upstream && git reset --hard 9f89728 && git push --force origin master`
- **Status**: Không rollback cần thiết — fork hiện AHEAD 4 commit so với upstream (GĐ4.5.1+4.5.2+GĐ5.5+GĐ5.9 + docs/follow-up-tasks.md + v2 audit report).

### 2. PC fork master — 2026-09-04T14:00Z
- **Action**: `git push --force origin gd5/final-fork-master:master`
- **Before**: fork master ở `2f962cc` (rebase cũ)
- **After**: fork master ở `0e3a219` (rebase lên `48cb61a` + cherry-pick GĐ4.1+4.2 squash)
- **Backup**: KHÔNG tạo backup tag. Phụ thuộc upstream `48cb61a`.
- **Rollback**: `git fetch upstream && git reset --hard 48cb61a && git push --force origin master`
- **Status**: Không rollback cần thiết — fork hiện sync với upstream.

## Cấu hình hiện tại (last-checked 2026-09-07T09:22:53Z)

### Hyd fork
- **Master HEAD**: `b59018a` (= upstream `9f89728` + 4 commit: `be956b9` GĐ4.5.1 + `9f0b08b` GĐ4.5.2 + `1ff5a43` GĐ5.5 + `b59018a` GĐ5.9 docs)
- **Branches**: 3 (master + gd5/final-fork-master + gd5/v2-audit-report)
- **PRs**: 0 open (tất cả 11 closed)

### PC fork
- **Master HEAD**: `0e3a219` (= upstream `48cb61a` + 1 commit)
- **Branches**: 2 (master + gd5/final-fork-master)
- **PRs**: 0 open (tất cả 6 closed)

## Recovery procedures

### Khôi phục Hyd fork master
```bash
cd /c/Users/Admin/.zcode/workspace/default/Hydraulic
git fetch upstream
git reset --hard 9f89728  # upstream master
git push --force origin master
# Verify: fork master = upstream master (0 commit ahead)
```

### Khôi phục PC fork master
```bash
cd /c/Users/Admin/.zcode/workspace/default/PackConverter
git fetch upstream
git reset --hard 48cb61a  # upstream master
git push --force origin master
# Verify: fork master = upstream master (0 commit ahead)
```

### Khôi phục nhánh gd5/final-fork-master
```bash
# Hyd
cd /c/Users/Admin/.zcode/workspace/default/Hydraulic
git checkout gd5/final-fork-master
git log --oneline -5  # verify đúng 4 commit

# PC (cùng tên)
cd /c/Users/Admin/.zcode/workspace/default/PackConverter
git checkout gd5/final-fork-master
git log --oneline -5  # verify đúng 1 commit
```

### Khôi phục nhánh đã xóa
**KHÔNG THỂ KHÔI PHỤC** — GitHub không cho restore branch đã xóa qua API. Cần maintainer intervention nếu cần.

## Risk không reversible (cần tránh trong tương lai)

1. **Force-push fork master mà không tạo backup tag** — luôn chạy `git tag audit-pre-<date>` trước khi force-push để có điểm khôi phục atomic.
2. **Xóa branch khi chưa chắc 100% không còn** — giữ lại ít nhất 7 ngày sau khi close PR tương ứng.
3. **Mở PR upstream sai target** (PR #73, #113, #114) — luôn verify `gh pr list --repo <upstream>` trước khi `gh pr create`.

## Điều kiện revert (khi nào cần rollback)

- **Hyd fork**: nếu maintainer GeyserMC merge PR #111 gây regression mới (F3 unverified), revert bằng cách xóa GĐ4.5.2 commit khỏi master.
- **PC fork**: nếu GĐ4.1+4.2 squash commit gây conflict nghiêm trọng với upstream mới, revert về upstream master + đợi upstream merge.
- **Cả 2 fork**: nếu audit sau (re-verify) phát hiện lỗi mới cần thiết kế lại từ đầu, revert về upstream + làm lại trên nhánh mới.
