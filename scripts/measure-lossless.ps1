$StorageDir = "C:\Users\Admin\Downloads\hydraulic-archive-extract\hydraulic\storage"
$OutFile = "C:\Users\Admin\Downloads\lossless-output.txt"

if (-not (Test-Path $StorageDir)) {
    Write-Error "storage dir not found"
    exit 1
}

$lines = New-Object System.Collections.Generic.List[string]
[void]$lines.Add(("mod_id".PadRight(40) + "size_kb".PadLeft(10) + "files".PadLeft(8) + "textures".PadLeft(10) + "models".PadLeft(8) + "entities".PadLeft(10) + "blocks".PadLeft(8) + "  status"))
[void]$lines.Add(([string]'-' * 100))

$totalSize = 0
$totalFiles = 0
$missing = New-Object System.Collections.Generic.List[string]

$mods = Get-ChildItem -Directory $StorageDir | Sort-Object Name
foreach ($mod in $mods) {
    $mcpackPath = Join-Path $mod.FullName ($mod.Name + ".mcpack")
    if (Test-Path $mcpackPath) {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($mcpackPath)
        try {
            $entries = $zip.Entries
            $textureCount = 0
            $modelCount = 0
            $entityCount = 0
            $blockCount = 0
            foreach ($e in $entries) {
                $name = $e.FullName
                if ($name.StartsWith("textures/")) { $textureCount++ }
                elseif ($name -like "*models/*") { $modelCount++ }
                elseif ($name.StartsWith("entity/")) { $entityCount++ }
                elseif ($name.StartsWith("blocks/")) { $blockCount++ }
            }
            $size = (Get-Item $mcpackPath).Length
            $line = $mod.Name.PadRight(40) + ([int]($size / 1024)).ToString().PadLeft(10) + $entries.Count.ToString().PadLeft(8) + $textureCount.ToString().PadLeft(10) + $modelCount.ToString().PadLeft(8) + $entityCount.ToString().PadLeft(10) + $blockCount.ToString().PadLeft(8)
            [void]$lines.Add($line)
            $totalSize += $size
            $totalFiles += $entries.Count
        } finally {
            $zip.Dispose()
        }
    } else {
        $materials = Join-Path $mod.FullName "materials.json"
        if (Test-Path $materials) {
            $line = $mod.Name.PadRight(40) + ([int]((Get-Item $materials).Length / 1024)).ToString().PadLeft(10) + "0".PadLeft(8) + "0".PadLeft(10) + "0".PadLeft(8) + "0".PadLeft(10) + "0".PadLeft(8) + "  [MISSING .mcpack]"
            [void]$lines.Add($line)
            [void]$missing.Add($mod.Name)
        }
    }
}

[void]$lines.Add("")
[void]$lines.Add(("Total: " + $mods.Count + " mods, " + [int]($totalSize / 1024) + " KB, " + $totalFiles + " files"))
if ($missing.Count -gt 0) {
    [void]$lines.Add(("Mods without .mcpack: " + $missing.Count))
    foreach ($m in $missing) { [void]$lines.Add(("  - " + $m)) }
}

Set-Content -Path $OutFile -Value $lines -Encoding utf8
Write-Host ("Wrote " + $lines.Count + " lines to " + $OutFile)
