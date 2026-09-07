# 04 — Follow-ups + Glossary

## Glossary (mã "GĐ" theo từng session)

| Mã | Tên đầy đủ | Mục tiêu ban đầu | Trạng thái |
|---|---|---|---|
| GĐ1 | Catch-ignored logging (8 file) + GeoUtil texture size overload | Audit PR #5 mục 1.1-1.2 | ✅ Done (LOCAL only) |
| GĐ2.4 | Per-mod ConversionBudget + system property override | Audit PR #5 mục 1.3 | ✅ Done (LOCAL only) |
| GĐ2.5 | Regression guard test cho addCodeSource missing-class | Audit PR #5 mục 1.4 | ✅ Done (LOCAL only) |
| GĐ3.6a | walkClassLoaderUrls + cache theo modId | Audit PR #5 mục 1.5 | ✅ Done (LOCAL only) |
| GĐ3.6b | PC side ReflectionInput đã có sẵn (verify) | Audit PR #5 mục 1.5 | ✅ Verified |
| GĐ3.7 | Drop dead ItemTranslatorMixin | Audit PR #5 mục 1.6 | ✅ Done (Hyd PR #109) |
| GĐ3.8 | Verify 7 model constructor (alexsmobs runtime) | Audit PR #5 mục 1.7 | ✅ Verified |
| GĐ3.9 | 7 TODO BlockPackModule/CreativeMappings | Audit PR #5 mục 1.8 | ✅ Done (Hyd PR #109) |
| GĐ3.10 | PackProvenance JSON embedded | Audit PR #5 mục 1.9 | ✅ Done (LOCAL only, deprecated) |
| GĐ4.1 | Path traversal fix `TextureConverter.resolveSafeRelative` | Audit 2026-09-03 từ log thật | ✅ Done (PC PR #72) |
| GĐ4.2 | Error counting `CombineContext.error()` override | Audit 2026-09-03 từ log thật | ✅ Done (PC PR #72) |
| GĐ4.3 | SNAPSHOT pin 3.4.3 → 3.5.8 | Audit 2026-09-03 | ⚠️ Code done, fabric-loom override cần debug |
| GĐ4.4 | `scripts/measure-lossless.ps1` + sample output | Audit 2026-09-03 | ✅ Done |
| GĐ4.5.1 | Thread pool `max(2, cores-1)` + property override | Audit 2026-09-03 | ✅ Done (Hyd PR #111) |
| GĐ4.5.2 | Bỏ `hydraulicUpdated` global flag | Audit 2026-09-03 | ✅ Done (Hyd PR #111) |
| GĐ4.5.3 | Async convert inline budget 5s (REVERTED) | Audit 2026-09-03 | ❌ Superseded (mất 84% pack) |
| GĐ4.5.5 | Sửa 2 catch-block nuốt lỗi (PackPackager, MetadataPackModule) | Audit 2026-09-04 | ✅ Done (Hyd branch `gd5/catch-block-audit`) |
| GĐ4.5.9 | Trace biomesoplenty parent model lặp log | Audit 2026-09-04 | ✅ Traced (P2 log noise, biomesoplenty bug) |
| GĐ4.6 | Theo dõi creative-api/unnamed cho special_render_type | Audit 2026-09-03 | ⏸️ Theo dõi (F5) |
| GĐ4.7 | Quy trình báo cáo cột "mergeable" | Audit 2026-09-04 | ✅ Done (memory note + báo cáo) |
| GĐ5.7 | Verify lại từng claim bằng grep thật trên artifact | Audit Claude 2026-09-07 | ✅ Done (5 file báo cáo mới) |

## Follow-up items (cần owner + deadline)

| ID | Task | Owner | Deadline | Status |
|---|---|---|---|---|
| FU1 | Maintainer `GeyserMC` review + merge [PR #72 (PackConverter)](https://github.com/GeyserMC/PackConverter/pull/72) (GĐ4.1+4.2) | GeyserMC | ASAP (P0) | ⏳ Open |
| FU2 | Maintainer `GeyserMC` review + merge [PR #109 (Hydraulic)](https://github.com/GeyserMC/Hydraulic/pull/109) (GĐ3.7+3.9 26.2) | GeyserMC | ASAP | ⏳ Open |
| FU3 | Maintainer `GeyserMC` review + merge [PR #111 (Hydraulic)](https://github.com/GeyserMC/Hydraulic/pull/111) (GĐ4.5.1+4.5.2, drop GĐ4.5.3) | GeyserMC | ASAP | ⏳ Open |
| FU4 | Re-verify F3 (GĐ4.5.3 regression "84% pack mất") bằng log build CÓ GĐ4.5.3 — cần capture log mới | nguyenphuck2468-tech | 2026-09-14 | ⏸️ Chờ build |
| FU5 | Debug GĐ4.3 fabric-loom override repos — cần `loom { repositories { mavenLocal() } }` hoặc JitPack | nguyenphuck2468-tech | 2026-09-21 | ⏸️ Blocked by upstream Hyd refactor |
| FU6 | Xóa 14 PR closed cũ trên 2 fork (UI thủ công — API không cho) | nguyenphuck2468-tech | 2026-09-10 | ⏸️ Manual |
| FU7 | Đo GĐ2.3 lossless vs pruned — cần 2 archive (pre/post PR #5) | nguyenphuck2468-tech | Khi có pre-PR-5 archive | ⏸️ Chờ |
| FU8 | Theo dõi `team.unnamed:creative-api` bump hỗ trợ `alexsmobs:icon` | upstream | TBD | ⏸️ Theo dõi |
| FU9 | Submit `biomesoplenty` parent model issue lên `Biomes O' Plenty` GitHub (template_sign_rot_N không tồn tại 26.2) | Biomes O' Plenty | TBD | ⏸️ TBD |
| FU10 | GeyserMC/Hydraulic cần `GeyserReloadResourcePacksEvent` để GĐ4.5.3 fix được | GeyserMC | Long-term | ⏸️ API request |

## Severity scale (chuẩn hóa 4 mức)

| Mức | Định nghĩa | Ví dụ trong report này |
|---|---|---|
| **P0** | Mất dữ liệu/pack, ảnh hưởng >50% người dùng, không workaround | F1, F2, F3 (unverified) |
| **P1** | Lỗi chức năng rõ ràng, ảnh hưởng subset người dùng, có workaround thủ công | F5, FU10 |
| **P2** | Lỗi log/observability gây nhiễu nhưng không ảnh hưởng chức năng | F4, F6, F8 |
| **P3** | Cosmetic/dọn dẹp | F7 |

## Phương pháp luận (cho báo cáo audit tương lai)

Mỗi báo cáo audit PHẢI tuân thủ checklist 12 điểm (áp dụng từ báo cáo đánh giá Claude 2026-09-07):

1. **Không bao giờ trích dẫn log/code "từ trí nhớ"** — chỉ copy-paste từ file thật
2. **Gắn nhãn mọi claim** bằng `[VERIFIED @ path:line, command X, ISO8601]`, `[INFERRED FROM path]`, hoặc `[ASSUMED / UNVERIFIED]`
3. **Số liệu đếm phải có lệnh thật** kèm output (không ngoại suy từ mẫu nhỏ)
4. **Trước khi gọi là "FINAL"**: chạy self-check `verify_report.py` + liệt kê kết quả ở đầu báo cáo
5. **Phân loại severity chuẩn** P0-P3 cho mọi finding
6. **Tách biệt tài liệu** theo mục đích: exec summary / findings / PR status / risk-rollback / followups
7. **Không đưa path cá nhân** (`C:\Users\...`) vào tài liệu chia sẻ
8. **Glossary** mọi mã tự đặt (GĐx.y) trong cùng file hoặc file riêng
9. **Executive Summary ≤10 dòng** ở đầu báo cáo
10. **Timestamp theo GIỜ** cho mọi state lấy từ API bên ngoài (CI, PR, branch)
11. **Với kết luận "không thể làm"**: liệt kê chính xác lệnh/API đã thử + response
12. **Definition of Done** checklist + follow-up items có owner + deadline
