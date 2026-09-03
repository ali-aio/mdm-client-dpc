#!/usr/bin/env bash
# Install the pre-ranked fast Arch mirrors for this region (Pakistan -> nearest JP/SG/CN/IN).
# These were already measured by reflector; this script just applies them (no re-ranking).
# Run as root:
#
#   sudo ./scripts/fast-mirrors.sh
set -euo pipefail

log() { printf '\033[1;35m[mirrors]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[mirrors] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "Run me as root:  sudo $0"

BACKUP="/etc/pacman.d/mirrorlist.bak.$(date +%Y%m%d-%H%M%S)"
log "Backing up current mirrorlist -> $BACKUP"
cp /etc/pacman.d/mirrorlist "$BACKUP"

log "Writing pre-ranked mirrorlist..."
cat > /etc/pacman.d/mirrorlist <<'EOF'
# Ranked by measured download rate (region: JP/SG/CN/IN)
Server = https://jp.mirrors.cicku.me/archlinux/$repo/os/$arch
Server = https://sg.mirrors.cicku.me/archlinux/$repo/os/$arch
Server = https://mirrors.nju.edu.cn/archlinux/$repo/os/$arch
Server = https://singapore.mirror.pkgbuild.com/$repo/os/$arch
Server = https://mirror.funami.tech/arch/$repo/os/$arch
Server = https://in.arch.niranjan.co/$repo/os/$arch
Server = https://sg.arch.niranjan.co/$repo/os/$arch
Server = https://mirror.maa.albony.in/archlinux/$repo/os/$arch
Server = https://mirror.keiminem.com/archlinux/$repo/os/$arch
Server = https://mirror2.keiminem.com/archlinux/$repo/os/$arch
EOF

# Make sure parallel downloads are on.
if ! grep -qE '^\s*ParallelDownloads' /etc/pacman.conf; then
    log "Enabling ParallelDownloads=5 in /etc/pacman.conf"
    sed -i 's/^#\s*ParallelDownloads.*/ParallelDownloads = 5/' /etc/pacman.conf || \
        echo 'ParallelDownloads = 5' >> /etc/pacman.conf
fi

log "Refreshing package databases..."
pacman -Syy

log "Done. Restore anytime with:  sudo cp $BACKUP /etc/pacman.d/mirrorlist"
