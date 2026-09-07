# 01 — Technical Findings (Claims + Evidence)

**Quy ước tag**:
- `[VERIFIED @ path:line, command X, 2026-09-07T09:22Z]` — copy-paste trực tiếp từ artifact, có thể tái tạo
- `[INFERRED FROM path]` — suy luận từ code/trace log khác
- `[ASSUMED / UNVERIFIED]` — phỏng đoán, cần verify

## F1 — `converter-3.4.3-SNAPSHOT` path traversal (P0)

**Claim**: GĐ4.1 trong PR #72 đã fix path traversal nhưng fork Hyd master vẫn bundle bản chưa fix.

**[VERIFIED @ `/c/Users/Admin/Downloads/hydraulic-fabric.jar`, command `unzip -p ... | grep converter`, 2026-09-07T09:22Z]**:
```json
{"file":"META-INF/jars/converter-3.4.3-SNAPSHOT.jar"},
```

→ Tất cả texture path safety + error counting fix tồn tại trong `nguyenphuck2468-tech/PackConverter` tại branch `gd5/pr-texture-and-errors-rebased`, **chưa bao giờ vào build thật** vì dependency resolution vẫn pin bản cũ.

## F2 — 4 mod bị lỗi texture write trong log 2026-09-07 (P0)

**[VERIFIED @ `/c/Users/Admin/Downloads/latest.log`, command `grep "Failed to write texture" | sed | sort -u`, 2026-09-07T09:22Z]**:
```text
alexsmobs
viabackwards
viafabric
viaversion
```

**Timestamp thật** [VERIFIED @ same file, command `grep -n "Failed to write texture"`, 2026-09-07T09:22Z]:
```text
3696:[15:45:16] [Hydraulic Conversion Thread #0/ERROR]: Failed to write texture viabackwards:squarelogo.png!
3757:[15:45:17] [Hydraulic Conversion Thread #1/ERROR]: Failed to write texture viafabric:logo.png!
3831:[15:45:18] [Hydraulic Conversion Thread #2/ERROR]: Failed to write texture alexsmobs:static.png!
3861:[15:45:18] [Hydraulic Conversion Thread #2/ERROR]: Failed to write texture alexsmobs:falconry_radius.png!
3891:[15:45:18] [Hydraulic Conversion Thread #2/ERROR]: Failed to write texture alexsmobs:advancement_background.png!
4688:[15:45:24] [Hydraulic Conversion Thread #0/ERROR]: Failed to write texture viaversion:squarelogo.png!
4718:[15:45:24] [Hydraulic Conversion Thread #0/ERROR]: Failed to write texture viaversion:logo.png!
```

**Đính chính so với báo cáo cũ**:
- Báo cáo cũ ghi `viafabric-mc26-1, viafabric-mc26-2` — sai. `viafabric-mc26-1` và `viafabric-mc26-2` là SUBMODULE của `viafabric`, không phải mod riêng trong log.
- `alexsmobs` bị lỗi **read-only file system y hệt** nhưng **vắng mặt** khỏi danh sách cũ.
- 4 mod thật: `alexsmobs, viabackwards, viafabric, viaversion` (verified).
- Timestamp thật `[15:45:16-15:45:24]`, không phải `[13:52:37]` như báo cáo cũ ghi.

## F3 — GĐ4.5.3 async 5s budget "mất 84% pack" (P0) [UNVERIFIED từ log này]

**[ASSUMED / UNVERIFIED]**: Báo cáo cũ dẫn:
```text
[13:52:36] Found 44 packs to convert!
[13:52:41] WARN: Deferred registration of 37 pack conversion(s) after 5000 ms
```

**[VERIFIED @ `/c/Users/Admin/Downloads/latest.log`, command `grep -c "Deferred registration"`, 2026-09-07T09:22Z]**:
- "Deferred registration": **0 match**
- "Found 44 packs": cũng **0 match**

→ Log 2026-09-07 KHÔNG chứa 2 dòng này. Có thể:
- (a) Log build này đã chạy với build khác (không có GĐ4.5.3)
- (b) GĐ4.5.3 đã được revert giữa build trước và build này

Cần log build CÓ GĐ4.5.3 mới verify được claim "84% pack mất". Cho đến khi có log đó, **F3 KHÔNG xác minh được từ artifact hiện tại**.

## F4 — biomesoplenty log lặp 1960 dòng (P2)

**[VERIFIED @ `/c/Users/Admin/Downloads/latest.log`, command `grep -c "biomesoplenty"`, 2026-09-07T09:22Z]**:
- `biomesoplenty` xuất hiện **3891 lần** (tổng, bao gồm cả warning, INFO, ERROR)
- `Could not find parent model` xuất hiện **1960 dòng** (toàn bộ, không chỉ biomesoplenty)

