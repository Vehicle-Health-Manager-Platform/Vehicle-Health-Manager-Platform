# Idempotently create the directory and page-file skeleton from the delivery document.
# This script never overwrites existing files.
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path

$backendModules = @(
  'gateway', 'common', 'user', 'vehicle', 'merchant', 'order', 'coupon',
  'point', 'service', 'ai', 'check', 'community', 'admin', 'job'
)

$emptyDirectories = @(
  'backend/src/main/resources',
  'backend/src/test/java/com/autocare/platform',
  'services/ocr/src',
  'deploy/compose', 'deploy/nginx', 'deploy/monitoring',
  'tests/e2e', 'tests/load', 'tests/security'
)

foreach ($module in $backendModules) {
  $emptyDirectories += "backend/src/main/java/com/autocare/platform/$module"
}

foreach ($app in @('owner', 'merchant', 'technician', 'admin')) {
  $emptyDirectories += "apps/$app/src/components"
  $emptyDirectories += "apps/$app/src/assets"
  $emptyDirectories += "apps/$app/src/services"
  $emptyDirectories += "apps/$app/src/store"
}

foreach ($relativeDirectory in $emptyDirectories) {
  $absoluteDirectory = Join-Path $projectRoot $relativeDirectory
  New-Item -ItemType Directory -Path $absoluteDirectory -Force | Out-Null
  $keepFile = Join-Path $absoluteDirectory '.gitkeep'
  if (-not (Test-Path -LiteralPath $keepFile)) {
    New-Item -ItemType File -Path $keepFile | Out-Null
  }
}

# C01-C25: these filenames match the exact owner routes in delivery document §3.1.
$ownerPages = @(
  'auth/login', 'vehicle/add', 'vehicle/plate', 'vehicle/license',
  'vehicle/vin', 'vehicle/manual', 'home/index', 'service/index',
  'service/detail', 'merchant/detail', 'ai/index', 'ai/plan',
  'archive/index', 'archive/record-add', 'mine/index', 'order/list',
  'order/detail', 'order/book', 'welfare/index', 'welfare/invite',
  'points/index', 'community/circle', 'check/confirm',
  'order/progress', 'order/review'
)

# The source document names these pages but does not assign route paths.
$merchantPages = @(
  'login', 'dashboard', 'orders/list', 'orders/detail', 'pickup-check',
  'owner-confirm-wait', 'protection', 'assignment', 'verify', 'refund',
  'service-pricing', 'coupons', 'assessment', 'analytics', 'customers'
)
$technicianPages = @(
  'login', 'dashboard', 'order-detail', 'accept', 'process-photo',
  'fault-part-photo', 'finish-photo', 'repair-plan', 'parts', 'quality-sign'
)
$adminPages = @(
  'login', 'dashboard', 'merchant-audit', 'merchant-list',
  'standard-project', 'vehicle-model', 'assessment', 'coupon-pool',
  'content-review', 'price-monitor', 'community-review', 'analytics',
  'settings'
)

$pageGroups = @(
  @{ app = 'owner'; files = $ownerPages; element = 'div' },
  @{ app = 'merchant'; files = $merchantPages; element = 'div' },
  @{ app = 'technician'; files = $technicianPages; element = 'div' },
  @{ app = 'admin'; files = $adminPages; element = 'div' }
)

foreach ($group in $pageGroups) {
  foreach ($page in $group.files) {
    $relativeFile = "apps/$($group.app)/src/pages/$page.vue"
    $absoluteFile = Join-Path $projectRoot $relativeFile
    $parentDirectory = Split-Path -Parent $absoluteFile
    New-Item -ItemType Directory -Path $parentDirectory -Force | Out-Null
    if (-not (Test-Path -LiteralPath $absoluteFile)) {
      $content = "<!-- Page scaffold only; see the delivery document and docs/SPEC.md. -->`n<template>`n  <$($group.element)></$($group.element)>`n</template>"
      Set-Content -LiteralPath $absoluteFile -Value $content -Encoding utf8
    }
  }
}

Write-Output "Scaffold ready: $($backendModules.Count) backend packages, $($ownerPages.Count) owner pages, $($merchantPages.Count) merchant pages, $($technicianPages.Count) technician pages, $($adminPages.Count) admin pages."
