#!/usr/bin/env bash
set -euo pipefail

# Absolute or relative path to your EventDebug repo inside WSL
# Adjust this if your repo is elsewhere (e.g. /mnt/c/Java\ Developer/EventDebug)
PROJECT_DIR="$HOME/EventDebug"

echo "=== Updating apt and installing Podman ==="
sudo apt update
sudo apt install -y podman

echo "=== Installing podman-compose (via pip if not available via apt) ==="
if ! command -v podman-compose >/dev/null 2>&1; then
  if sudo apt install -y podman-compose 2>/dev/null; then
    echo "podman-compose installed via apt."
  else
    echo "apt podman-compose not available, installing via pip..."
    sudo apt install -y python3-pip
    pip3 install --user podman-compose
    export PATH="$HOME/.local/bin:$PATH"
  fi
fi

echo "Podman version: $(podman --version)"
echo "podman-compose version: $(podman-compose --version || echo 'not found on PATH')"

echo "=== Ensuring project directory exists: $PROJECT_DIR ==="
if [ ! -d "$PROJECT_DIR" ]; then
  echo "ERROR: Project directory $PROJECT_DIR does not exist."
  echo "Clone or copy your EventDebug repo there, or edit PROJECT_DIR in this script."
  exit 1
fi

cd "$PROJECT_DIR"

echo "=== Building and starting full stack with podman-compose ==="
podman-compose up --build -d

echo "=== Done ==="
echo "Services should now be running. Check with:"
echo "  podman ps"
echo
echo "From Windows, open:  http://localhost:9090"