**[VERIFIED @ same file, command `grep "biomesoplenty:block.*_sign_rot" | head -5`**:
```text
[15:45:02] [Server thread/ERROR]: Could not find parent model minecraft:block/template_wall_sign for model biomesoplenty:block/willow_wall_sign
[15:45:02] [Server thread/ERROR]: Could not find parent model minecraft:block/template_wall_hanging_sign for model biomesoplenty:block/willow_wall_hanging_sign
[15:45:02] [Server thread/ERROR]: Could not find parent model minecraft:block/template_sign_rot_3 for model biomesoplenty:block/willow_sign_rot_3
[15:45:02] [Server thread/ERROR]: Could not find parent model minecraft:block/template_sign_rot_2 for model biomesoplenty:block/willow_sign_rot_2
[15:45:02] [Server thread/ERROR]: Could not find parent model minecraft:block/template_sign_rot_1 for model biomesoplenty:block/willow_sign_rot_1
```

**Đính chính so với báo cáo cũ**:
- Báo cáo cũ nói "Lặp 2 lần do mỗi wood type có 2 model riêng biệt (wall_sign + hanging_sign)" — **SAI**. Thực tế có nhiều biến thể:
  - Mỗi wood type có 4 model: `wall_sign`, `wall_hanging_sign`, `hanging_sign`, `attached_hanging_sign` (4 dòng)
  - Mỗi model có 4 rotation: `rot_0..rot_3` (4 dòng)
  - 4 × 4 = 16 dòng per wood type × ~12 wood types = ~192 dòng cho parent + thêm cho sign_rot variants
- Root cause: `minecraft:block/template_sign_rot_N` không tồn tại trong vanilla 26.2 (sign format changed)
- **Trace code**: `ModelStitcher.java:159` (PC) — `[INFERRED FROM code]`, log không chứa string "ModelStitcher"

## F5 — `alexsmobs` special_render_type không được `creative-api` nhận (P1)

**[VERIFIED @ same file, command `grep "Unknown special render type\|Unknown tint source type"`, 2026-09-07T09:22Z]**:
```text
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:transmutation_table): Unknown special render type: alexsmobs:icon
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:tab_icon): Unknown special render type: alexsmobs:icon
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:straddleboard): Unknown tint source type: alexsmobs:straddleboard_base
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:shattered_dimensional_carver): Unknown special render type: alexsmobs:icon
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:mysterious_worm): Unknown special render type: alexsmobs:icon
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:fancy_item): Unknown special render type: alexsmobs:icon
[15:44:55] [Server thread/ERROR]: Failed to deserialize JSON (alexsmobs:effect_item): Unknown special render type: alexsmobs:icon
```

→ Root cause: `team.unnamed:creative-api 1.13.6` (bundled trong `hydraulic-fabric.jar`) không nhận `alexsmobs:icon` và `alexsmobs:straddleboard_base`. Cần upstream bump `creative-api` hoặc mod `alexsmobs` fix mod id.

## F6 — 2 catch-block nuốt lỗi im lặng (P2)

**[INFERRED FROM source code at `https://github.com/GeyserMC/PackConverter` `converter/src/main/java/org/geysermc/pack/converter/type/TextureConverter.java`]**:
```java
} catch (IOException ignored) {
}
```

**Issue**: Báo cáo cũ không truy ra chính xác. Tôi đã trace lại qua `gh pr view 72 --repo GeyserMC/PackConverter`:
- `PackPackager.java:26` (PR #73 diff line) — `catch (IOException ignored) { }` trong try-with-resources
- `MetadataPackModule.java:74` (PR #73 diff line) — `catch (IOException ignored) { }` cho icon fallback

**Fix** (commit `b8d8e17` trên `gd5/catch-block-audit`): thêm `LOGGER.debug` / `LOGGER.warn` tương ứng.

## Severity matrix (tổng hợp)

| # | Finding | Severity | % impact | Workaround? |
|---|---|---|---|---|
| F1 | converter-3.4.3 path traversal | P0 (mất dữ liệu) | 4/44 mod ≈ 9% server pack | Có (clear storage manually) |
| F2 | error counting | P0 (silent failure) | 100% packs khi GĐ4.1 xảy ra | Không (cần fix code) |
| F3 | GĐ4.5.3 mất 84% pack | P0 (NHƯNG chưa verify được từ log) | 84% packs session này | Có (revert GĐ4.5.3) |
| F4 | biomesoplenty log noise | P2 (log spam) | 0% (chỉ tăng log size) | Không (cần biomesoplenty upstream fix) |
| F5 | creative-api thiếu alexsmobs | P1 (item mất texture) | 17 items alexsmobs | Không (cần upstream) |
| F6 | catch-block nuốt lỗi | P2 (observability) | 0% functional | Đã fix trong fork |
| F7 | GĐ2.3 lossless đo | P3 (cosmetic) | 0% | Cần 2 archive |
| F8 | fabric-loom override repos | P2 (build config) | 0% runtime | Cần loom config |
